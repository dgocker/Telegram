package org.telegram.messenger.feed;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BaseController;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * Умная лента: собирает свежие посты каналов из подписок и ранжирует их.
 * Скор = рейтинг канала (взаимодействия с экспоненциальным затуханием)
 *      + свежесть поста + вовлечённость (просмотры/репосты/реакции)
 *      − сильный штраф за мьют.
 */
public class FeedController extends BaseController {

    public static final int INTERACTION_OPEN = 0;
    public static final int INTERACTION_REACTION = 1;
    public static final int INTERACTION_FORWARD = 2;

    private static final int MAX_CHANNELS = 32;
    private static final int POSTS_PER_CHANNEL = 8;
    private static final int MAX_POST_AGE_SECONDS = 3 * 24 * 60 * 60;
    private static final long RELOAD_INTERVAL_MS = 5 * 60 * 1000L;
    private static final double RATING_HALF_LIFE_DAYS = 7.0;
    private static final float MAX_RATING = 50f;

    private static final FeedController[] Instance = new FeedController[UserConfig.MAX_ACCOUNT_COUNT];

    public static FeedController getInstance(int num) {
        FeedController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (FeedController.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new FeedController(num);
                }
            }
        }
        return localInstance;
    }

    public FeedController(int num) {
        super(num);
    }

    public static class FeedPost {
        public long dialogId;
        public TLRPC.Chat chat;
        public MessageObject message;
        public ArrayList<MessageObject> album;
        public MessageObject.GroupedMessages groupedMessages;
        public float score;
        public boolean muted;

        public int getId() {
            return message.getId();
        }

        public String getText() {
            if (!TextUtils.isEmpty(message.messageOwner.message)) {
                return message.messageOwner.message;
            }
            if (album != null) {
                for (int i = 0; i < album.size(); i++) {
                    String caption = album.get(i).messageOwner.message;
                    if (!TextUtils.isEmpty(caption)) {
                        return caption;
                    }
                }
            }
            return "";
        }

        public int getViews() {
            int views = 0;
            if (album != null) {
                for (int i = 0; i < album.size(); i++) {
                    views = Math.max(views, album.get(i).messageOwner.views);
                }
            } else {
                views = message.messageOwner.views;
            }
            return views;
        }

        public int getForwards() {
            return message.messageOwner.forwards;
        }

        public int getReactionsCount() {
            int count = 0;
            TLRPC.Message owner = message.messageOwner;
            if (owner.reactions != null && owner.reactions.results != null) {
                for (int i = 0; i < owner.reactions.results.size(); i++) {
                    count += owner.reactions.results.get(i).count;
                }
            }
            return count;
        }
    }

    private final ArrayList<FeedPost> posts = new ArrayList<>();
    private boolean loading;
    private boolean loadedOnce;
    private long lastLoadTime;

    public ArrayList<FeedPost> getPosts() {
        return posts;
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean isLoadedOnce() {
        return loadedOnce;
    }

    public void loadFeed(boolean force) {
        if (loading) {
            return;
        }
        if (!force && loadedOnce && !posts.isEmpty() && System.currentTimeMillis() - lastLoadTime < RELOAD_INTERVAL_MS) {
            return;
        }

        final ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        final ArrayList<TLRPC.Dialog> channelDialogs = new ArrayList<>();
        for (int i = 0; i < dialogs.size(); i++) {
            TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog.id >= 0) {
                continue;
            }
            TLRPC.Chat chat = getMessagesController().getChat(-dialog.id);
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || chat.left || chat.kicked) {
                continue;
            }
            channelDialogs.add(dialog);
        }
        if (channelDialogs.size() > MAX_CHANNELS) {
            Collections.sort(channelDialogs, (a, b) -> Integer.compare(b.last_message_date, a.last_message_date));
            channelDialogs.subList(MAX_CHANNELS, channelDialogs.size()).clear();
        }
        if (channelDialogs.isEmpty()) {
            loadedOnce = true;
            lastLoadTime = System.currentTimeMillis();
            posts.clear();
            getNotificationCenter().postNotificationName(NotificationCenter.smartFeedDidLoad);
            return;
        }

        loading = true;
        final ArrayList<TLRPC.Message> allMessages = new ArrayList<>();
        final int[] pending = {channelDialogs.size()};

        for (int i = 0; i < channelDialogs.size(); i++) {
            final long dialogId = channelDialogs.get(i).id;
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = getMessagesController().getInputPeer(dialogId);
            req.limit = POSTS_PER_CHANNEL;
            req.offset_id = 0;
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (response instanceof TLRPC.messages_Messages) {
                        TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                        getMessagesController().putUsers(res.users, false);
                        getMessagesController().putChats(res.chats, false);
                        allMessages.addAll(res.messages);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                pending[0]--;
                if (pending[0] == 0) {
                    processLoadedMessages(allMessages);
                }
            }));
        }
    }

    private void processLoadedMessages(ArrayList<TLRPC.Message> messages) {
        try {
            final int now = getConnectionsManager().getCurrentTime();
            final HashMap<Long, FeedPost> byGroup = new HashMap<>();
            final ArrayList<FeedPost> newPosts = new ArrayList<>();

            for (int i = 0; i < messages.size(); i++) {
                TLRPC.Message msg = messages.get(i);
                if (msg instanceof TLRPC.TL_messageService || msg instanceof TLRPC.TL_messageEmpty) {
                    continue;
                }
                if (now - msg.date > MAX_POST_AGE_SECONDS) {
                    continue;
                }
                MessageObject messageObject = new MessageObject(currentAccount, msg, false, true);
                long dialogId = messageObject.getDialogId();
                TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
                if (chat == null) {
                    continue;
                }

                // в грид группируем только фото/видео-альбомы: у документов и музыки
                // GroupedMessages.calculate() не даёт осмысленной геометрии
                final boolean groupableMedia = msg.grouped_id != 0 && (messageObject.isVideo() || messageObject.isPhoto());
                if (groupableMedia) {
                    FeedPost existing = byGroup.get(msg.grouped_id);
                    if (existing != null) {
                        existing.album.add(messageObject);
                        continue;
                    }
                }

                FeedPost post = new FeedPost();
                post.dialogId = dialogId;
                post.chat = chat;
                post.message = messageObject;
                if (groupableMedia) {
                    post.album = new ArrayList<>();
                    post.album.add(messageObject);
                    byGroup.put(msg.grouped_id, post);
                }
                newPosts.add(post);
            }

            for (FeedPost post : newPosts) {
                if (post.album != null && post.album.size() > 1) {
                    Collections.sort(post.album, (a, b) -> a.getId() - b.getId());
                    post.message = post.album.get(0);
                    MessageObject.GroupedMessages group = new MessageObject.GroupedMessages();
                    group.groupId = post.message.messageOwner.grouped_id;
                    group.messages.addAll(post.album);
                    group.calculate();
                    post.groupedMessages = group;
                }
                // пустые посты (ни текста, ни медиа) не показываем
                if (TextUtils.isEmpty(post.getText()) && post.message.messageOwner.media == null) {
                    post.score = Float.NEGATIVE_INFINITY;
                    continue;
                }
                post.muted = getMessagesController().isDialogMuted(post.dialogId, 0);
                post.score = calculateScore(post, now);
            }

            for (int i = newPosts.size() - 1; i >= 0; i--) {
                if (newPosts.get(i).score == Float.NEGATIVE_INFINITY) {
                    newPosts.remove(i);
                }
            }
            Collections.sort(newPosts, (a, b) -> {
                int cmp = Float.compare(b.score, a.score);
                if (cmp != 0) {
                    return cmp;
                }
                return Integer.compare(b.message.messageOwner.date, a.message.messageOwner.date);
            });

            posts.clear();
            posts.addAll(newPosts);
            loadedOnce = true;
            lastLoadTime = System.currentTimeMillis();
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            loading = false;
            getNotificationCenter().postNotificationName(NotificationCenter.smartFeedDidLoad);
        }
    }

    private float calculateScore(FeedPost post, int now) {
        final float ageHours = Math.max(0, now - post.message.messageOwner.date) / 3600f;
        final float freshness = 4f * (float) Math.exp(-ageHours / 24f);

        float engagement = 0.6f * (float) Math.log10(1 + post.getViews());
        engagement += 1.2f * (float) Math.log10(1 + post.getForwards());
        engagement += 1.5f * (float) Math.log10(1 + post.getReactionsCount());

        final float rating = getChannelRating(post.dialogId);
        float score = 2f * rating + freshness + engagement;
        if (post.muted) {
            score -= 10f;
        }
        return score;
    }

    /* Рейтинг каналов: копится от взаимодействий, затухает с полураспадом в неделю. */

    private SharedPreferences getRatingPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("feed_rank" + currentAccount, 0);
    }

    public float getChannelRating(long dialogId) {
        SharedPreferences prefs = getRatingPrefs();
        float rating = prefs.getFloat("r_" + dialogId, 0);
        if (rating <= 0) {
            return 0;
        }
        long updated = prefs.getLong("t_" + dialogId, 0);
        double days = Math.max(0, System.currentTimeMillis() - updated) / 86400000.0;
        return rating * (float) Math.pow(0.5, days / RATING_HALF_LIFE_DAYS);
    }

    private void addRating(long dialogId, float delta) {
        float rating = Math.min(MAX_RATING, getChannelRating(dialogId) + delta);
        getRatingPrefs().edit()
            .putFloat("r_" + dialogId, rating)
            .putLong("t_" + dialogId, System.currentTimeMillis())
            .apply();
    }

    private boolean isTrackedChannel(long dialogId) {
        if (dialogId >= 0) {
            return false;
        }
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        return chat != null && ChatObject.isChannelAndNotMegaGroup(chat);
    }

    public void trackInteraction(long dialogId, int type) {
        if (!isTrackedChannel(dialogId)) {
            return;
        }
        switch (type) {
            case INTERACTION_OPEN:
                addRating(dialogId, 1f);
                break;
            case INTERACTION_REACTION:
                addRating(dialogId, 2f);
                break;
            case INTERACTION_FORWARD:
                addRating(dialogId, 2.5f);
                break;
        }
    }

    public void trackReadTime(long dialogId, long millis) {
        if (!isTrackedChannel(dialogId) || millis < 3000) {
            return;
        }
        addRating(dialogId, Math.min(2f, millis / 1000f * 0.03f));
    }
}
