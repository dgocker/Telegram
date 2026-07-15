package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
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
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.feed.FeedController;
import org.telegram.messenger.feed.FeedSummarizer;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.FeedPostCell;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.FeedModelDownloadAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.HashSet;

public class FeedFragment extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private boolean hasMainTabs;
    private int additionNavigationBarHeight;
    private int navigationBarHeight;

    private FrameLayout contentView;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ListAdapter adapter;
    private EmptyTextProgressView emptyView;

    private final HashSet<String> requestedSummaries = new HashSet<>();
    private final java.util.HashMap<String, Integer> summaryAttempts = new java.util.HashMap<>();
    // кэш: isModelDownloaded() ходит по диску, из адаптера её дёргать нельзя,
    // а смена значения без notifyDataSetChanged роняет RecyclerView
    private boolean modelDownloaded;

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
        super.onFragmentDestroy();
    }

    @Override
    public ActionBar createActionBar(Context context) {
        ActionBar actionBar = super.createActionBar(context);
        actionBar.setAddToContainer(false);
        return actionBar;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.MainTabsFeed));

        contentView = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                measureChildWithMargins(actionBar, widthMeasureSpec, 0, heightMeasureSpec, 0);
                checkListViewPadding();
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        fragmentView = contentView;
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        emptyView = new EmptyTextProgressView(context);
        emptyView.setText(getString(R.string.SmartFeedNoPosts));
        emptyView.showProgress();
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context);
        listView.setClipToPadding(false);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener((view, position) -> {
            if (adapter.hasBanner() && position == 0) {
                showModelDownloadAlert();
            } else if (view instanceof FeedPostCell) {
                showPostSheet(((FeedPostCell) view).getPost());
            }
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        contentView.addView(actionBar);

        if (hasMainTabs) {
            ViewCompat.setOnApplyWindowInsetsListener(fragmentView, this::onInsetsInternal);
        }
        return fragmentView;
    }

    private void checkListViewPadding() {
        if (listView == null) {
            return;
        }
        final int topPadding = actionBar.getMeasuredHeight() + dp(8);
        final int bottomPadding = navigationBarHeight + additionNavigationBarHeight + dp(8);
        if (listView.getPaddingTop() != topPadding || listView.getPaddingBottom() != bottomPadding) {
            listView.setPadding(0, topPadding, 0, bottomPadding);
            emptyView.setPadding(0, topPadding, 0, bottomPadding);
        }
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        navigationBarHeight = bottom;
        checkListViewPadding();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        checkModelDownloaded();
        FeedController.getInstance(currentAccount).loadFeed(false);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmptyView();
    }

    private void checkModelDownloaded() {
        boolean downloaded = FeedSummarizer.isModelDownloaded();
        if (downloaded != modelDownloaded) {
            modelDownloaded = downloaded;
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.smartFeedDidLoad) {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            updateEmptyView();
        } else if (id == NotificationCenter.dialogsNeedReload) {
            if (FeedController.getInstance(currentAccount).getPosts().isEmpty()) {
                FeedController.getInstance(currentAccount).loadFeed(false);
            }
        }
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

    private void showPostSheet(FeedController.FeedPost post) {
        if (post != null && getParentActivity() != null) {
            showDialog(new FeedPostSheet(FeedFragment.this, post));
        }
    }

    private void showModelDownloadAlert() {
        if (getParentActivity() == null) {
            return;
        }
        FeedModelDownloadAlert.show(getParentActivity(), getResourceProvider(), this::checkModelDownloaded);
    }

    /* TabFragmentDelegate */

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null && layoutManager != null) {
            if (layoutManager.findFirstVisibleItemPosition() > 12) {
                listView.scrollToPosition(6);
            }
            listView.smoothScrollToPosition(0);
        }
    }

    /* */

    private void bindSummary(FeedPostCell cell, FeedController.FeedPost post) {
        final String text = post.getText();
        if (TextUtils.isEmpty(text)) {
            cell.setSummary(null, true);
            return;
        }
        final boolean modelReady = modelDownloaded;
        final String cached = FeedSummarizer.getInstance().getCached(post.dialogId, post.getId());
        if (cached != null) {
            cell.setSummary(cached, modelReady);
            return;
        }
        if (text.length() < FeedSummarizer.MIN_TEXT_LENGTH_TO_SUMMARIZE) {
            cell.setSummary(text.trim(), modelReady);
            return;
        }
        cell.setSummary(null, modelReady);
        if (!modelReady) {
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
            if (listView == null) {
                return;
            }
            FeedPostCell visibleCell = null;
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (child instanceof FeedPostCell && ((FeedPostCell) child).getPost() == requestedPost) {
                    visibleCell = (FeedPostCell) child;
                    break;
                }
            }
            if (summary != null) {
                summaryAttempts.remove(key);
                if (visibleCell != null) {
                    visibleCell.setSummary(summary, true);
                }
                return;
            }
            // запрос вытеснен из очереди при быстрой прокрутке или упал — ретраим
            int attempts = summaryAttempts.containsKey(key) ? summaryAttempts.get(key) : 0;
            summaryAttempts.put(key, attempts + 1);
            if (visibleCell != null) {
                if (attempts + 1 < 3) {
                    bindSummary(visibleCell, requestedPost);
                } else {
                    visibleCell.setSummary(text.trim(), true);
                }
            }
        });
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private static final int VIEW_TYPE_BANNER = 0;
        private static final int VIEW_TYPE_POST = 1;

        private final Context context;

        public ListAdapter(Context context) {
            this.context = context;
        }

        public boolean hasBanner() {
            return !modelDownloaded;
        }

        @Override
        public int getItemCount() {
            int count = FeedController.getInstance(currentAccount).getPosts().size();
            if (count > 0 && hasBanner()) {
                count++;
            }
            return count;
        }

        @Override
        public int getItemViewType(int position) {
            return hasBanner() && position == 0 ? VIEW_TYPE_BANNER : VIEW_TYPE_POST;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_BANNER) {
                TextView textView = new TextView(context);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                textView.setTypeface(AndroidUtilities.bold());
                textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
                textView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                textView.setGravity(Gravity.CENTER);
                textView.setPadding(dp(16), dp(14), dp(16), dp(14));
                textView.setText(getString(R.string.SmartFeedModelBanner));
                view = textView;
            } else {
                view = new FeedPostCell(context);
            }
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            view.setLayoutParams(lp);
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (getItemViewType(position) == VIEW_TYPE_POST) {
                int index = hasBanner() ? position - 1 : position;
                if (index < 0 || index >= FeedController.getInstance(currentAccount).getPosts().size()) {
                    return;
                }
                FeedController.FeedPost post = FeedController.getInstance(currentAccount).getPosts().get(index);
                FeedPostCell cell = (FeedPostCell) holder.itemView;
                cell.setPost(post);
                // тап по медиа открывает ту же модалку, что и тап по карточке
                cell.getMediaGrid().setOnMediaClickListener(mediaIndex -> showPostSheet(post));
                bindSummary(cell, post);
            }
        }
    }
}
