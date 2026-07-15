package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.feed.FeedController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.FeedMediaGrid;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Карточка поста умной ленты: шапка канала, дата и просмотры,
 * медиа-грид и AI-выжимка текста.
 */
public class FeedPostCell extends FrameLayout {

    private final BackupImageView avatarImageView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TextView nameTextView;
    private final TextView infoTextView;
    private final FeedMediaGrid mediaGrid;
    private final TextView summaryTextView;
    private final LinearLayout contentLayout;

    private FeedController.FeedPost currentPost;

    public FeedPostCell(Context context) {
        super(context);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(dp(14), dp(12), dp(14), dp(12));
        addView(contentLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout headerLayout = new FrameLayout(context);
        contentLayout.addView(headerLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 0, 0, 10));

        avatarImageView = new BackupImageView(context);
        avatarImageView.setRoundRadius(dp(21));
        headerLayout.addView(avatarImageView, LayoutHelper.createFrame(42, 42, Gravity.LEFT | Gravity.TOP));

        nameTextView = new TextView(context);
        nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameTextView.setTypeface(AndroidUtilities.bold());
        nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameTextView.setSingleLine(true);
        nameTextView.setEllipsize(TextUtils.TruncateAt.END);
        headerLayout.addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 52, 2, 0, 0));

        infoTextView = new TextView(context);
        infoTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        infoTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        infoTextView.setSingleLine(true);
        infoTextView.setEllipsize(TextUtils.TruncateAt.END);
        headerLayout.addView(infoTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 52, 23, 0, 0));

        mediaGrid = new FeedMediaGrid(context);
        contentLayout.addView(mediaGrid, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        summaryTextView = new TextView(context);
        summaryTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        summaryTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        summaryTextView.setLineSpacing(dp(2), 1f);
        summaryTextView.setMaxLines(10);
        summaryTextView.setEllipsize(TextUtils.TruncateAt.END);
        contentLayout.addView(summaryTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    public void setPost(FeedController.FeedPost post) {
        currentPost = post;

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

        boolean hasMedia = post.message.photoThumbs != null && !post.message.photoThumbs.isEmpty();
        if (hasMedia) {
            mediaGrid.setVisibility(VISIBLE);
            ArrayList<MessageObject> album = post.album;
            if (album == null) {
                album = new ArrayList<>();
                album.add(post.message);
            }
            mediaGrid.setMessages(album, post.groupedMessages);
        } else {
            mediaGrid.setVisibility(GONE);
        }
        setSummary(null, false);
    }

    /**
     * summary == null: показать статус (генерация/модель не скачана).
     */
    public void setSummary(String summary, boolean modelAvailable) {
        if (currentPost == null) {
            return;
        }
        if (TextUtils.isEmpty(currentPost.getText())) {
            summaryTextView.setVisibility(GONE);
            return;
        }
        summaryTextView.setVisibility(VISIBLE);
        if (summary != null) {
            summaryTextView.setTypeface(Typeface.DEFAULT);
            summaryTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            summaryTextView.setText(summary);
        } else {
            summaryTextView.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
            summaryTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            summaryTextView.setText(LocaleController.getString(modelAvailable ? R.string.SmartFeedSummarizing : R.string.SmartFeedSummaryUnavailable));
        }
    }

    public FeedController.FeedPost getPost() {
        return currentPost;
    }

    public FeedMediaGrid getMediaGrid() {
        return mediaGrid;
    }
}
