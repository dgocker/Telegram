package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.feed.FeedController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.EditTextEmoji;
import org.telegram.ui.Components.FeedMediaGrid;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;

/**
 * Модалка поста ленты: полный оригинальный текст, медиа в полном размере
 * (тап — PhotoViewer с альбомом), комментарии из discussion-группы
 * с пагинацией и полем ввода.
 */
public class FeedPostSheet extends BottomSheetWithRecyclerListView {

    private static final int ROW_HEADER = 0;
    private static final int ROW_MEDIA = 1;
    private static final int ROW_TEXT = 2;
    private static final int ROW_COMMENTS_HEADER = 3;
    private static final int ROW_COMMENT = 4;
    private static final int ROW_LOADING = 5;
    private static final int ROW_COMMENTS_STATUS = 6;

    private static final int COMMENTS_PAGE = 30;

    private final BaseFragment fragment;
    private final FeedController.FeedPost post;
    private final ArrayList<MessageObject> mediaMessages = new ArrayList<>();

    private ListAdapter adapter;
    private EditTextEmoji editText;
    private FrameLayout inputLayout;
    private FeedMediaGrid sheetMediaGrid;

    // discussion state
    private boolean discussionLoading;
    private boolean commentsUnavailable;
    private TLRPC.Message topMessage;
    private MessageObject topMessageObject;
    private long discussionDialogId;
    private final ArrayList<TLRPC.Message> comments = new ArrayList<>();
    private static final int LOCAL_COMMENT_ID_BASE = Integer.MAX_VALUE - 100000;
    private int maxServerCommentId;
    private boolean commentsLoading;
    private boolean commentsEndReached;
    private int localCommentIdCounter = LOCAL_COMMENT_ID_BASE;

    public FeedPostSheet(BaseFragment fragment, FeedController.FeedPost post) {
        super(fragment, true, false);
        this.fragment = fragment;
        this.post = post;

        if (post.album != null) {
            mediaMessages.addAll(post.album);
        } else if (post.message.photoThumbs != null && !post.message.photoThumbs.isEmpty()) {
            mediaMessages.add(post.message);
        }

        Context context = getContext();

        inputLayout = new FrameLayout(context);
        inputLayout.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));
        inputLayout.setVisibility(View.GONE);

        editText = new EditTextEmoji(context, (SizeNotifierFrameLayout) containerView, null, EditTextEmoji.STYLE_DIALOG, true, resourcesProvider);
        editText.setHint(getString(R.string.SmartFeedCommentHint));
        inputLayout.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT, 0, 0, 52, 0));
        setEditTextEmoji(editText);

        ImageView sendButton = new ImageView(context);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setImageResource(R.drawable.msg_send);
        sendButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_featuredStickers_addButton), PorterDuff.Mode.SRC_IN));
        sendButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1));
        sendButton.setOnClickListener(v -> sendComment());
        inputLayout.addView(sendButton, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        containerView.addView(inputLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        recyclerListView.setClipToPadding(false);
        recyclerListView.setPadding(recyclerListView.getPaddingLeft(), recyclerListView.getPaddingTop(), recyclerListView.getPaddingRight(), dp(58));
        recyclerListView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || commentsLoading || commentsEndReached || topMessage == null) {
                    return;
                }
                int lastVisible = -1;
                for (int i = 0; i < recyclerView.getChildCount(); i++) {
                    View child = recyclerView.getChildAt(i);
                    int position = recyclerView.getChildAdapterPosition(child);
                    lastVisible = Math.max(lastVisible, position);
                }
                if (lastVisible >= adapter.getItemCount() - 5) {
                    loadComments();
                }
            }
        });

        if (actionBar != null) {
            actionBar.setTitle(getTitle());
        }
        loadDiscussion();
    }

    @Override
    protected CharSequence getTitle() {
        // вызывается из конструктора базового класса, когда post ещё null
        return post != null && post.chat != null ? post.chat.title : "";
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        return adapter = new ListAdapter();
    }

    @Override
    public void dismissInternal() {
        if (editText != null) {
            editText.onDestroy();
        }
        super.dismissInternal();
    }

    /* Discussion */

    private void loadDiscussion() {
        if (discussionLoading) {
            return;
        }
        discussionLoading = true;
        TLRPC.TL_messages_getDiscussionMessage req = new TLRPC.TL_messages_getDiscussionMessage();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(post.dialogId);
        req.msg_id = post.getId();
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            discussionLoading = false;
            if (response instanceof TLRPC.TL_messages_discussionMessage) {
                TLRPC.TL_messages_discussionMessage res = (TLRPC.TL_messages_discussionMessage) response;
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                TLRPC.Message top = null;
                for (int i = 0; i < res.messages.size(); i++) {
                    TLRPC.Message msg = res.messages.get(i);
                    if (!(msg instanceof TLRPC.TL_messageEmpty)) {
                        top = msg;
                        break;
                    }
                }
                if (top == null) {
                    commentsUnavailable = true;
                } else {
                    topMessage = top;
                    topMessageObject = new MessageObject(currentAccount, top, false, false);
                    discussionDialogId = topMessageObject.getDialogId();
                    inputLayout.setVisibility(View.VISIBLE);
                    loadComments();
                }
            } else {
                commentsUnavailable = true;
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }));
    }

    private void loadComments() {
        if (commentsLoading || commentsEndReached || topMessage == null) {
            return;
        }
        commentsLoading = true;
        TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(discussionDialogId);
        req.msg_id = topMessage.id;
        if (maxServerCommentId == 0) {
            // первая страница: самые старые комментарии
            req.offset_id = 1;
            req.add_offset = -COMMENTS_PAGE;
            req.limit = COMMENTS_PAGE;
        } else {
            // следующие страницы: более новые, чем уже загруженные
            req.offset_id = maxServerCommentId;
            req.add_offset = -COMMENTS_PAGE - 1;
            req.limit = COMMENTS_PAGE + 1;
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            commentsLoading = false;
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                ArrayList<TLRPC.Message> fresh = new ArrayList<>();
                for (int i = 0; i < res.messages.size(); i++) {
                    TLRPC.Message msg = res.messages.get(i);
                    if (msg instanceof TLRPC.TL_messageEmpty || msg instanceof TLRPC.TL_messageService) {
                        continue;
                    }
                    if (msg.id <= maxServerCommentId || msg.id == topMessage.id) {
                        continue;
                    }
                    fresh.add(msg);
                }
                if (fresh.isEmpty()) {
                    commentsEndReached = true;
                } else {
                    java.util.Collections.sort(fresh, (a, b) -> a.id - b.id);
                    // локальные (оптимистичные) комментарии держим в конце
                    int insertIndex = comments.size();
                    while (insertIndex > 0 && comments.get(insertIndex - 1).id >= LOCAL_COMMENT_ID_BASE) {
                        insertIndex--;
                    }
                    comments.addAll(insertIndex, fresh);
                    maxServerCommentId = fresh.get(fresh.size() - 1).id;
                    if (fresh.size() < COMMENTS_PAGE) {
                        commentsEndReached = true;
                    }
                }
            } else {
                commentsEndReached = true;
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }));
    }

    private void sendComment() {
        if (editText == null || topMessageObject == null) {
            return;
        }
        CharSequence textRaw = editText.getText();
        String text = textRaw == null ? "" : textRaw.toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        CharSequence[] message = new CharSequence[]{text};
        ArrayList<TLRPC.MessageEntity> entities = MediaDataController.getInstance(currentAccount).getEntities(message, true);
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
            message[0].toString(), discussionDialogId, topMessageObject, topMessageObject,
            null, true, entities, null, null, true, 0, 0, null, false);
        SendMessagesHelper.getInstance(currentAccount).sendMessage(params);

        editText.setText("");
        AndroidUtilities.hideKeyboard(editText.getEditText());

        // оптимистично показываем свой комментарий сразу
        TLRPC.TL_message local = new TLRPC.TL_message();
        local.id = localCommentIdCounter++;
        local.message = text;
        local.date = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = UserConfig.getInstance(currentAccount).getClientUserId();
        local.from_id = peer;
        comments.add(local);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (recyclerListView.getAdapter() != null) {
            recyclerListView.smoothScrollToPosition(recyclerListView.getAdapter().getItemCount() - 1);
        }
    }

    /* Media */

    private void openMedia(int index) {
        if (mediaMessages.isEmpty() || fragment == null) {
            return;
        }
        PhotoViewer.getInstance().setParentActivity(fragment, resourcesProvider);
        PhotoViewer.getInstance().openPhoto(mediaMessages, index, post.dialogId, 0, 0, photoViewerProvider);
    }

    private final PhotoViewer.EmptyPhotoViewerProvider photoViewerProvider = new PhotoViewer.EmptyPhotoViewerProvider() {
        @Override
        public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index, boolean needPreview, boolean closing) {
            if (sheetMediaGrid == null || messageObject == null) {
                return null;
            }
            int mediaIndex = mediaMessages.indexOf(messageObject);
            if (mediaIndex < 0) {
                for (int i = 0; i < mediaMessages.size(); i++) {
                    if (mediaMessages.get(i).getId() == messageObject.getId()) {
                        mediaIndex = i;
                        break;
                    }
                }
            }
            BackupImageView imageView = sheetMediaGrid.getImageViewAt(mediaIndex);
            if (imageView == null || !imageView.isAttachedToWindow()) {
                return null;
            }
            int[] coords = new int[2];
            imageView.getLocationInWindow(coords);
            PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
            object.viewX = coords[0];
            object.viewY = coords[1];
            object.parentView = recyclerListView;
            object.imageReceiver = imageView.getImageReceiver();
            object.thumb = object.imageReceiver.getBitmapSafe();
            object.radius = object.imageReceiver.getRoundRadius(true);
            return object;
        }

        @Override
        public boolean forceAllInGroup() {
            return true;
        }
    };

    /* Adapter */

    private int rowCount;
    private int headerRow, mediaRow, textRow, commentsHeaderRow, commentsStartRow, commentsEndRow, loadingRow, statusRow;

    private void updateRows() {
        headerRow = mediaRow = textRow = commentsHeaderRow = commentsStartRow = commentsEndRow = loadingRow = statusRow = -1;
        rowCount = 0;
        if (post == null || mediaMessages == null) {
            // адаптер может дёрнуться из конструктора базового класса до инициализации полей
            return;
        }
        headerRow = rowCount++;
        if (!mediaMessages.isEmpty()) {
            mediaRow = rowCount++;
        }
        if (!TextUtils.isEmpty(post.getText())) {
            textRow = rowCount++;
        }
        commentsHeaderRow = rowCount++;
        if (!comments.isEmpty()) {
            commentsStartRow = rowCount;
            rowCount += comments.size();
            commentsEndRow = rowCount;
        }
        if (commentsUnavailable || (commentsEndReached && comments.isEmpty())) {
            statusRow = rowCount++;
        } else if (comments.isEmpty()) {
            // индикатор только в пустом состоянии: иначе loadComments() из
            // scroll-листенера менял бы rowCount без notify прямо во время скролла
            loadingRow = rowCount++;
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public void notifyDataSetChanged() {
            updateRows();
            super.notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            updateRows();
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow) {
                return ROW_HEADER;
            }
            if (position == mediaRow) {
                return ROW_MEDIA;
            }
            if (position == textRow) {
                return ROW_TEXT;
            }
            if (position == commentsHeaderRow) {
                return ROW_COMMENTS_HEADER;
            }
            if (position == loadingRow) {
                return ROW_LOADING;
            }
            if (position == statusRow) {
                return ROW_COMMENTS_STATUS;
            }
            return ROW_COMMENT;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            switch (viewType) {
                case ROW_HEADER:
                    view = new PostHeaderView(context);
                    break;
                case ROW_MEDIA:
                    view = new FeedMediaGrid(context);
                    break;
                case ROW_TEXT: {
                    TextView textView = new TextView(context);
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                    textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                    textView.setLineSpacing(dp(2), 1f);
                    textView.setPadding(dp(16), dp(10), dp(16), dp(10));
                    textView.setAutoLinkMask(Linkify.WEB_URLS);
                    textView.setLinkTextColor(getThemedColor(Theme.key_windowBackgroundWhiteLinkText));
                    textView.setTextIsSelectable(true);
                    view = textView;
                    break;
                }
                case ROW_COMMENTS_HEADER: {
                    TextView textView = new TextView(context);
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                    textView.setTypeface(AndroidUtilities.bold());
                    textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                    textView.setPadding(dp(16), dp(14), dp(16), dp(6));
                    textView.setText(getString(R.string.SmartFeedComments));
                    view = textView;
                    break;
                }
                case ROW_LOADING: {
                    FrameLayout frameLayout = new FrameLayout(context);
                    RadialProgressView progressView = new RadialProgressView(context, resourcesProvider);
                    progressView.setSize(dp(24));
                    frameLayout.addView(progressView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 12, 0, 12));
                    view = frameLayout;
                    break;
                }
                case ROW_COMMENTS_STATUS: {
                    TextView textView = new TextView(context);
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                    textView.setGravity(Gravity.CENTER);
                    textView.setPadding(dp(16), dp(14), dp(16), dp(14));
                    view = textView;
                    break;
                }
                case ROW_COMMENT:
                default:
                    view = new CommentCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int viewType = holder.getItemViewType();
            if (viewType == ROW_HEADER) {
                ((PostHeaderView) holder.itemView).bind();
            } else if (viewType == ROW_MEDIA) {
                sheetMediaGrid = (FeedMediaGrid) holder.itemView;
                sheetMediaGrid.setMessages(mediaMessages, post.groupedMessages);
                sheetMediaGrid.setOnMediaClickListener(FeedPostSheet.this::openMedia);
            } else if (viewType == ROW_TEXT) {
                ((TextView) holder.itemView).setText(post.getText());
            } else if (viewType == ROW_COMMENTS_STATUS) {
                ((TextView) holder.itemView).setText(getString(commentsUnavailable ? R.string.SmartFeedCommentsUnavailable : R.string.SmartFeedNoComments));
            } else if (viewType == ROW_COMMENT) {
                int index = position - commentsStartRow;
                if (index >= 0 && index < comments.size()) {
                    ((CommentCell) holder.itemView).bind(comments.get(index));
                }
            }
        }
    }

    /* Cells */

    private class PostHeaderView extends FrameLayout {

        private final BackupImageView avatarImageView;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView nameTextView;
        private final TextView infoTextView;

        public PostHeaderView(Context context) {
            super(context);
            setPadding(dp(16), dp(10), dp(16), dp(6));

            avatarImageView = new BackupImageView(context);
            avatarImageView.setRoundRadius(dp(21));
            addView(avatarImageView, LayoutHelper.createFrame(42, 42, Gravity.LEFT | Gravity.TOP));

            nameTextView = new TextView(context);
            nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            nameTextView.setTypeface(AndroidUtilities.bold());
            nameTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            nameTextView.setSingleLine(true);
            nameTextView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 52, 2, 0, 0));

            infoTextView = new TextView(context);
            infoTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            infoTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            infoTextView.setSingleLine(true);
            addView(infoTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 52, 23, 0, 0));
        }

        public void bind() {
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
            nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            nameTextView.setTypeface(AndroidUtilities.bold());
            nameTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText4));
            nameTextView.setSingleLine(true);
            nameTextView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 42, 0, 60, 0));

            timeTextView = new TextView(context);
            timeTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            timeTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            timeTextView.setSingleLine(true);
            addView(timeTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.TOP, 0, 2, 0, 0));

            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setLineSpacing(dp(1), 1f);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 42, 20, 0, 0));
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
                textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                textView.setText(message.message);
            } else {
                textView.setTypeface(android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.ITALIC));
                textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                textView.setText(getString(message.media instanceof TLRPC.TL_messageMediaPhoto ? R.string.AttachPhoto : R.string.AttachDocument));
            }
        }
    }
}
