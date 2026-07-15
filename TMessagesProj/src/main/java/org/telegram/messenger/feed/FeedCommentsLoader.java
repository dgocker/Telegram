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
    // newest-first: comments.get(0) — самый свежий
    private final ArrayList<TLRPC.Message> comments = new ArrayList<>();
    private int maxLoadedId;   // самый свежий загруженный серверный id
    private int minLoadedId;   // самый старый загруженный серверный id
    private boolean loading;
    private boolean endReached;
    private int localIdCounter = LOCAL_ID_BASE;

    private boolean liveActive;
    private final Runnable pollRunnable = this::pollNew;

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
                    startLive();
                }
            } else {
                unavailable = true;
            }
            delegate.onUpdated();
        }));
    }

    /** Догрузка более старых комментариев (вниз по списку). */
    public void loadMore() {
        if (loading || endReached || topMessage == null) {
            return;
        }
        loading = true;
        TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(discussionDialogId);
        req.msg_id = topMessage.id;
        // offset_id=0 → самые свежие; иначе — старее уже загруженных
        req.offset_id = minLoadedId == 0 ? 0 : minLoadedId;
        req.add_offset = 0;
        req.limit = PAGE;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            loading = false;
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                ArrayList<TLRPC.Message> fresh = filterFresh(res.messages, true);
                if (fresh.isEmpty()) {
                    endReached = true;
                } else {
                    // newest-first: старые уходят в конец списка (перед локальными нет — локальные сверху)
                    Collections.sort(fresh, (a, b) -> b.id - a.id);
                    for (TLRPC.Message m : fresh) {
                        comments.add(m);
                        maxLoadedId = Math.max(maxLoadedId, m.id);
                        minLoadedId = minLoadedId == 0 ? m.id : Math.min(minLoadedId, m.id);
                    }
                    if (fresh.size() < PAGE) {
                        endReached = true;
                    }
                }
            } else {
                endReached = true;
            }
            delegate.onUpdated();
            scheduleLive();
        }));
    }

    private ArrayList<TLRPC.Message> filterFresh(ArrayList<TLRPC.Message> messages, boolean excludeKnown) {
        ArrayList<TLRPC.Message> fresh = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            TLRPC.Message msg = messages.get(i);
            if (msg instanceof TLRPC.TL_messageEmpty || msg instanceof TLRPC.TL_messageService || msg.id == topMessage.id) {
                continue;
            }
            if (excludeKnown && containsServerId(msg.id)) {
                continue;
            }
            fresh.add(msg);
        }
        return fresh;
    }

    private boolean containsServerId(int id) {
        for (int i = 0; i < comments.size(); i++) {
            if (comments.get(i).id == id) {
                return true;
            }
        }
        return false;
    }

    /** Удаляет оптимистичную (локальную) копию своего комментария с таким же текстом. */
    private void removeOptimistic(String text) {
        for (int i = comments.size() - 1; i >= 0; i--) {
            TLRPC.Message c = comments.get(i);
            if (c.id >= LOCAL_ID_BASE && TextUtils.equals(c.message, text)) {
                comments.remove(i);
                return;
            }
        }
    }

    /* Живое обновление: пока шторка открыта, тянем самые свежие и добавляем сверху */

    public void startLive() {
        if (!liveActive) {
            liveActive = true;
            scheduleLive();
        }
    }

    public void stopLive() {
        liveActive = false;
        AndroidUtilities.cancelRunOnUIThread(pollRunnable);
    }

    private void scheduleLive() {
        if (liveActive) {
            AndroidUtilities.cancelRunOnUIThread(pollRunnable);
            AndroidUtilities.runOnUIThread(pollRunnable, 8000);
        }
    }

    private void pollNew() {
        if (!liveActive || topMessage == null || loading) {
            scheduleLive();
            return;
        }
        TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(discussionDialogId);
        req.msg_id = topMessage.id;
        req.offset_id = 0;   // самые свежие
        req.add_offset = 0;
        req.limit = PAGE;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                ArrayList<TLRPC.Message> fresh = new ArrayList<>();
                for (int i = 0; i < res.messages.size(); i++) {
                    TLRPC.Message msg = res.messages.get(i);
                    if (msg instanceof TLRPC.TL_messageEmpty || msg instanceof TLRPC.TL_messageService || msg.id == topMessage.id) {
                        continue;
                    }
                    if (msg.id > maxLoadedId && !containsServerId(msg.id)) {
                        fresh.add(msg);
                    }
                }
                if (!fresh.isEmpty()) {
                    Collections.sort(fresh, (a, b) -> a.id - b.id); // от старого к новому
                    long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                    for (TLRPC.Message m : fresh) {
                        // серверное эхо своего комментария заменяет оптимистичную копию,
                        // а не добавляется вторым
                        if (MessageObject.getFromChatId(m) == selfId) {
                            removeOptimistic(m.message);
                        }
                        comments.add(0, m); // сверху
                        maxLoadedId = Math.max(maxLoadedId, m.id);
                    }
                    delegate.onUpdated();
                }
            }
            scheduleLive();
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
        comments.add(0, local); // свой комментарий — сверху (newest-first)
        delegate.onUpdated();
        return true;
    }
}
