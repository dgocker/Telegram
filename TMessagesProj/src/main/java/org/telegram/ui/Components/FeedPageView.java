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
    private final BackupImageView avatarImageView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TextView nameTextView;
    private final TextView infoTextView;
    private final TextView summaryTextView;
    private final TextView centerTextView;
    private final LinearLayout buttonsColumn;
    private final FrameLayout fullTextOverlay;
    private final TextView fullTextView;

    private int bottomInset;
    private int topInset;
    private boolean fullTextShown;
    private boolean overlaysHiddenByTouch;
    private final Runnable restoreOverlaysRunnable = () -> setOverlaysHidden(false);

    public FeedPageView(Context context, int currentAccount) {
        super(context);
        this.currentAccount = currentAccount;
        setBackgroundColor(Color.BLACK);

        mediaContainer = new FrameLayout(context);
        addView(mediaContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        carousel = new RecyclerView(context);
        carousel.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        carousel.setAdapter(carouselAdapter);
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
                        carouselAdapter.notifyItemRangeChanged(0, carouselAdapter.getItemCount());
                    }
                }
            }
        });
        // пока юзер листает галерею — текст не мешает; отпустил — вернулся
        carousel.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                if (mediaMessages.size() > 1) {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        AndroidUtilities.cancelRunOnUIThread(restoreOverlaysRunnable);
                        setOverlaysHidden(true);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        AndroidUtilities.runOnUIThread(restoreOverlaysRunnable, 1000);
                    }
                }
                return false;
            }

            @Override
            public void onTouchEvent(RecyclerView rv, MotionEvent e) {
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallow) {
            }
        });
        mediaContainer.addView(carousel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        dotsIndicator = new DotsIndicator(context);
        addView(dotsIndicator, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 20, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // нижняя треть: канал + выжимка
        bottomOverlay = new LinearLayout(context);
        bottomOverlay.setOrientation(LinearLayout.VERTICAL);
        bottomOverlay.setPadding(dp(14), dp(30), dp(72), dp(10));
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xCC000000, 0x66000000, 0x00000000});
        bottomOverlay.setBackground(shade);
        addView(bottomOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        FrameLayout channelRow = new FrameLayout(context);
        bottomOverlay.addView(channelRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 0, 0, 8));

        avatarImageView = new BackupImageView(context);
        avatarImageView.setRoundRadius(dp(18));
        channelRow.addView(avatarImageView, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        nameTextView = new TextView(context);
        nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameTextView.setTypeface(AndroidUtilities.bold());
        nameTextView.setTextColor(Color.WHITE);
        nameTextView.setSingleLine(true);
        nameTextView.setEllipsize(TextUtils.TruncateAt.END);
        nameTextView.setShadowLayer(dp(2), 0, dp(1), 0x66000000);
        channelRow.addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 46, 0, 0, 0));

        infoTextView = new TextView(context);
        infoTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        infoTextView.setTextColor(0xCCFFFFFF);
        infoTextView.setSingleLine(true);
        infoTextView.setShadowLayer(dp(2), 0, dp(1), 0x66000000);
        channelRow.addView(infoTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 46, 19, 0, 0));

        summaryTextView = new TextView(context);
        summaryTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        summaryTextView.setTextColor(Color.WHITE);
        summaryTextView.setLineSpacing(dp(2), 1f);
        summaryTextView.setEllipsize(TextUtils.TruncateAt.END);
        summaryTextView.setShadowLayer(dp(2), 0, dp(1), 0x66000000);
        summaryTextView.setOnClickListener(v -> setFullTextShown(true));
        bottomOverlay.addView(summaryTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

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

        // полный текст поверх с блюром
        fullTextOverlay = new FrameLayout(context);
        fullTextOverlay.setBackgroundColor(0xB3000000);
        fullTextOverlay.setVisibility(GONE);
        fullTextOverlay.setOnClickListener(v -> setFullTextShown(false));
        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        fullTextView = new TextView(context);
        fullTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        fullTextView.setTextColor(Color.WHITE);
        fullTextView.setLineSpacing(dp(3), 1f);
        fullTextView.setPadding(dp(18), dp(24), dp(18), dp(24));
        fullTextView.setOnClickListener(v -> setFullTextShown(false));
        scrollView.addView(fullTextView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        addActionButton(R.drawable.msg_discussion, v -> {
            if (delegate != null && post != null) {
                delegate.onOpenComments(post);
            }
        });
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

    private void addActionButton(int iconRes, OnClickListener listener) {
        ImageView button = new ImageView(getContext());
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(iconRes);
        button.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        button.setBackground(Theme.createSelectorDrawable(0x33FFFFFF, 1));
        button.setOnClickListener(listener);
        buttonsColumn.addView(button, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 6));
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setInsets(int top, int bottom) {
        topInset = top;
        bottomInset = bottom;
        ((LayoutParams) dotsIndicator.getLayoutParams()).topMargin = top + dp(8);
        ((LayoutParams) bottomOverlay.getLayoutParams()).bottomMargin = bottom;
        ((LayoutParams) buttonsColumn.getLayoutParams()).bottomMargin = bottom + dp(4);
        fullTextView.setPadding(dp(18), topInset + dp(24), dp(18), bottom + dp(24));
        requestLayout();
    }

    public void setPost(FeedController.FeedPost newPost) {
        post = newPost;
        carouselPosition = 0;
        fullTextShown = false;
        fullTextOverlay.setVisibility(GONE);
        applyBlur(false);
        setOverlaysHidden(false);

        mediaMessages.clear();
        if (post.album != null) {
            mediaMessages.addAll(post.album);
        } else if (post.message.photoThumbs != null && !post.message.photoThumbs.isEmpty()) {
            mediaMessages.add(post.message);
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

        fullTextView.setText(post.getText());

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
    }

    public FeedController.FeedPost getPost() {
        return post;
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
        carouselAdapter.notifyItemRangeChanged(0, carouselAdapter.getItemCount());
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

    private void setOverlaysHidden(boolean hidden) {
        if (overlaysHiddenByTouch == hidden) {
            return;
        }
        overlaysHiddenByTouch = hidden;
        float alpha = hidden ? 0f : 1f;
        bottomOverlay.animate().alpha(alpha).setDuration(180).start();
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
            boolean playing = active && position == carouselPosition;
            ((MediaItemView) holder.itemView).bind(mediaMessages.get(position), playing);
        }
    }

    private class MediaItemView extends FrameLayout {

        private final BackupImageView imageView;
        private Drawable playDrawable;
        private boolean showPlay;

        public MediaItemView(Context context) {
            super(context);
            imageView = new BackupImageView(context);
            imageView.getImageReceiver().setAspectFit(true);
            addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            setOnClickListener(v -> {
                if (fullTextShown) {
                    setFullTextShown(false);
                } else if (overlaysHiddenByTouch) {
                    AndroidUtilities.cancelRunOnUIThread(restoreOverlaysRunnable);
                    setOverlaysHidden(false);
                } else if (!TextUtils.isEmpty(post != null ? post.getText() : null)) {
                    setFullTextShown(true);
                }
            });
            setWillNotDraw(false);
        }

        public void bind(MessageObject messageObject, boolean playing) {
            TLRPC.Document document = messageObject.getDocument();
            boolean isVideo = messageObject.isVideo();
            showPlay = isVideo && !playing;
            if (showPlay && playDrawable == null) {
                playDrawable = ContextCompat.getDrawable(getContext(), R.drawable.play_mini_video).mutate();
            }
            // полный размер: фото — максимально доступный размер, видео — автоплей потоком
            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, AndroidUtilities.getPhotoSize());
            TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 50);
            if (thumbSize == photoSize) {
                thumbSize = null;
            }
            if (isVideo && playing && document != null) {
                imageView.getImageReceiver().setAllowStartAnimation(true);
                imageView.getImageReceiver().setImage(
                    ImageLocation.getForDocument(document), ImageLoader.AUTOPLAY_FILTER,
                    ImageLocation.getForObject(photoSize, messageObject.photoThumbsObject), null,
                    ImageLocation.getForObject(thumbSize, messageObject.photoThumbsObject), "50_50_b",
                    null, document.size, null, messageObject, 0);
            } else {
                imageView.getImageReceiver().setImage(
                    ImageLocation.getForObject(photoSize, messageObject.photoThumbsObject), null,
                    ImageLocation.getForObject(thumbSize, messageObject.photoThumbsObject), "50_50_b",
                    photoSize != null ? photoSize.size : 0, null, messageObject, 1);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (showPlay && playDrawable != null) {
                final int w = playDrawable.getIntrinsicWidth();
                final int h = playDrawable.getIntrinsicHeight();
                final int cx = getWidth() / 2;
                final int cy = getHeight() / 2;
                playDrawable.setBounds(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
                playDrawable.draw(canvas);
            }
        }
    }

    /* Точки-индикатор карусели */

    private static class DotsIndicator extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int count;
        private int selected;

        public DotsIndicator(Context context) {
            super(context);
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
                paint.setColor(i == selected ? Color.WHITE : 0x80FFFFFF);
                canvas.drawCircle(dp(7) + i * dp(14), cy, dp(i == selected ? 3.5f : 3f), paint);
            }
        }
    }
}
