package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.feed.FeedCommentsLoader;
import org.telegram.messenger.feed.FeedController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

/**
 * Шторка комментариев в стиле TikTok: выезжает на ~2/3 экрана, медиа поста
 * сжимается кверху (сжатие делает FeedFragment по onProgress), заголовок
 * «N комментариев», список с пагинацией, поле ввода прибито снизу.
 */
public class FeedCommentsPanel extends FrameLayout implements FeedCommentsLoader.Delegate {

    public interface Delegate {
        void onShrinkProgress(float progress, int panelHeight);
        void onDismissed();
        void onCommentsOpened(FeedController.FeedPost post);
    }

    private final int currentAccount;
    private final Delegate delegate;

    private FeedCommentsLoader loader;
    private FeedController.FeedPost post;

    private final TextView titleView;
    private final RecyclerListView listView;
    private final ListAdapter adapter = new ListAdapter();
    private final EditTextBoldCursor editText;
    private ValueAnimator animator;
    private boolean shown;

    public FeedCommentsPanel(Context context, int currentAccount, Delegate delegate) {
        super(context);
        this.currentAccount = currentAccount;
        this.delegate = delegate;
        setVisibility(GONE);
        setBackground(Theme.createRoundRectDrawable(dp(16), dp(16), 0, 0, Theme.getColor(Theme.key_dialogBackground)));
        setClickable(true);

        FrameLayout header = new FrameLayout(context);
        header.setOnTouchListener(this::onHeaderTouch);
        addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP));

        // grabber-полоска сверху (потянуть вниз — закрыть)
        View grabber = new View(context);
        grabber.setBackground(Theme.createRoundRectDrawable(dp(2), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText) & 0x66FFFFFF));
        header.addView(grabber, LayoutHelper.createFrame(36, 4, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 6, 0, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        header.addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 44, 0, 44, 0));

        ImageView closeButton = new ImageView(context);
        closeButton.setScaleType(ImageView.ScaleType.CENTER);
        closeButton.setImageResource(R.drawable.ic_close_white);
        closeButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN));
        closeButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        closeButton.setOnClickListener(v -> hide());
        header.addView(closeButton, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.TOP));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(4), 0, dp(8));
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || loader == null || loader.isLoading() || loader.isEndReached()) {
                    return;
                }
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 5) {
                    loader.loadMore();
                }
            }
        });
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 44, 0, 50));

        FrameLayout inputBar = new FrameLayout(context);
        inputBar.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground));
        addView(inputBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.BOTTOM));

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        inputBar.addView(divider, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.TOP));

        // явная плашка поля ввода, чтобы его было видно
        FrameLayout inputPill = new FrameLayout(context);
        inputPill.setBackground(Theme.createRoundRectDrawable(dp(20), Theme.getColor(Theme.key_windowBackgroundGray)));
        inputPill.setPadding(dp(4), 0, dp(4), 0);
        inputBar.addView(inputPill, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.CENTER_VERTICAL, 12, 0, 12, 0));

        editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setHintText(getString(R.string.SmartFeedCommentHint));
        editText.setBackground(null);
        editText.setSingleLine(true);
        editText.setPadding(dp(12), 0, dp(8), 0);
        editText.setGravity(Gravity.CENTER_VERTICAL);
        inputPill.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 0, 0, 44, 0));

        ImageView sendButton = new ImageView(context);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setImageResource(R.drawable.msg_send);
        sendButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton), PorterDuff.Mode.SRC_IN));
        sendButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        sendButton.setOnClickListener(v -> {
            if (loader != null && loader.send(editText.getText().toString())) {
                editText.setText("");
                AndroidUtilities.hideKeyboard(editText);
                listView.smoothScrollToPosition(0); // свой коммент теперь сверху
            }
        });
        inputPill.addView(sendButton, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
    }

    /** Отступ под плавающую нижнюю навигацию, чтобы поле ввода не заезжало под таб-бар. */
    public void setBottomInset(int inset) {
        if (getPaddingBottom() != inset) {
            setPadding(0, 0, 0, inset);
            requestLayout();
        }
    }

    /* Закрытие потягиванием вниз (заголовок или верх списка) и свайпом вправо */

    private float dragStartX, dragStartY;
    private boolean dragging;
    private boolean horizontalDrag;

    private boolean onHeaderTouch(View v, MotionEvent e) {
        return handleDrag(e, true);
    }

    private boolean listAtTop() {
        return !listView.canScrollVertically(-1);
    }

    /** Драг для закрытия. fromHeader=true — с заголовка (всегда), иначе — со списка (только когда он вверху). */
    private boolean handleDrag(MotionEvent e, boolean fromHeader) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartX = e.getRawX();
                dragStartY = e.getRawY();
                dragging = false;
                horizontalDrag = false;
                return fromHeader;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - dragStartX;
                float dy = e.getRawY() - dragStartY;
                if (!dragging) {
                    if (Math.abs(dx) > dp(12) && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        horizontalDrag = true;
                    } else if (dy > dp(12) && dy > Math.abs(dx) && (fromHeader || listAtTop())) {
                        dragging = true;
                        horizontalDrag = false;
                    }
                }
                if (dragging) {
                    int height = getHeight() > 0 ? getHeight() : dp(400);
                    if (horizontalDrag) {
                        setTranslationX(Math.max(0, dx));
                        if (delegate != null) {
                            delegate.onShrinkProgress(Math.max(0f, 1f - Math.max(0, dx) / getWidth()), height);
                        }
                    } else if (dy > 0) {
                        setTranslationY(dy);
                        if (delegate != null) {
                            delegate.onShrinkProgress(Math.max(0f, 1f - dy / height), height);
                        }
                    }
                    return true;
                }
                return fromHeader;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragging) {
                    float dx = e.getRawX() - dragStartX;
                    float dy = e.getRawY() - dragStartY;
                    boolean dismiss = horizontalDrag ? dx > getWidth() * 0.3f : dy > getHeight() * 0.22f;
                    if (dismiss) {
                        hide();
                    } else {
                        setTranslationX(0);
                        animateTo(1f, null);
                    }
                }
                boolean wasDragging = dragging;
                dragging = false;
                return fromHeader || wasDragging;
            }
        }
        return false;
    }

    // перехват вертикального драга со списка, когда он в самом верху
    private boolean interceptListDrag;

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dragStartX = e.getRawX();
            dragStartY = e.getRawY();
            interceptListDrag = false;
            return false;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = e.getRawX() - dragStartX;
            float dy = e.getRawY() - dragStartY;
            if ((dy > dp(14) && dy > Math.abs(dx) && listAtTop()) || (Math.abs(dx) > dp(14) && Math.abs(dx) > Math.abs(dy))) {
                interceptListDrag = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (interceptListDrag) {
            return handleDrag(e, false);
        }
        return super.onTouchEvent(e);
    }

    public boolean isShown() {
        return shown;
    }

    /** Жёсткая остановка (при уничтожении фрагмента): гасит живой опрос комментов. */
    public void onDestroy() {
        shown = false;
        if (loader != null) {
            loader.stopLive();
            loader = null;
        }
        if (animator != null) {
            animator.cancel();
        }
    }

    public void show(FeedController.FeedPost newPost) {
        if (shown) {
            return;
        }
        shown = true;
        post = newPost;
        loader = new FeedCommentsLoader(currentAccount, post, this);
        loader.start();
        updateTitle();
        adapter.notifyDataSetChanged();
        setVisibility(VISIBLE);
        if (delegate != null) {
            delegate.onCommentsOpened(post);
        }
        animateTo(1f, null);
    }

    public void hide() {
        if (!shown) {
            return;
        }
        shown = false;
        if (loader != null) {
            loader.stopLive();
        }
        AndroidUtilities.hideKeyboard(editText);
        animateTo(0f, () -> {
            // если за это время успели снова show() — не гасим свежую панель
            if (shown) {
                return;
            }
            setVisibility(GONE);
            loader = null;
            if (delegate != null) {
                delegate.onDismissed();
            }
        });
    }

    private void animateTo(float target, Runnable onEnd) {
        if (animator != null) {
            animator.cancel();
        }
        setTranslationX(0);
        final int height = getLayoutParams() != null && getLayoutParams().height > 0 ? getLayoutParams().height : dp(400);
        float startProgress = getVisibility() == VISIBLE && height > 0 ? 1f - getTranslationY() / height : 0f;
        if (target == 1f && getTranslationY() == 0 && getVisibility() == VISIBLE && startProgress >= 1f) {
            startProgress = 0f;
        }
        setTranslationY((1f - startProgress) * height);
        animator = ValueAnimator.ofFloat(startProgress, target);
        animator.setDuration(240);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(a -> {
            float progress = (float) a.getAnimatedValue();
            setTranslationY((1f - progress) * height);
            if (delegate != null) {
                delegate.onShrinkProgress(progress, height);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onEnd != null) {
                    onEnd.run();
                }
            }
        });
        animator.start();
    }

    private void updateTitle() {
        int count = 0;
        if (post != null && post.message.messageOwner.replies != null) {
            count = post.message.messageOwner.replies.replies;
        }
        if (loader != null) {
            count = Math.max(count, loader.getComments().size());
        }
        if (count > 0) {
            titleView.setText(LocaleController.formatPluralString("Comments", count));
        } else {
            titleView.setText(getString(R.string.SmartFeedComments));
        }
    }

    @Override
    public void onUpdated() {
        updateTitle();
        adapter.notifyDataSetChanged();
    }

    /* Список */

    private static final int ROW_COMMENT = 0;
    private static final int ROW_LOADING = 1;
    private static final int ROW_STATUS = 2;

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            if (loader == null) {
                return 0;
            }
            int count = loader.getComments().size();
            if (loader.isUnavailable() || (loader.isEndReached() && count == 0)) {
                count++;
            } else if (count == 0 && loader.isLoading()) {
                count++;
            }
            return count;
        }

        @Override
        public int getItemViewType(int position) {
            if (loader == null || position >= loader.getComments().size()) {
                if (loader != null && (loader.isUnavailable() || loader.isEndReached())) {
                    return ROW_STATUS;
                }
                return ROW_LOADING;
            }
            return ROW_COMMENT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == ROW_COMMENT) {
                view = new CommentCell(parent.getContext());
            } else if (viewType == ROW_LOADING) {
                FrameLayout frameLayout = new FrameLayout(parent.getContext());
                RadialProgressView progressView = new RadialProgressView(parent.getContext());
                progressView.setSize(dp(22));
                frameLayout.addView(progressView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 14, 0, 14));
                view = frameLayout;
            } else {
                TextView textView = new TextView(parent.getContext());
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                textView.setGravity(Gravity.CENTER);
                textView.setPadding(dp(16), dp(16), dp(16), dp(16));
                view = textView;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int viewType = holder.getItemViewType();
            if (viewType == ROW_COMMENT && loader != null && position < loader.getComments().size()) {
                ((CommentCell) holder.itemView).bind(loader.getComments().get(position));
            } else if (viewType == ROW_STATUS) {
                ((TextView) holder.itemView).setText(getString(loader != null && loader.isUnavailable()
                    ? R.string.SmartFeedCommentsUnavailable : R.string.SmartFeedNoComments));
            }
        }
    }

    private class CommentCell extends FrameLayout {

        private final BackupImageView avatarImageView;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView nameTextView;
        private final TextView timeTextView;
        private final TextView textView;

        public CommentCell(Context context) {
            super(context);
            setPadding(dp(16), dp(6), dp(16), dp(6));

            avatarImageView = new BackupImageView(context);
            avatarImageView.setRoundRadius(dp(16));
            addView(avatarImageView, LayoutHelper.createFrame(32, 32, Gravity.LEFT | Gravity.TOP));

            nameTextView = new TextView(context);
            nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            nameTextView.setTypeface(AndroidUtilities.bold());
            nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            nameTextView.setSingleLine(true);
            nameTextView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 42, 0, 60, 0));

            timeTextView = new TextView(context);
            timeTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            timeTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            timeTextView.setSingleLine(true);
            addView(timeTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.TOP, 0, 1, 0, 0));

            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setLineSpacing(dp(1), 1f);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 42, 19, 0, 0));
        }

        public void bind(TLRPC.Message message) {
            long fromId = MessageObject.getFromChatId(message);
            String name = "";
            if (fromId > 0) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(fromId);
                name = UserObject.getUserName(user);
                avatarDrawable.setInfo(user);
                avatarImageView.setForUserOrChat(user, avatarDrawable);
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-fromId);
                if (chat != null) {
                    name = chat.title;
                }
                avatarDrawable.setInfo(chat);
                avatarImageView.setForUserOrChat(chat, avatarDrawable);
            }
            nameTextView.setText(name);
            timeTextView.setText(LocaleController.stringForMessageListDate(message.date));
            if (!TextUtils.isEmpty(message.message)) {
                textView.setTypeface(null);
                textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                textView.setText(message.message);
            } else {
                textView.setTypeface(android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.ITALIC));
                textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                textView.setText(getString(message.media instanceof TLRPC.TL_messageMediaPhoto ? R.string.AttachPhoto : R.string.AttachDocument));
            }
        }
    }
}
