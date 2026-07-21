package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.feed.FeedController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.FeedCommentsPanel;
import org.telegram.ui.Components.FeedPageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;

import java.util.ArrayList;

/**
 * Умная лента в стиле TikTok: вертикальный полноэкранный пейджер постов.
 */
public class FeedFragment extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate, FeedPageView.Delegate, FeedCommentsPanel.Delegate {

    private static final int PRELOAD_AHEAD = 3;

    private boolean hasMainTabs;
    private int additionNavigationBarHeight;
    private int navigationBarHeight;
    private int statusBarHeight;

    private FrameLayout contentView;
    private RecyclerListView pager;
    private LinearLayoutManager layoutManager;
    private PagerAdapter adapter;
    private EmptyTextProgressView emptyView;
    private FeedCommentsPanel commentsPanel;
    private View commentsScrim;
    private android.widget.ImageView historyButton;
    private FeedPageView shrinkTarget;


    private int currentPage = -1;
    private long pageShownTime;
    private final ArrayList<ImageReceiver> prefetchReceivers = new ArrayList<>();

    public FeedFragment(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        }
        additionNavigationBarHeight = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
        getNotificationCenter().addObserver(this, NotificationCenter.smartFeedDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        FeedController.getInstance(currentAccount).loadFeed(false);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.smartFeedDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        clearPrefetch();
        if (commentsPanel != null) {
            commentsPanel.onDestroy(); // остановить живой опрос комментов
        }
        super.onFragmentDestroy();
    }

    @Override
    public ActionBar createActionBar(Context context) {
        ActionBar actionBar = super.createActionBar(context);
        actionBar.setAddToContainer(false);
        return actionBar;
    }

    @Override
    public boolean hasOwnBackground() {
        return true;
    }

    @Override
    public View createView(Context context) {
        contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Color.BLACK);
        fragmentView = contentView;

        emptyView = new EmptyTextProgressView(context);
        emptyView.setText(getString(R.string.SmartFeedNoPosts));
        emptyView.setTextColor(0xCCFFFFFF);
        emptyView.showProgress();
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        pager = new RecyclerListView(context) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent e) {
                if (commentsPanel != null && commentsPanel.isShown()) {
                    return false;
                }
                return super.onInterceptTouchEvent(e);
            }
        };
        pager.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        pager.setAdapter(adapter = new PagerAdapter());
        pager.setItemAnimator(null);
        new PagerSnapHelper().attachToRecyclerView(pager);
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    int position = layoutManager.findFirstCompletelyVisibleItemPosition();
                    if (position >= 0) {
                        onPageSelected(position);
                    }
                }
            }
        });
        pager.setEmptyView(emptyView);
        contentView.addView(pager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // скрим над шторкой комментов: тап или свайп вправо закрывают
        commentsScrim = new View(context);
        commentsScrim.setVisibility(View.GONE);
        final android.view.GestureDetector scrimGestures = new android.view.GestureDetector(context, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true; // иначе View не получит UP и onSingleTapUp не сработает
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (commentsPanel != null) {
                    commentsPanel.hide();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 != null && (Math.abs(e2.getX() - e1.getX()) > dp(60) || Math.abs(vx) > 800) && commentsPanel != null) {
                    commentsPanel.hide();
                    return true;
                }
                return false;
            }
        });
        commentsScrim.setOnTouchListener((v, e) -> scrimGestures.onTouchEvent(e));
        contentView.addView(commentsScrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        commentsPanel = new FeedCommentsPanel(context, currentAccount, this);
        contentView.addView(commentsPanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 400, Gravity.BOTTOM));

        // кнопка «Просмотренное» сверху справа (центр активности)
        historyButton = new android.widget.ImageView(context);
        historyButton.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        historyButton.setImageResource(R.drawable.msg_recent);
        historyButton.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN));
        historyButton.setBackground(org.telegram.ui.ActionBar.Theme.createSelectorDrawable(0x33FFFFFF, 1));
        historyButton.setOnClickListener(v -> presentFragment(new FeedHistoryActivity()));
        contentView.addView(historyButton, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.RIGHT, 0, 8, 8, 0));

        if (hasMainTabs) {
            ViewCompat.setOnApplyWindowInsetsListener(fragmentView, this::onInsetsInternal);
        }
        return fragmentView;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        navigationBarHeight = bottom;
        statusBarHeight = top;
        applyInsets();
    }

    private void applyInsets() {
        if (historyButton != null) {
            ((FrameLayout.LayoutParams) historyButton.getLayoutParams()).topMargin = statusBarHeight + dp(8);
        }
        if (commentsPanel != null) {
            int bottomInset = getPageBottomInset();
            int panelHeight = (int) ((AndroidUtilities.displaySize.y) * 0.66f) + bottomInset;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) commentsPanel.getLayoutParams();
            if (lp.height != panelHeight) {
                lp.height = panelHeight;
                commentsPanel.requestLayout();
            }
            commentsPanel.setBottomInset(bottomInset);
        }
        if (pager != null) {
            for (int i = 0; i < pager.getChildCount(); i++) {
                View child = pager.getChildAt(i);
                if (child instanceof FeedPageView) {
                    ((FeedPageView) child).setInsets(statusBarHeight, getPageBottomInset());
                }
            }
        }
    }

    private int getPageBottomInset() {
        return navigationBarHeight + additionNavigationBarHeight;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        // при возврате в приложение ленту НЕ перезагружаем (иначе теряются порядок,
        // позиция и тексты) — грузим только если её ещё нет
        if (getPosts().isEmpty()) {
            FeedController.getInstance(currentAccount).loadFeed(false);
        }
        updateEmptyView();
        pageShownTime = SystemClock.elapsedRealtime();
        setPageActive(currentPage, true);
    }

    @Override
    public void onPause() {
        super.onPause();
        trackCurrentDwell();
        setPageActive(currentPage, false);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.smartFeedDidLoad) {
            // append (догрузка) не сбрасывает позицию; сброс — только при первичной загрузке
            boolean firstLoad = currentPage < 0 || currentPage >= getPosts().size();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            updateEmptyView();
            if (firstLoad) {
                currentPage = -1;
                AndroidUtilities.runOnUIThread(() -> {
                    int position = layoutManager != null ? layoutManager.findFirstCompletelyVisibleItemPosition() : -1;
                    if (position >= 0) {
                        onPageSelected(position);
                    } else if (!getPosts().isEmpty()) {
                        onPageSelected(0);
                    }
                });
            }
        } else if (id == NotificationCenter.dialogsNeedReload) {
            if (getPosts().isEmpty()) {
                FeedController.getInstance(currentAccount).loadFeed(false);
            }
        }
    }

    private ArrayList<FeedController.FeedPost> getPosts() {
        return FeedController.getInstance(currentAccount).getPosts();
    }

    private void updateEmptyView() {
        if (emptyView == null) {
            return;
        }
        FeedController controller = FeedController.getInstance(currentAccount);
        if (controller.isLoading() || !controller.isLoadedOnce()) {
            emptyView.showProgress();
        } else {
            emptyView.setText(getString(controller.isExhausted() && getPosts().isEmpty()
                ? R.string.SmartFeedAllSeen : R.string.SmartFeedNoPosts));
            emptyView.showTextView();
        }
    }

    /* Пейджер */

    private void onPageSelected(int position) {
        if (position == currentPage) {
            return;
        }
        trackCurrentDwell();
        setPageActive(currentPage, false);
        currentPage = position;
        pageShownTime = SystemClock.elapsedRealtime();
        setPageActive(position, true);
        preloadAhead(position);
        // бесконечная лента: приближаемся к концу — догружаем
        if (position >= getPosts().size() - 5) {
            FeedController.getInstance(currentAccount).loadMore();
        }
    }

    private void trackCurrentDwell() {
        ArrayList<FeedController.FeedPost> posts = getPosts();
        if (currentPage >= 0 && currentPage < posts.size() && pageShownTime > 0) {
            FeedController.getInstance(currentAccount).trackPostDwell(
                posts.get(currentPage), SystemClock.elapsedRealtime() - pageShownTime, false);
        }
    }

    private void setPageActive(int position, boolean active) {
        FeedPageView page = findPageView(position);
        if (page != null) {
            page.setActive(active);
            if (active) {
                page.resetShrink(); // страховка от застрявшего сжатия шторки
            }
        }
    }

    private FeedPageView findPageView(int position) {
        if (pager == null || position < 0) {
            return null;
        }
        RecyclerView.ViewHolder holder = pager.findViewHolderForAdapterPosition(position);
        return holder != null && holder.itemView instanceof FeedPageView ? (FeedPageView) holder.itemView : null;
    }

    private FeedPageView findPageForPost(FeedController.FeedPost post) {
        if (pager == null) {
            return null;
        }
        for (int i = 0; i < pager.getChildCount(); i++) {
            View child = pager.getChildAt(i);
            if (child instanceof FeedPageView && ((FeedPageView) child).getPost() == post) {
                return (FeedPageView) child;
            }
        }
        return null;
    }

    /* Предзагрузка: выжимки пачкой на верхушку ленты + адаптивный к сети префетч фото */

    /** Глубина префетча по скорости сети: WiFi — глубже и больше, медленный мобильный — минимум. */
    private int prefetchDepth() {
        if (org.telegram.messenger.ApplicationLoader.isConnectedToWiFi()) {
            return 5;
        }
        if (org.telegram.messenger.ApplicationLoader.isConnectionSlow()) {
            return 1;
        }
        return PRELOAD_AHEAD;
    }

    // видео, поставленные на фоновую загрузку — чтобы отменить при пролистывании мимо
    private final ArrayList<TLRPC.Document> prefetchVideos = new ArrayList<>();

    private void preloadAhead(int position) {
        ArrayList<FeedController.FeedPost> posts = getPosts();
        clearPrefetch();
        final int depth = prefetchDepth();
        final boolean wifi = org.telegram.messenger.ApplicationLoader.isConnectedToWiFi();
        for (int offset = 1; offset <= depth; offset++) {
            int index = position + offset;
            if (index >= posts.size()) {
                break;
            }
            prefetchMedia(posts.get(index), offset, wifi);
        }
    }

    private void prefetchMedia(FeedController.FeedPost post, int offset, boolean wifi) {
        ArrayList<MessageObject> media = post.album != null ? post.album
            : new ArrayList<>(java.util.Collections.singletonList(post.message));
        final boolean slow = org.telegram.messenger.ApplicationLoader.isConnectionSlow();
        final int photoCount = slow ? 1 : Math.min(2, media.size());
        int photosDone = 0;
        for (int i = 0; i < media.size(); i++) {
            MessageObject messageObject = media.get(i);
            if (messageObject.isVideo()) {
                // видео следующего поста буферим заранее ТОЛЬКО на WiFi и только на 1 вперёд —
                // чтобы фоновая загрузка не отъедала канал у текущего проигрываемого видео
                if (offset == 1 && wifi && i == 0) {
                    TLRPC.Document doc = messageObject.getDocument();
                    if (doc != null && !isVideoCached(doc)) {
                        FileLoader.getInstance(currentAccount).loadFile(doc, messageObject, FileLoader.PRIORITY_LOW, 1);
                        prefetchVideos.add(doc);
                    }
                }
                continue;
            }
            if (photosDone >= photoCount || messageObject.photoThumbs == null || messageObject.photoThumbs.isEmpty()) {
                continue;
            }
            // ближним постам и на WiFi — полный размер; дальним/медленным — уменьшенный (полный догрузится при показе)
            final int sizePx = (wifi || offset == 1) ? AndroidUtilities.getPhotoSize() : 640;
            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, sizePx);
            if (photoSize == null) {
                continue;
            }
            // уже в кэше — не префетчим
            if (FileLoader.getInstance(currentAccount).getPathToAttach(photoSize, true).exists()) {
                continue;
            }
            ImageReceiver receiver = new ImageReceiver();
            receiver.setCurrentAccount(currentAccount);
            receiver.setDelegate((imageReceiver, set, thumb, memCache) -> {
                if (set && !thumb) {
                    AndroidUtilities.runOnUIThread(() -> {
                        prefetchReceivers.remove(imageReceiver);
                        imageReceiver.clearImage();
                    });
                }
            });
            receiver.setImage(ImageLocation.getForObject(photoSize, messageObject.photoThumbsObject), null, null, null, photoSize.size, null, messageObject, 1);
            prefetchReceivers.add(receiver);
            photosDone++;
        }
        while (prefetchReceivers.size() > 10) {
            prefetchReceivers.remove(0).clearImage();
        }
    }

    private boolean isVideoCached(TLRPC.Document doc) {
        java.io.File f = FileLoader.getInstance(currentAccount).getPathToAttach(doc, true);
        return f != null && f.exists();
    }

    private void clearPrefetch() {
        for (int i = 0; i < prefetchReceivers.size(); i++) {
            prefetchReceivers.get(i).clearImage();
        }
        prefetchReceivers.clear();
        // отменяем фоновую загрузку видео, мимо которых пролистнули, КРОМЕ активного —
        // его сейчас стримит плеер, отмена вызвала бы ре-буфер/статтер
        TLRPC.Document activeDoc = currentVideoDoc();
        for (int i = 0; i < prefetchVideos.size(); i++) {
            TLRPC.Document doc = prefetchVideos.get(i);
            if (doc != activeDoc) {
                FileLoader.getInstance(currentAccount).cancelLoadFile(doc);
            }
        }
        prefetchVideos.clear();
    }

    private TLRPC.Document currentVideoDoc() {
        // документ берём у активной страницы: у альбома играющее видео может быть
        // не post.message, и отмена префетча била бы не по тому документу
        FeedPageView page = findPageView(currentPage);
        return page != null ? page.getPlayingVideoDocument() : null;
    }

    /* Выжимки */

    /** ИИ-выжимка отменена: показываем оригинальный текст, обрезанный по длине. */
    private static final int FEED_TEXT_LIMIT = 700;

    private void requestSummary(FeedController.FeedPost post, FeedPageView targetPage) {
        if (targetPage == null) {
            return;
        }
        String text = post.getText();
        if (text != null) {
            text = text.trim();
            if (text.length() > FEED_TEXT_LIMIT) {
                int cut = text.lastIndexOf(' ', FEED_TEXT_LIMIT);
                text = text.substring(0, cut > FEED_TEXT_LIMIT / 2 ? cut : FEED_TEXT_LIMIT) + "…";
            }
        }
        targetPage.setSummary(text, true);
    }

    /* TabFragmentDelegate */

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        if (commentsPanel != null && commentsPanel.isShown()) {
            return false;
        }
        FeedPageView page = findPageView(currentPage);
        // на постах с каруселью горизонтальный жест листает галерею, не табы
        return page == null || !page.canCarouselScroll();
    }

    @Override
    public void onParentScrollToTop() {
        if (pager != null && currentPage > 0) {
            pager.scrollToPosition(0);
            AndroidUtilities.runOnUIThread(() -> onPageSelected(0));
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (commentsPanel != null && commentsPanel.isShown()) {
            if (invoked) {
                commentsPanel.hide();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    /* FeedPageView.Delegate — кнопки действий */

    @Override
    public void onDislike(FeedController.FeedPost post) {
        FeedController.getInstance(currentAccount).trackDislike(post);
        if (getParentActivity() != null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                getString(R.string.SmartFeedDisliked)).setDuration(Bulletin.DURATION_SHORT).show();
        }
        // сразу листаем к следующему посту
        ArrayList<FeedController.FeedPost> posts = getPosts();
        if (currentPage + 1 < posts.size() && pager != null) {
            pager.smoothScrollToPosition(currentPage + 1);
        }
    }

    @Override
    public void onOpenComments(FeedController.FeedPost post) {
        if (commentsPanel != null && !commentsPanel.isShown()) {
            AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
            commentsPanel.show(post);
        }
    }

    @Override
    public void onShareTelegram(FeedController.FeedPost post) {
        if (getParentActivity() == null) {
            return;
        }
        FeedController.getInstance(currentAccount).trackInteraction(post.dialogId, FeedController.INTERACTION_SHARE);
        showDialog(ShareAlert.createShareAlert(getParentActivity(), post.message, null, ChatObject.isChannel(post.chat), null, false));
    }

    @Override
    public void onShareExternal(FeedController.FeedPost post) {
        if (getParentActivity() == null) {
            return;
        }
        FeedController.getInstance(currentAccount).trackInteraction(post.dialogId, FeedController.INTERACTION_SHARE);
        String link;
        if (!TextUtils.isEmpty(ChatObject.getPublicUsername(post.chat))) {
            link = "https://t.me/" + ChatObject.getPublicUsername(post.chat) + "/" + post.getId();
        } else {
            link = post.getText();
        }
        if (TextUtils.isEmpty(link)) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, link);
        getParentActivity().startActivity(Intent.createChooser(intent, getString(R.string.ShareFile)));
    }

    @Override
    public void onOpenChannel(FeedController.FeedPost post) {
        Bundle args = new Bundle();
        args.putLong("chat_id", -post.dialogId);
        args.putInt("message_id", post.getId());
        presentFragment(new ChatActivity(args));
    }

    @Override
    public void onOpenFullPost(FeedController.FeedPost post) {
        FeedController.getInstance(currentAccount).trackInteraction(post.dialogId, FeedController.INTERACTION_FULL_POST_OPEN);
        showDialog(new FeedPostSheet(this, post));
    }

    /* FeedCommentsPanel.Delegate */

    @Override
    public void onShrinkProgress(float progress, int panelHeight) {
        // держим прямую ссылку на сжимаемую страницу, а не ищем по currentPage
        // (после reload currentPage=-1 и медиа осталось бы сжатым — баг с мелкой картинкой)
        if (shrinkTarget != null) {
            shrinkTarget.setCommentsShrink(progress, panelHeight);
        }
    }

    @Override
    public void onDismissed() {
        if (getParentActivity() != null) {
            AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);
        }
        if (commentsScrim != null) {
            commentsScrim.setVisibility(View.GONE);
        }
        if (historyButton != null) {
            historyButton.setVisibility(View.VISIBLE);
        }
        if (shrinkTarget != null) {
            shrinkTarget.setCommentsShrink(0f, 0);
            shrinkTarget.resetShrink();
            shrinkTarget = null;
        }
    }

    @Override
    public void onCommentsOpened(FeedController.FeedPost post) {
        shrinkTarget = findPageView(currentPage);
        if (commentsScrim != null) {
            commentsScrim.setVisibility(View.VISIBLE);
        }
        if (historyButton != null) {
            historyButton.setVisibility(View.GONE);
        }
        FeedController.getInstance(currentAccount).trackInteraction(post.dialogId, FeedController.INTERACTION_COMMENTS_OPEN);
    }

    /* Адаптер пейджера */

    private class PagerAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return getPosts().size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            FeedPageView view = new FeedPageView(parent.getContext(), currentAccount);
            view.setDelegate(FeedFragment.this);
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ArrayList<FeedController.FeedPost> posts = getPosts();
            if (position < 0 || position >= posts.size()) {
                return;
            }
            FeedController.FeedPost post = posts.get(position);
            FeedPageView page = (FeedPageView) holder.itemView;
            page.setInsets(statusBarHeight, getPageBottomInset());
            page.setPost(post);
            page.setActive(position == currentPage);
            requestSummary(post, page);
        }
    }
}
