package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.feed.FeedController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * История просмотренного в ленте — «центр активности» как в TikTok:
 * все виденные посты, новые сверху. Тап открывает пост в чате канала.
 */
public class FeedHistoryActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<FeedController.HistoryEntry> entries = new ArrayList<>();

    private static final int MENU_CLEAR = 1;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.SmartFeedHistory));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_CLEAR) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                    builder.setTitle(getString(R.string.SmartFeedHistoryClear));
                    builder.setMessage(getString(R.string.AreYouSure));
                    builder.setPositiveButton(getString(R.string.OK), (d, w) -> {
                        FeedController.getInstance(currentAccount).clearHistory();
                        entries.clear();
                        adapter.notifyDataSetChanged();
                    });
                    builder.setNegativeButton(getString(R.string.Cancel), null);
                    showDialog(builder.create());
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_CLEAR, R.drawable.msg_delete);

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        EmptyTextProgressView emptyView = new EmptyTextProgressView(context);
        emptyView.setText(getString(R.string.SmartFeedHistoryEmpty));
        emptyView.showTextView();
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter());
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= entries.size()) {
                return;
            }
            FeedController.HistoryEntry entry = entries.get(position);
            Bundle args = new Bundle();
            args.putLong("chat_id", -entry.dialogId);
            args.putInt("message_id", entry.messageId);
            presentFragment(new ChatActivity(args));
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    @Override
    public boolean onFragmentCreate() {
        entries = FeedController.getInstance(currentAccount).getHistory();
        return super.onFragmentCreate();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return entries.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            HistoryCell view = new HistoryCell(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((HistoryCell) holder.itemView).bind(entries.get(position));
        }
    }

    private class HistoryCell extends FrameLayout {

        private final BackupImageView avatarImageView;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView titleTextView;
        private final TextView subtitleTextView;

        public HistoryCell(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            setPadding(dp(14), dp(8), dp(14), dp(8));

            avatarImageView = new BackupImageView(context);
            avatarImageView.setRoundRadius(dp(22));
            addView(avatarImageView, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            titleTextView = new TextView(context);
            titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleTextView.setTypeface(AndroidUtilities.bold());
            titleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleTextView.setSingleLine(true);
            titleTextView.setEllipsize(TextUtils.TruncateAt.END);
            addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 56, 4, 12, 0));

            subtitleTextView = new TextView(context);
            subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            subtitleTextView.setSingleLine(true);
            subtitleTextView.setEllipsize(TextUtils.TruncateAt.END);
            addView(subtitleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 56, 25, 12, 0));
        }

        public void bind(FeedController.HistoryEntry entry) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-entry.dialogId);
            String name = chat != null ? chat.title : (entry.title != null ? entry.title : "");
            avatarDrawable.setInfo(chat);
            avatarImageView.setForUserOrChat(chat, avatarDrawable);
            titleTextView.setText(name);

            StringBuilder sb = new StringBuilder();
            sb.append(typeLabel(entry.contentType));
            if (entry.seenAt > 0) {
                sb.append(" · ").append(LocaleController.stringForMessageListDate(entry.seenAt / 1000));
            }
            subtitleTextView.setText(sb);
        }

        private String typeLabel(int type) {
            switch (type) {
                case FeedController.TYPE_VIDEO:
                    return getString(R.string.AttachVideo);
                case FeedController.TYPE_PHOTO:
                    return getString(R.string.AttachPhoto);
                default:
                    return getString(R.string.SmartFeedTypeText);
            }
        }
    }
}
