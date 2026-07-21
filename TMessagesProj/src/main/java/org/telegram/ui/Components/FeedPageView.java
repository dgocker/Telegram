package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.feed.FeedController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/**
 * Полноэкранная страница поста в TikTok-ленте: медиа на весь экран
 * (карусель для альбомов), выжимка в нижней трети, разворот полного текста
 * с блюром, столбик кнопок действий справа.
 */
public class FeedPageView extends FrameLayout {

    public interface Delegate {
        void onDislike(FeedController.FeedPost post);
        void onOpenComments(FeedController.FeedPost post);
        void onShareTelegram(FeedController.FeedPost post);
        void onShareExternal(FeedController.FeedPost post);
        void onOpenFullPost(FeedController.FeedPost post);
        void onOpenChannel(FeedController.FeedPost post);
    }

    private final int currentAccount;
    private Delegate delegate;

    private FeedController.FeedPost post;
    private final ArrayList<MessageObject> mediaMessages = new ArrayList<>();
    private boolean active;
    private int carouselPosition;

    private final FrameLayout mediaContainer;
    private final RecyclerView carousel;
    private final CarouselAdapter carouselAdapter = new CarouselAdapter();
    private final DotsIndicator dotsIndicator;
    private final LinearLayout bottomOverlay;
    private final FrameLayout channelRow;
    private final BackupImageView avatarImageView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TextView nameTextView;
    private final TextView infoTextView;
    private final TextView summaryTextView;
    private final TextView centerTextView;
    private final LinearLayout buttonsColumn;
    private ActionButton commentsButton;
    private TextView commentsCountView;
    private boolean commentsEnabled;
    private final FrameLayout fullTextOverlay;
    private final LinkSpanTextView fullTextView;

    private final FeedVideoSeekBar videoSeekBar;
    private boolean seeking;
    private VideoPlayer videoPlayer;
    private MediaItemView videoItem;
    private MessageObject videoPlayerMessage;
    private boolean manuallyPaused;

    private int bottomInset;
    private int topInset;
    private boolean fullTextShown;
    private boolean overlaysHiddenByTouch;
    private final Runnable restoreOverlaysRunnable = () -> setOverlaysHidden(false);

    private final Runnable seekTicker = new Runnable() {
        @Override
        public void run() {
            if (videoSeekBar.getVisibility() == VISIBLE) {
                videoSeekBar.invalidate();
                AndroidUtilities.runOnUIThread(this, 200);
            }
        }
    };

    public FeedPageView(Context context, int currentAccount) {
        super(context);
        this.currentAccount = currentAccount;
        setBackgroundColor(Color.BLACK);

        mediaContainer = new FrameLayout(context);
        addView(mediaContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        // для текстовых постов (нет MediaItemView) жесты ловим здесь:
        // тап открывает/закрывает полный текст, долгое нажатие прячет оверлеи
        mediaContainer.setOnClickListener(v -> {
            if (fullTextShown) {
                setFullTextShown(false);
            } else if (post != null && mediaMessages.isEmpty() && !TextUtils.isEmpty(post.getText())) {
                setFullTextShown(true);
            }
        });
        mediaContainer.setOnLongClickListener(v -> {
            setOverlaysHidden(true);
            mediaContainer.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            return true;
        });
        mediaContainer.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && overlaysHiddenByTouch) {
                setOverlaysHidden(false);
            }
            return false;
        });

        carousel = new RecyclerView(context);
        carousel.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        carousel.setAdapter(carouselAdapter);
        carousel.setItemAnimator(null);
        new PagerSnapHelper().attachToRecyclerView(carousel);
        carousel.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                    int position = lm != null ? lm.findFirstCompletelyVisibleItemPosition() : -1;
                    if (position >= 0 && position != carouselPosition) {
                        carouselPosition = position;
                        dotsIndicator.setSelected(position);
                        updateActiveVideo();
                    }
                }
            }
        });
        mediaContainer.addView(carousel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // нижняя треть: канал (всегда виден) + выжимка (может прятаться).
        // без горизонтального паддинга — иначе точки уходят влево; отступы у строк отдельно
        bottomOverlay = new LinearLayout(context);
        bottomOverlay.setOrientation(LinearLayout.VERTICAL);
        bottomOverlay.setPadding(0, dp(30), 0, dp(10));
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xCC000000, 0x66000000, 0x00000000});
        bottomOverlay.setBackground(shade);
        addView(bottomOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        // точки-индикатор карусели над панелью, строго по центру экрана
        dotsIndicator = new DotsIndicator(context);
        bottomOverlay.addView(dotsIndicator, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 16, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8));

        // полоса перемотки видео у нижнего края (над панелью канала)
        videoSeekBar = new FeedVideoSeekBar(context);
        videoSeekBar.setVisibility(GONE);
        videoSeekBar.setDelegate(new FeedVideoSeekBar.Delegate() {
            @Override
            public int getDurationMs() {
                return videoPlayer != null ? (int) videoPlayer.getDuration() : 0;
            }

            @Override
            public int getProgressMs() {
                return videoPlayer != null ? (int) videoPlayer.getCurrentPosition() : 0;
            }

            @Override
            public void onSeek(int ms) {
                if (videoPlayer != null) {
                    videoPlayer.seekTo(ms, true);
                }
            }

            @Override
            public void onDragStart() {
                seeking = true;
            }

            @Override
            public void onDragEnd() {
                seeking = false;
            }
        });
        addView(videoSeekBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.BOTTOM));

        channelRow = new FrameLayout(context);
        bottomOverlay.addView(channelRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 14, 0, 72, 6));

        avatarImageView = new BackupImageView(context);
        avatarImageView.setRoundRadius(dp(18));
        avatarImageView.setOnClickListener(v -> {
            if (delegate != null && post != null) {
                delegate.onOpenChannel(post);
            }
        });
        channelRow.addView(avatarImageView, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        nameTextView = new TextView(context);
        nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameTextView.setTypeface(AndroidUtilities.bold());
        nameTextView.setTextColor(Color.WHITE);
        nameTextView.setSingleLine(true);
        nameTextView.setEllipsize(TextUtils.TruncateAt.END);
        nameTextView.setShadowLayer(dp(2), 0, dp(1), 0xB3000000);
        nameTextView.setOnClickListener(v -> {
            if (delegate != null && post != null) {
                delegate.onOpenChannel(post);
            }
        });
        channelRow.addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 46, 2, 0, 0));

        infoTextView = new TextView(context);
        infoTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        infoTextView.setTextColor(0xCCFFFFFF);
        infoTextView.setSingleLine(true);
        infoTextView.setShadowLayer(dp(2), 0, dp(1), 0xB3000000);
        channelRow.addView(infoTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 46, 21, 0, 0));

        summaryTextView = new TextView(context);
        summaryTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        summaryTextView.setTextColor(Color.WHITE);
        summaryTextView.setLineSpacing(dp(2), 1f);
        summaryTextView.setEllipsize(TextUtils.TruncateAt.END);
        summaryTextView.setShadowLayer(dp(2), 0, dp(1), 0x66000000);
        summaryTextView.setOnClickListener(v -> setFullTextShown(true));
        bottomOverlay.addView(summaryTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 6, 72, 0));

        // для постов без медиа текст стоит по центру экрана, а не в нижней трети
        centerTextView = new TextView(context);
        centerTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        centerTextView.setTextColor(Color.WHITE);
        centerTextView.setLineSpacing(dp(3), 1f);
        centerTextView.setGravity(Gravity.CENTER);
        centerTextView.setEllipsize(TextUtils.TruncateAt.END);
        centerTextView.setVisibility(GONE);
        centerTextView.setOnClickListener(v -> setFullTextShown(true));
        addView(centerTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 20, 40, 76, 90));

        // полный текст поверх с блюром: мало текста — по центру, много — растёт и скроллится
        fullTextOverlay = new FrameLayout(context);
        fullTextOverlay.setBackgroundColor(0xB3000000);
        fullTextOverlay.setVisibility(GONE);
        fullTextOverlay.setOnClickListener(v -> setFullTextShown(false));
        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setFillViewport(true);
        FrameLayout centerWrapper = new FrameLayout(context);
        centerWrapper.setOnClickListener(v -> setFullTextShown(false));
        fullTextView = new LinkSpanTextView(context);
        fullTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        fullTextView.setTextColor(Color.WHITE);
        fullTextView.setLinkTextColor(0xFF64B5F6); // ссылки синим
        fullTextView.setLineSpacing(dp(3), 1f);
        fullTextView.setGravity(Gravity.CENTER);
        fullTextView.setPadding(dp(18), dp(24), dp(18), dp(24));
        fullTextView.setOnClickListener(v -> setFullTextShown(false));
        centerWrapper.addView(fullTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        scrollView.addView(centerWrapper, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fullTextOverlay.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        addView(fullTextOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // столбик кнопок справа снизу
        buttonsColumn = new LinearLayout(context);
        buttonsColumn.setOrientation(LinearLayout.VERTICAL);
        buttonsColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(buttonsColumn, LayoutHelper.createFrame(56, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 6, 0));

        addActionButton(R.drawable.msg_block, v -> {
            if (delegate != null && post != null) {
                delegate.onDislike(post);
            }
        });
        // кнопка комментов + счётчик — единая кликабельная область (иначе счётчик
        // перекрывал низ кнопки и глотал тапы → «открывается не с первого раза»)
        LinearLayout commentsGroup = new LinearLayout(context);
        commentsGroup.setOrientation(LinearLayout.VERTICAL);
        commentsGroup.setGravity(Gravity.CENTER_HORIZONTAL);
        commentsGroup.setOnClickListener(v -> {
            if (delegate != null && post != null && commentsEnabled) {
                delegate.onOpenComments(post);
            }
        });
        commentsButton = new ActionButton(context);
        commentsButton.setScaleType(ImageView.ScaleType.CENTER);
        commentsButton.setImageResource(R.drawable.msg_discussion);
        commentsButton.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        commentsButton.setDuplicateParentStateEnabled(true);
        commentsGroup.addView(commentsButton, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL));
        commentsCountView = new TextView(context);
        commentsCountView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        commentsCountView.setTypeface(AndroidUtilities.bold());
        commentsCountView.setTextColor(Color.WHITE);
        commentsCountView.setGravity(Gravity.CENTER_HORIZONTAL);
        commentsCountView.setShadowLayer(dp(2), 0, dp(1), 0x66000000);
        commentsGroup.addView(commentsCountView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, -4, 0, 0));
        buttonsColumn.addView(commentsGroup, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 6));
        addActionButton(R.drawable.msg_forward, v -> {
            if (delegate != null && post != null) {
                delegate.onShareTelegram(post);
            }
        });
        addActionButton(R.drawable.msg_shareout, v -> {
            if (delegate != null && post != null) {
                delegate.onShareExternal(post);
            }
        });
        addActionButton(R.drawable.msg_expand, v -> {
            if (delegate != null && post != null) {
                delegate.onOpenFullPost(post);
            }
        });
    }

    private ActionButton addActionButton(int iconRes, OnClickListener listener) {
        ActionButton button = new ActionButton(getContext());
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(iconRes);
        button.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        button.setBackground(Theme.createSelectorDrawable(0x33FFFFFF, 1));
        button.setOnClickListener(listener);
        buttonsColumn.addView(button, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 6));
        return button;
    }

    /** Кнопка действия с тенью иконки; в состоянии crossed рисует диагональную черту. */
    private static class ActionButton extends ImageView {

        private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.ColorFilter shadowFilter = new PorterDuffColorFilter(0x80000000, PorterDuff.Mode.SRC_IN);
        private final android.graphics.ColorFilter iconFilter = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        private boolean crossed;

        public ActionButton(Context context) {
            super(context);
            crossPaint.setColor(Color.WHITE);
            crossPaint.setStrokeWidth(dp(2));
            crossPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        public void setCrossed(boolean value) {
            if (crossed != value) {
                crossed = value;
                setAlpha(crossed ? 0.6f : 1f);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // рисуем иконку сами: сначала тёмная копия со сдвигом (тень), потом белая
            Drawable d = getDrawable();
            if (d != null) {
                int iw = d.getIntrinsicWidth();
                int ih = d.getIntrinsicHeight();
                int l = (getWidth() - iw) / 2;
                int t = (getHeight() - ih) / 2;
                d.setBounds(l, t, l + iw, t + ih);
                d.setColorFilter(shadowFilter);
                canvas.save();
                canvas.translate(0, dp(1.5f));
                d.draw(canvas);
                canvas.restore();
                d.setColorFilter(iconFilter);
                d.draw(canvas);
            }
            if (crossed) {
                canvas.drawLine(dp(13), dp(13), getWidth() - dp(13), getHeight() - dp(13), crossPaint);
            }
        }
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setInsets(int top, int bottom) {
        topInset = top;
        bottomInset = bottom;
        ((LayoutParams) bottomOverlay.getLayoutParams()).bottomMargin = bottom;
        ((LayoutParams) buttonsColumn.getLayoutParams()).bottomMargin = bottom + dp(4);
        ((LayoutParams) videoSeekBar.getLayoutParams()).bottomMargin = bottom;
        fullTextView.setPadding(dp(18), topInset + dp(24), dp(18), bottom + dp(24));
        requestLayout();
    }

    private MediaItemView currentMediaItem() {
        if (carousel == null) {
            return null;
        }
        RecyclerView.ViewHolder holder = carousel.findViewHolderForAdapterPosition(carouselPosition);
        return holder != null && holder.itemView instanceof MediaItemView ? (MediaItemView) holder.itemView : null;
    }

    private boolean currentItemIsVideo() {
        return !mediaMessages.isEmpty()
            && carouselPosition < mediaMessages.size()
            && mediaMessages.get(carouselPosition).isVideo();
    }

    /* Видео через VideoPlayer (ExoPlayer): надёжный автостарт/пауза/резюм/seek со стримингом */

    private void updateActiveVideo() {
        MediaItemView item = active ? currentMediaItem() : null;
        boolean wantVideo = item != null && currentItemIsVideo();
        if (!wantVideo) {
            releaseVideo();
            updateSeekBarVisibility();
            return;
        }
        MessageObject messageObject = mediaMessages.get(carouselPosition);
        if (videoPlayer != null && videoPlayerMessage == messageObject && videoItem == item) {
            return; // уже играет нужное
        }
        releaseVideo();
        videoItem = item;
        videoPlayerMessage = messageObject;
        manuallyPaused = false;
        final MediaItemView boundItem = item;

        try {
            TLRPC.Document document = messageObject.getDocument();
            android.net.Uri uri = org.telegram.messenger.FileStreamLoadOperation.prepareUri(currentAccount, document, messageObject);
            if (uri == null) {
                return;
            }
            org.telegram.messenger.FileStreamLoadOperation.setPriorityForDocument(document, FileLoader.PRIORITY_HIGH);
            FileLoader.getInstance(currentAccount).changePriority(FileLoader.PRIORITY_HIGH, document, null, null, null, null, null);

            videoPlayer = new VideoPlayer(false, false);
            videoPlayer.setLooping(true);
            videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                    videoSeekBar.invalidate();
                }

                @Override
                public void onError(VideoPlayer player, Exception e) {}

                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                    if (videoItem == boundItem && width > 0 && height > 0) {
                        float aspect = width * pixelWidthHeightRatio / (float) height;
                        if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) {
                            aspect = 1f / aspect;
                        }
                        boundItem.setVideoAspect(aspect);
                    }
                }

                @Override
                public void onRenderedFirstFrame() {
                    // сверяемся с текущим item — колбэк мог прийти после смены поста
                    if (videoItem == boundItem) {
                        boundItem.onVideoRendered();
                    }
                }

                @Override
                public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surfaceTexture) {}

                @Override
                public boolean onSurfaceDestroyed(android.graphics.SurfaceTexture surfaceTexture) {
                    return false;
                }
            });
            videoPlayer.setTextureView(item.ensureTextureView());
            videoPlayer.preparePlayer(uri, "other", FileLoader.PRIORITY_HIGH, 0);
            videoPlayer.setMute(false);
            videoPlayer.play();
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            releaseVideo();
        }
        updateSeekBarVisibility();
    }

    private void releaseVideo() {
        if (videoPlayer != null) {
            try {
                videoPlayer.releasePlayer(true);
            } catch (Throwable ignore) {}
            videoPlayer = null;
        }
        if (videoItem != null) {
            videoItem.onVideoReleased();
            videoItem = null;
        }
        videoPlayerMessage = null;
        videoSeekBar.setVisibility(GONE);
        AndroidUtilities.cancelRunOnUIThread(seekTicker);
    }

    private void toggleVideoPlayback() {
        if (videoPlayer == null) {
            return;
        }
        if (videoPlayer.isPlaying()) {
            videoPlayer.pause();
            manuallyPaused = true;
        } else {
            videoPlayer.play();
            manuallyPaused = false;
        }
        if (videoItem != null) {
            videoItem.setShowPlay(manuallyPaused);
        }
    }

    private void updateSeekBarVisibility() {
        boolean showBar = active && currentItemIsVideo() && videoPlayer != null;
        videoSeekBar.setVisibility(showBar ? VISIBLE : GONE);
        AndroidUtilities.cancelRunOnUIThread(seekTicker);
        if (showBar) {
            AndroidUtilities.runOnUIThread(seekTicker, 200);
        }
    }

    public void setPost(FeedController.FeedPost newPost) {
        post = newPost;
        carouselPosition = 0;
        fullTextShown = false;
        fullTextOverlay.setVisibility(GONE);
        applyBlur(false);
        overlaysHiddenByTouch = false;
        AndroidUtilities.cancelRunOnUIThread(restoreOverlaysRunnable);
        // полный сброс состояния переиспользуемой view: канал и оверлеи должны быть
        // видны всегда, даже если на прошлом посте был открыт полный текст/шторка
        bottomOverlay.setVisibility(VISIBLE);
        bottomOverlay.setAlpha(1f);
        summaryTextView.setAlpha(1f);
        centerTextView.setAlpha(1f);
        buttonsColumn.setVisibility(VISIBLE);
        buttonsColumn.setAlpha(1f);
        dotsIndicator.setAlpha(1f);
        mediaContainer.setScaleX(1f);
        mediaContainer.setScaleY(1f);

        mediaMessages.clear();
        mediaMessages.addAll(post.renderableMedia());

        // комментарии доступны только у постов с привязанной discussion-группой;
        // у альбома флаг/счётчик лежат не на первом сообщении
        commentsEnabled = post.commentsEnabled();
        commentsButton.setCrossed(!commentsEnabled);
        int commentsCount = commentsEnabled ? post.commentsCount() : 0;
        commentsCountView.setVisibility(commentsCount > 0 ? VISIBLE : GONE);
        if (commentsCount > 0) {
            commentsCountView.setText(LocaleController.formatShortNumber(commentsCount, null));
        }

        avatarDrawable.setInfo(post.chat);
        avatarImageView.setForUserOrChat(post.chat, avatarDrawable);
        nameTextView.setText(post.chat.title);
        StringBuilder info = new StringBuilder();
        info.append(LocaleController.stringForMessageListDate(post.message.messageOwner.date));
        int views = post.getViews();
        if (views > 0) {
            info.append(" · ").append(LocaleController.formatString("SmartFeedViews", R.string.SmartFeedViews, LocaleController.formatShortNumber(views, null)));
        }
        infoTextView.setText(info);

        // entity-ссылки Telegram (text_url и т.п.) кликабельны, как в модалке поста;
        // голые URL без entities долинкует LinkSpanTextView
        CharSequence fullText = post.getText();
        MessageObject textSource = post.textMessage();
        if (textSource != null && textSource.messageOwner.entities != null && !textSource.messageOwner.entities.isEmpty()) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(fullText);
            MessageObject.addEntitiesToText(sb, textSource.messageOwner.entities, false, false, false, true);
            fullText = sb;
        }
        fullTextView.setText(fullText);

        if (mediaMessages.isEmpty()) {
            // текстовый пост: градиент вместо медиа
            GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF20364d, 0xFF101d2a, 0xFF000000});
            mediaContainer.setBackground(gradient);
        } else {
            mediaContainer.setBackground(null);
        }
        dotsIndicator.setCount(mediaMessages.size());
        dotsIndicator.setSelected(0);
        carouselAdapter.notifyDataSetChanged();
        carousel.scrollToPosition(0);
        updateActiveVideo();
    }

    public FeedController.FeedPost getPost() {
        return post;
    }

    /** Документ видео, которое сейчас реально стримит плеер (активный элемент карусели). */
    public TLRPC.Document getPlayingVideoDocument() {
        return videoPlayerMessage != null ? videoPlayerMessage.getDocument() : null;
    }

    public void setSummary(String summary, boolean modelAvailable) {
        if (post == null || TextUtils.isEmpty(post.getText())) {
            summaryTextView.setVisibility(GONE);
            centerTextView.setVisibility(GONE);
            return;
        }
        // пост без медиа: текст по центру экрана, с медиа — в нижней трети
        final boolean textOnly = mediaMessages.isEmpty();
        final TextView target = textOnly ? centerTextView : summaryTextView;
        summaryTextView.setVisibility(textOnly ? GONE : VISIBLE);
        centerTextView.setVisibility(textOnly ? VISIBLE : GONE);
        target.setMaxLines(textOnly ? 16 : 6);
        if (summary != null) {
            target.setTypeface(null);
            target.setAlpha(1f);
            target.setText(summary);
        } else {
            target.setTypeface(android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.ITALIC));
            target.setAlpha(0.7f);
            target.setText(LocaleController.getString(modelAvailable ? R.string.SmartFeedSummarizing : R.string.SmartFeedSummaryUnavailable));
        }
    }

    /** Активная страница — играет видео; неактивная — статичная. */
    public void setActive(boolean value) {
        if (active == value) {
            return;
        }
        active = value;
        updateActiveVideo();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AndroidUtilities.cancelRunOnUIThread(seekTicker);
        AndroidUtilities.cancelRunOnUIThread(restoreOverlaysRunnable);
        releaseVideo();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateActiveVideo();
    }

    /** Страховка: сброс возможного застрявшего сжатия (оверлеи/масштаб) при показе поста. */
    public void resetShrink() {
        mediaContainer.setScaleX(1f);
        mediaContainer.setScaleY(1f);
        bottomOverlay.setAlpha(1f);
        bottomOverlay.setVisibility(VISIBLE);
        buttonsColumn.setAlpha(1f);
        buttonsColumn.setVisibility(VISIBLE);
        dotsIndicator.setAlpha(1f);
        centerTextView.setAlpha(1f);
    }

    public boolean canCarouselScroll() {
        return mediaMessages.size() > 1;
    }

    private void setFullTextShown(boolean shown) {
        if (post == null || TextUtils.isEmpty(post.getText()) || fullTextShown == shown) {
            return;
        }
        fullTextShown = shown;
        fullTextOverlay.setVisibility(shown ? VISIBLE : GONE);
        bottomOverlay.setVisibility(shown ? INVISIBLE : VISIBLE);
        if (mediaMessages.isEmpty()) {
            centerTextView.setVisibility(shown ? INVISIBLE : VISIBLE);
        }
        applyBlur(shown);
    }

    private void applyBlur(boolean blur) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mediaContainer.setRenderEffect(blur ? RenderEffect.createBlurEffect(dp(12), dp(12), Shader.TileMode.MIRROR) : null);
        }
    }

    /** Долгое нажатие по экрану — спрятать всё (текст, кнопки, шапку канала, точки). */
    private void setOverlaysHidden(boolean hidden) {
        if (overlaysHiddenByTouch == hidden) {
            return;
        }
        overlaysHiddenByTouch = hidden;
        float alpha = hidden ? 0f : 1f;
        // bottomOverlay = шапка канала + текст + точки; centerTextView и кнопки — отдельно
        bottomOverlay.animate().alpha(alpha).setDuration(180).start();
        centerTextView.animate().alpha(alpha).setDuration(180).start();
        buttonsColumn.animate().alpha(alpha).setDuration(180).start();
    }

    /** Сжатие медиа кверху, когда открыта шторка комментариев (0 — фулскрин, 1 — сжато). */
    public void setCommentsShrink(float progress, int panelHeight) {
        int fullHeight = getHeight() > 0 ? getHeight() : AndroidUtilities.displaySize.y;
        float targetScale = Math.max(0.2f, (fullHeight - panelHeight - topInset) / (float) fullHeight);
        float scale = 1f - (1f - targetScale) * progress;
        mediaContainer.setPivotX(getWidth() / 2f);
        mediaContainer.setPivotY(topInset);
        mediaContainer.setScaleX(scale);
        mediaContainer.setScaleY(scale);
        float otherAlpha = 1f - progress;
        bottomOverlay.setAlpha(otherAlpha);
        buttonsColumn.setAlpha(otherAlpha);
        dotsIndicator.setAlpha(otherAlpha);
        centerTextView.setAlpha(otherAlpha);
        bottomOverlay.setVisibility(otherAlpha == 0 ? INVISIBLE : VISIBLE);
        buttonsColumn.setVisibility(otherAlpha == 0 ? INVISIBLE : VISIBLE);
    }

    /* Карусель медиа */

    private class CarouselAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemCount() {
            return mediaMessages.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            MediaItemView view = new MediaItemView(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((MediaItemView) holder.itemView).bind(mediaMessages.get(position));
            // активный видео-холдер мог забиндиться уже после первого updateActiveVideo
            // (карусель не была разложена) — до-запускаем, когда он готов
            if (active && position == carouselPosition && mediaMessages.get(position).isVideo()) {
                AndroidUtilities.runOnUIThread(FeedPageView.this::updateActiveVideo);
            }
        }
    }

    private class MediaItemView extends FrameLayout {

        private final BackupImageView imageView; // постер (кадр-превью)
        private android.view.TextureView textureView;
        private Drawable playDrawable;
        private RadialProgressView loadingView; // спиннер, пока видео не отдало первый кадр
        private boolean showPlay;
        private boolean isVideo;
        private float videoAspect;

        public MediaItemView(Context context) {
            super(context);
            imageView = new BackupImageView(context);
            imageView.getImageReceiver().setAspectFit(true);
            addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            setOnClickListener(v -> {
                if (fullTextShown) {
                    setFullTextShown(false);
                    return;
                }
                // тап по видео — пауза/воспроизведение (полный текст только по тапу на текст)
                if (isVideo && videoItem == this) {
                    toggleVideoPlayback();
                }
            });
            // долгое нажатие по экрану прячет оверлеи (текст, кнопки, шапку); отпустил — вернул
            setOnLongClickListener(v -> {
                setOverlaysHidden(true);
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                return true;
            });
            setWillNotDraw(false);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int a = event.getActionMasked();
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && overlaysHiddenByTouch) {
                setOverlaysHidden(false);
            }
            return super.onTouchEvent(event);
        }

        android.view.TextureView ensureTextureView() {
            if (textureView == null) {
                textureView = new android.view.TextureView(getContext());
                addView(textureView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
            }
            textureView.setVisibility(VISIBLE);
            textureView.setAlpha(0f); // покажем по первому кадру, чтобы не мигало чёрным
            showPlay = false;
            setLoading(true); // буферизация: крутилка поверх постера, чтобы видео не путали с фото
            requestLayout();
            invalidate();
            return textureView;
        }

        /** Крутилка-предзагрузка поверх постера, пока плеер не отрисовал первый кадр. */
        void setLoading(boolean value) {
            if (value) {
                if (loadingView == null) {
                    loadingView = new RadialProgressView(getContext());
                    loadingView.setSize(dp(48));
                    loadingView.setStrokeWidth(2.5f);
                    loadingView.setProgressColor(Color.WHITE);
                    addView(loadingView, LayoutHelper.createFrame(56, 56, Gravity.CENTER));
                }
                loadingView.setVisibility(VISIBLE);
            } else if (loadingView != null) {
                loadingView.setVisibility(GONE);
            }
        }

        /** Вписываем видео по его соотношению сторон (letterbox), как фото-постер. */
        void setVideoAspect(float aspect) {
            if (aspect > 0 && Math.abs(videoAspect - aspect) > 0.001f) {
                videoAspect = aspect;
                requestLayout();
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (textureView != null && videoAspect > 0) {
                int vw = getWidth();
                int vh = getHeight();
                if (vw > 0 && vh > 0) {
                    int w = vw, h = (int) (vw / videoAspect);
                    if (h > vh) {
                        h = vh;
                        w = (int) (vh * videoAspect);
                    }
                    int cx = (vw - w) / 2, cy = (vh - h) / 2;
                    textureView.measure(
                        MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
                    textureView.layout(cx, cy, cx + w, cy + h);
                }
            }
        }

        void onVideoRendered() {
            if (textureView != null) {
                textureView.setVisibility(VISIBLE);
                textureView.animate().alpha(1f).setDuration(150).start();
            }
            setLoading(false); // первый кадр пришёл — крутилку прячем
            showPlay = false;
            invalidate();
        }

        void onVideoReleased() {
            if (textureView != null) {
                textureView.setAlpha(0f);
                textureView.setVisibility(GONE);
            }
            setLoading(false);
            showPlay = isVideo;
            invalidate();
        }

        void setShowPlay(boolean value) {
            showPlay = value;
            invalidate();
        }

        public void bind(MessageObject messageObject) {
            // активный играющий item не трогаем — иначе спрячем живое видео под постер
            if (videoItem == this) {
                return;
            }
            isVideo = messageObject.isVideo();
            showPlay = isVideo; // постер видео показывает play, пока плеер не отрисует кадр
            setLoading(false); // переиспользуемый холдер мог остаться с крутилкой от прошлого видео
            if (isVideo && playDrawable == null) {
                playDrawable = ContextCompat.getDrawable(getContext(), R.drawable.play_mini_video).mutate();
            }
            if (textureView != null) {
                textureView.setAlpha(0f);
                textureView.setVisibility(GONE);
            }
            // постер: полноразмерный кадр (для фото — само фото)
            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, AndroidUtilities.getPhotoSize());
            TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 50);
            if (thumbSize == photoSize) {
                thumbSize = null;
            }
            imageView.getImageReceiver().setImage(
                ImageLocation.getForObject(photoSize, messageObject.photoThumbsObject), null,
                ImageLocation.getForObject(thumbSize, messageObject.photoThumbsObject), "50_50_b",
                photoSize != null ? photoSize.size : 0, null, messageObject, 1);
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            // play-иконка поверх постера/видео, крупная (×5)
            if (showPlay && playDrawable != null) {
                final int w = playDrawable.getIntrinsicWidth() * 5;
                final int h = playDrawable.getIntrinsicHeight() * 5;
                final int cx = getWidth() / 2;
                final int cy = getHeight() / 2;
                playDrawable.setAlpha(220);
                playDrawable.setBounds(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
                playDrawable.draw(canvas);
            }
        }
    }

    /* Точки-индикатор карусели */

    private static class DotsIndicator extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int count;
        private int selected;

        public DotsIndicator(Context context) {
            super(context);
            shadowPaint.setColor(0x66000000);
        }

        public void setCount(int value) {
            count = value;
            setVisibility(count > 1 ? VISIBLE : GONE);
            requestLayout();
            invalidate();
        }

        public void setSelected(int value) {
            selected = value;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(count * dp(14), MeasureSpec.getSize(heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cy = getHeight() / 2f;
            for (int i = 0; i < count; i++) {
                float cx = dp(7) + i * dp(14);
                float r = dp(i == selected ? 3.5f : 3f);
                canvas.drawCircle(cx, cy + dp(1), r + dp(0.6f), shadowPaint); // тень
                paint.setColor(i == selected ? Color.WHITE : 0x80FFFFFF);
                canvas.drawCircle(cx, cy, r, paint);
            }
        }
    }
}
