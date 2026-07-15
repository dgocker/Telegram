package org.telegram.messenger.feed;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Загрузка комментариев поста канала из привязанной discussion-группы:
 * getDiscussionMessage → страницы getReplies (от старых к новым) → отправка в тред.
 */
public class FeedCommentsLoader {

    public interface Delegate {
        void onUpdated();
    }

    private static final int PAGE = 30;
    public static final int LOCAL_ID_BASE = Integer.MAX_VALUE - 100000;

    private final int currentAccount;
    private final FeedController.FeedPost post;
    private final Delegate delegate;

    private boolean discussionLoading;
    private boolean unavailable;
    private TLRPC.Message topMessage;
    private MessageObject topMessageObject;
    private long discussionDialogId;
    private final ArrayList<TLRPC.Message> comments = new ArrayList<>();
    private int maxServerCommentId;
    private boolean loading;
    private boolean endReached;
    private int localIdCounter = LOCAL_ID_BASE;

    public FeedCommentsLoader(int currentAccount, FeedController.FeedPost post, Delegate delegate) {
        this.currentAccount = currentAccount;
        this.post = post;
        this.delegate = delegate;
    }

    public ArrayList<TLRPC.Message> getComments() {
        return comments;
    }

    public boolean isUnavailable() {
        return unavailable;
    }

    public boolean isLoading() {
        return loading || discussionLoading;
    }

    public boolean isEndReached() {
        return endReached;
    }

    public boolean canComment() {
        return topMessageObject != null;
    }

    public void start() {
        if (discussionLoading || topMessage != null || unavailable) {
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
                    unavailable = true;
                } else {
                    topMessage = top;
                    topMessageObject = new MessageObject(currentAccount, top, false, false);
                    discussionDialogId = topMessageObject.getDialogId();
                    loadMore();
                }
            } else {
                unavailable = true;
            }
            delegate.onUpdated();
        }));
    }

    public void loadMore() {
        if (loading || endReached || topMessage == null) {
            return;
        }
        loading = true;
        TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(discussionDialogId);
        req.msg_id = topMessage.id;
        if (maxServerCommentId == 0) {
            req.offset_id = 1;
            req.add_offset = -PAGE;
            req.limit = PAGE;
        } else {
            req.offset_id = maxServerCommentId;
            req.add_offset = -PAGE - 1;
            req.limit = PAGE + 1;
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            loading = false;
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
                    endReached = true;
                } else {
                    Collections.sort(fresh, (a, b) -> a.id - b.id);
                    int insertIndex = comments.size();
                    while (insertIndex > 0 && comments.get(insertIndex - 1).id >= LOCAL_ID_BASE) {
                        insertIndex--;
                    }
                    comments.addAll(insertIndex, fresh);
                    maxServerCommentId = fresh.get(fresh.size() - 1).id;
                    if (fresh.size() < PAGE) {
                        endReached = true;
                    }
                }
            } else {
                endReached = true;
            }
            delegate.onUpdated();
        }));
    }

    /** Отправляет комментарий и оптимистично добавляет его в конец списка. */
    public boolean send(String text) {
        text = text == null ? "" : text.trim();
        if (TextUtils.isEmpty(text) || topMessageObject == null) {
            return false;
        }
        CharSequence[] message = new CharSequence[]{text};
        ArrayList<TLRPC.MessageEntity> entities = MediaDataController.getInstance(currentAccount).getEntities(message, true);
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
            message[0].toString(), discussionDialogId, topMessageObject, topMessageObject,
            null, true, entities, null, null, true, 0, 0, null, false);
        SendMessagesHelper.getInstance(currentAccount).sendMessage(params);

        TLRPC.TL_message local = new TLRPC.TL_message();
        local.id = localIdCounter++;
        local.message = text;
        local.date = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = UserConfig.getInstance(currentAccount).getClientUserId();
        local.from_id = peer;
        comments.add(local);
        delegate.onUpdated();
        return true;
    }
}
