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
import org.telegram.messenger.feed.FeedSummarizer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.FeedCommentsPanel;
import org.telegram.ui.Components.FeedModelDownloadAlert;
import org.telegram.ui.Components.FeedPageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;

import java.util.ArrayList;
import java.util.HashSet;

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
    private TextView modelChip;
    private FeedCommentsPanel commentsPanel;

    private final HashSet<String> requestedSummaries = new HashSet<>();
    private final java.util.HashMap<String, Integer> summaryAttempts = new java.util.HashMap<>();
    private boolean modelDownloaded;

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
        modelDownloaded = FeedSummarizer.isModelDownloaded();
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

        modelChip = new TextView(context);
        modelChip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        modelChip.setTypeface(AndroidUtilities.bold());
        modelChip.setTextColor(Color.WHITE);
        modelChip.setBackground(org.telegram.ui.ActionBar.Theme.createRoundRectDrawable(dp(16), 0x66000000));
        modelChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        modelChip.setText(getString(R.string.SmartFeedModelBanner));
        modelChip.setOnClickListener(v -> showModelDownloadAlert());
        modelChip.setVisibility(modelDownloaded ? View.GONE : View.VISIBLE);
        contentView.addView(modelChip, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        commentsPanel = new FeedCommentsPanel(context, currentAccount, this);
        contentView.addView(commentsPanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 400, Gravity.BOTTOM));

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
        if (modelChip != null) {
            ((FrameLayout.LayoutParams) modelChip.getLayoutParams()).topMargin = statusBarHeight + dp(8);
        }
        if (commentsPanel != null) {
            int panelHeight = (int) ((AndroidUtilities.displaySize.y) * 0.66f);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) commentsPanel.getLayoutParams();
            if (lp.height != panelHeight) {
                lp.height = panelHeight;
                commentsPanel.requestLayout();
            }
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
        checkModelDownloaded();
        FeedController.getInstance(currentAccount).loadFeed(false);
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

    private void checkModelDownloaded() {
        boolean downloaded = FeedSummarizer.isModelDownloaded();
        if (downloaded != modelDownloaded) {
            modelDownloaded = downloaded;
            if (modelChip != null) {
                modelChip.setVisibility(modelDownloaded ? View.GONE : View.VISIBLE);
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.smartFeedDidLoad) {
            currentPage = -1;
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            updateEmptyView();
            AndroidUtilities.runOnUIThread(() -> {
                int position = layoutManager != null ? layoutManager.findFirstCompletelyVisibleItemPosition() : -1;
                if (position >= 0) {
                    onPageSelected(position);
                } else if (!getPosts().isEmpty()) {
                    onPageSelected(0);
                }
            });
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
        if (FeedController.getInstance(currentAccount).isLoading() || !FeedController.getInstance(currentAccount).isLoadedOnce()) {
            emptyView.showProgress();
        } else {
            emptyView.showTextView();
        }
    }

    private void showModelDownloadAlert() {
        if (getParentActivity() == null) {
            return;
        }
        FeedModelDownloadAlert.show(getParentActivity(), getResourceProvider(), this::checkModelDownloaded);
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
    }

    private void trackCurrentDwell() {
        ArrayList<FeedController.FeedPost> posts = getPosts();
        if (currentPage >= 0 && currentPage < posts.size() && pageShownTime > 0) {
            FeedController.FeedPost post = posts.get(currentPage);
            FeedController.getInstance(currentAccount).trackPostDwell(post.dialogId, post.getId(), SystemClock.elapsedRealtime() - pageShownTime, false);
        }
    }

    private void setPageActive(int position, boolean active) {
        FeedPageView page = findPageView(position);
        if (page != null) {
            page.setActive(active);
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

    /* Предзагрузка следующих постов: выжимки + фотографии */

    private void preloadAhead(int position) {
        ArrayList<FeedController.FeedPost> posts = getPosts();
        clearPrefetch();
        for (int offset = 1; offset <= PRELOAD_AHEAD; offset++) {
            int index = position + offset;
            if (index >= posts.size()) {
                break;
            }
            FeedController.FeedPost post = posts.get(index);
            requestSummary(post, null);
            prefetchMedia(post);
        }
    }

    private void prefetchMedia(FeedController.FeedPost post) {
        ArrayList<MessageObject> media = post.album;
        if (media == null) {
            if (post.message.photoThumbs == null || post.message.photoThumbs.isEmpty()) {
                return;
            }
            media = new ArrayList<>();
            media.add(post.message);
        }
        for (int i = 0; i < Math.min(2, media.size()); i++) {
            MessageObject messageObject = media.get(i);
            if (messageObject.isVideo()) {
                continue; // видео стримится автоплеем при показе
            }
            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, AndroidUtilities.getPhotoSize());
            if (photoSize == null) {
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
        }
        while (prefetchReceivers.size() > 8) {
            prefetchReceivers.remove(0).clearImage();
        }
    }

    private void clearPrefetch() {
        for (int i = 0; i < prefetchReceivers.size(); i++) {
            prefetchReceivers.get(i).clearImage();
        }
        prefetchReceivers.clear();
    }

    /* Выжимки */

    private void requestSummary(FeedController.FeedPost post, FeedPageView targetPage) {
        final String text = post.getText();
        if (TextUtils.isEmpty(text)) {
            if (targetPage != null) {
                targetPage.setSummary(null, true);
            }
            return;
        }
        final String cached = FeedSummarizer.getInstance().getCached(post.dialogId, post.getId());
        if (cached != null) {
            if (targetPage != null) {
                targetPage.setSummary(cached, modelDownloaded);
            }
            return;
        }
        if (text.length() < FeedSummarizer.MIN_TEXT_LENGTH_TO_SUMMARIZE) {
            if (targetPage != null) {
                targetPage.setSummary(text.trim(), modelDownloaded);
            }
            return;
        }
        if (targetPage != null) {
            targetPage.setSummary(null, modelDownloaded);
        }
        if (!modelDownloaded) {
            return;
        }
        final String key = post.dialogId + "_" + post.getId();
        if (requestedSummaries.contains(key)) {
            return;
        }
        requestedSummaries.add(key);
        final FeedController.FeedPost requestedPost = post;
        FeedSummarizer.getInstance().summarize(post.dialogId, post.getId(), text, summary -> {
            requestedSummaries.remove(key);
            FeedPageView page = findPageForPost(requestedPost);
            if (summary != null) {
                summaryAttempts.remove(key);
                if (page != null) {
                    page.setSummary(summary, true);
                }
                return;
            }
            int attempts = summaryAttempts.containsKey(key) ? summaryAttempts.get(key) : 0;
            summaryAttempts.put(key, attempts + 1);
            if (page != null) {
                if (attempts + 1 < 3) {
                    requestSummary(requestedPost, page);
                } else {
                    page.setSummary(text.trim(), true);
                }
            }
        });
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
        FeedController.getInstance(currentAccount).trackDislike(post.dialogId, post.getId());
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
    public void onOpenFullPost(FeedController.FeedPost post) {
        FeedController.getInstance(currentAccount).trackInteraction(post.dialogId, FeedController.INTERACTION_FULL_POST_OPEN);
        showDialog(new FeedPostSheet(this, post));
    }

    /* FeedCommentsPanel.Delegate */

    @Override
    public void onShrinkProgress(float progress, int panelHeight) {
        FeedPageView page = findPageView(currentPage);
        if (page != null) {
            page.setCommentsShrink(progress, panelHeight);
        }
    }

    @Override
    public void onDismissed() {
        if (getParentActivity() != null) {
            AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);
        }
        FeedPageView page = findPageView(currentPage);
        if (page != null) {
            page.setCommentsShrink(0f, 0);
        }
    }

    @Override
    public void onCommentsOpened(FeedController.FeedPost post) {
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
