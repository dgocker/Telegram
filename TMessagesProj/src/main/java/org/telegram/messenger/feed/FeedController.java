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
    public static final int INTERACTION_COMMENTS_OPEN = 3;
    public static final int INTERACTION_SHARE = 4;
    public static final int INTERACTION_FULL_POST_OPEN = 5;

    private static final int MAX_CHANNELS = 32;
    // 15, чтобы альбом (до 10 медиа) с подписью не обрезался лимитом и подпись попадала в группу
    private static final int POSTS_PER_CHANNEL = 15;
    private static final double RATING_HALF_LIFE_DAYS = 7.0;
    private static final float MAX_RATING = 50f;
    private static final float MIN_RATING = -30f;

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

        /** Медиа, которые реально можно показать в ленте (фото/видео с превью). */
        public ArrayList<MessageObject> renderableMedia() {
            ArrayList<MessageObject> result = new ArrayList<>();
            ArrayList<MessageObject> source = album != null ? album : new ArrayList<>(Collections.singletonList(message));
            for (int i = 0; i < source.size(); i++) {
                MessageObject mo = source.get(i);
                if (mo.isVideo() || mo.isPhoto() || (mo.photoThumbs != null && !mo.photoThumbs.isEmpty())) {
                    result.add(mo);
                }
            }
            return result;
        }

        public boolean hasMedia() {
            return !renderableMedia().isEmpty();
        }

        /** Сообщение альбома, у которого есть инфо о комментариях (обычно не первое). */
        public MessageObject commentsMessage() {
            if (message.messageOwner.replies != null && message.messageOwner.replies.comments) {
                return message;
            }
            if (album != null) {
                for (int i = 0; i < album.size(); i++) {
                    TLRPC.MessageReplies r = album.get(i).messageOwner.replies;
                    if (r != null && r.comments) {
                        return album.get(i);
                    }
                }
            }
            return message;
        }

        public boolean commentsEnabled() {
            TLRPC.MessageReplies r = commentsMessage().messageOwner.replies;
            return r != null && r.comments;
        }

        public int commentsCount() {
            TLRPC.MessageReplies r = commentsMessage().messageOwner.replies;
            return r != null ? r.replies : 0;
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

    /* Бесконечная лента: показанное (posts, append-only) + пул кандидатов + курсоры каналов */

    private static final int BATCH = 12;             // сколько постов выдаём за раз
    private static final int POOL_LOW = 25;          // ниже — догружаем старые посты каналов
    private static final int PAGE_MAX_AGE_SECONDS = 45 * 24 * 60 * 60; // не глубже ~45 дней

    private final ArrayList<FeedPost> posts = new ArrayList<>();
    private final ArrayList<FeedPost> candidatePool = new ArrayList<>();
    private final ArrayList<Long> channelIds = new ArrayList<>();
    private final HashMap<Long, Integer> oldestFetchedId = new HashMap<>();
    private final java.util.HashSet<Long> channelExhausted = new java.util.HashSet<>();
    private final java.util.HashSet<Long> feedKeys = new java.util.HashSet<>();      // O(1) дедуп (пул+показанные)
    private final HashMap<Long, FeedPost> poolGroups = new HashMap<>();              // grouped_id → пост (склейка альбомов между страницами)

    private boolean loading;
    private boolean loadedOnce;
    private boolean exhaustedAll;
    private long lastRefreshTime;

    public ArrayList<FeedPost> getPosts() {
        return posts;
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean isLoadedOnce() {
        return loadedOnce;
    }

    public boolean isExhausted() {
        return exhaustedAll;
    }

    private void refreshChannelList() {
        channelIds.clear();
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
        for (TLRPC.Dialog d : channelDialogs) {
            channelIds.add(d.id);
        }
    }

    /** Первичная загрузка (или обновление пустой ленты). */
    public void loadFeed(boolean force) {
        if (loading) {
            return;
        }
        // показанную ленту не пересобираем при возврате — иначе теряются порядок и позиция
        if (!force && loadedOnce && !posts.isEmpty()) {
            return;
        }
        refreshChannelList();
        if (channelIds.isEmpty()) {
            loadedOnce = true;
            exhaustedAll = true;
            posts.clear();
            getNotificationCenter().postNotificationName(NotificationCenter.smartFeedDidLoad);
            return;
        }
        posts.clear();
        candidatePool.clear();
        feedKeys.clear();
        poolGroups.clear();
        oldestFetchedId.clear();
        channelExhausted.clear();
        exhaustedAll = false;
        // грузим самые свежие посты каждого канала
        fetchPages(true, () -> {
            try {
                placeNextBatch();
                loadedOnce = true;
                lastRefreshTime = System.currentTimeMillis();
                checkExhaustion();
            } finally {
                loading = false;
            }
            getNotificationCenter().postNotificationName(NotificationCenter.smartFeedDidLoad);
            // первая загрузка ничего не разместила (всё просмотрено) — идём глубже
            if (posts.isEmpty() && !exhaustedAll) {
                loadMore();
            }
        });
    }

    /** Догрузка при приближении к концу ленты. */
    public void loadMore() {
        if (loading || exhaustedAll) {
            return;
        }
        if (countPlaceable() >= BATCH) {
            placeNextBatch();
            checkExhaustion();
            getNotificationCenter().postNotificationName(NotificationCenter.smartFeedDidLoad);
            return;
        }
        if (allChannelsExhausted()) {
            placeNextBatch();
            checkExhaustion();
            getNotificationCenter().postNotificationName(NotificationCenter.smartFeedDidLoad);
            return;
        }
        // пул иссякает — тянем более старые посты каналов
        fetchPages(false, () -> {
            try {
                placeNextBatch();
                checkExhaustion();
            } finally {
                loading = false;
            }
            getNotificationCenter().postNotificationName(NotificationCenter.smartFeedDidLoad);
            // лента ещё пуста (всё просмотрено), но старое не кончилось — углубляемся дальше
            if (posts.isEmpty() && !exhaustedAll) {
                loadMore();
            }
        });
    }

    /** Лента исчерпана: нечего разместить, пул пуст и все каналы отдали всё. */
    private void checkExhaustion() {
        if (countPlaceable() == 0 && allChannelsExhausted()) {
            exhaustedAll = true;
        }
    }

    private boolean allChannelsExhausted() {
        return channelExhausted.size() >= channelIds.size();
    }

    private int countPlaceable() {
        int c = 0;
        for (FeedPost p : candidatePool) {
            if (!isPostSeen(p.dialogId, p.getId())) {
                c++;
            }
        }
        return c;
    }

    /** Запросить по странице у каждого канала (initial=свежие, иначе — старее курсора). */
    private void fetchPages(boolean initial, Runnable onComplete) {
        final ArrayList<Long> targets = new ArrayList<>();
        for (Long id : channelIds) {
            if (!initial && channelExhausted.contains(id)) {
                continue;
            }
            targets.add(id);
        }
        if (targets.isEmpty()) {
            onComplete.run();
            return;
        }
        loading = true;
        final ArrayList<TLRPC.Message> collected = new ArrayList<>();
        final int[] pending = {targets.size()};
        final Runnable[] onOne = new Runnable[1];
        onOne[0] = () -> {
            pending[0]--;
            if (pending[0] == 0) {
                buildCandidates(collected);
                onComplete.run();
            }
        };
        for (Long dialogId : targets) {
            try {
                TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
                req.peer = getMessagesController().getInputPeer(dialogId);
                req.limit = POSTS_PER_CHANNEL;
                req.offset_id = initial ? 0 : oldestFetchedId.getOrDefault(dialogId, 0);
                getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    try {
                        if (response instanceof TLRPC.messages_Messages) {
                            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                            getMessagesController().putUsers(res.users, false);
                            getMessagesController().putChats(res.chats, false);
                            if (res.messages.isEmpty()) {
                                channelExhausted.add(dialogId);
                            } else {
                                collected.addAll(res.messages);
                            }
                        } else {
                            channelExhausted.add(dialogId);
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                    onOne[0].run();
                }));
            } catch (Throwable e) {
                // синхронная ошибка отправки не должна подвесить pending → loading залипнет
                FileLog.e(e);
                channelExhausted.add(dialogId);
                onOne[0].run();
            }
        }
    }

    /** Группируем альбомы (в т.ч. между страницами), фильтруем, скорим, кладём в пул. */
    private void buildCandidates(ArrayList<TLRPC.Message> messages) {
        try {
            final int now = getConnectionsManager().getCurrentTime();
            final ArrayList<FeedPost> fresh = new ArrayList<>();

            for (int i = 0; i < messages.size(); i++) {
                TLRPC.Message msg = messages.get(i);
                // канал сообщения (peer_id есть и у service/empty)
                long dialogId = msg.peer_id != null && msg.peer_id.channel_id != 0 ? -msg.peer_id.channel_id : 0;
                // курсор канала двигаем ДО любых skip (иначе на удалённых постах
                // курсор стоит и та же страница тянется бесконечно)
                if (dialogId != 0) {
                    Integer prevOldest = oldestFetchedId.get(dialogId);
                    if (prevOldest == null || msg.id < prevOldest) {
                        oldestFetchedId.put(dialogId, msg.id);
                    }
                }
                if (msg instanceof TLRPC.TL_messageService || msg instanceof TLRPC.TL_messageEmpty || dialogId == 0) {
                    continue;
                }
                MessageObject messageObject = new MessageObject(currentAccount, msg, false, true);
                TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
                if (chat == null) {
                    continue;
                }
                if (now - msg.date > PAGE_MAX_AGE_SECONDS) {
                    channelExhausted.add(dialogId); // ушли слишком глубоко — стоп по каналу
                    continue;
                }

                // группируем ВЕСЬ альбом по grouped_id (в т.ч. spoiler/ограниченные/документы) —
                // иначе теряются элементы и сообщение с подписью; показываем потом только фото/видео
                final boolean grouped = msg.grouped_id != 0;
                if (grouped) {
                    FeedPost existing = poolGroups.get(msg.grouped_id);
                    if (existing != null) {
                        existing.album.add(messageObject);
                        rebuildAlbum(existing);
                        continue;
                    }
                }
                FeedPost post = new FeedPost();
                post.dialogId = dialogId;
                post.chat = chat;
                post.message = messageObject;
                if (grouped) {
                    post.album = new ArrayList<>();
                    post.album.add(messageObject);
                    poolGroups.put(msg.grouped_id, post);
                }
                fresh.add(post);
            }

            for (FeedPost post : fresh) {
                rebuildAlbum(post);
                if (TextUtils.isEmpty(post.getText()) && !post.hasMedia()) {
                    continue; // нечего показать
                }
                if (isPostSeen(post.dialogId, post.getId())) {
                    continue; // просмотренное
                }
                long key = feedKey(post.dialogId, post.getId());
                if (feedKeys.contains(key)) {
                    continue; // дубликат (в пуле или уже показан)
                }
                post.muted = getMessagesController().isDialogMuted(post.dialogId, 0);
                post.score = calculateScore(post, now);
                candidatePool.add(post);
                feedKeys.add(key);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void rebuildAlbum(FeedPost post) {
        if (post.album != null && post.album.size() > 1) {
            Collections.sort(post.album, (a, b) -> a.getId() - b.getId());
            // главное сообщение и грид-геометрия — из показываемых (фото/видео);
            // документы/аудио остаются в album только чтобы не потерять их подпись
            ArrayList<MessageObject> shown = post.renderableMedia();
            post.message = shown.isEmpty() ? post.album.get(0) : shown.get(0);
            if (shown.size() > 1) {
                MessageObject.GroupedMessages group = new MessageObject.GroupedMessages();
                group.groupId = post.message.messageOwner.grouped_id;
                group.messages.addAll(shown);
                group.calculate();
                post.groupedMessages = group;
            } else {
                post.groupedMessages = null;
            }
        }
    }

    private static long feedKey(long dialogId, int messageId) {
        return dialogId * 1_000_000_000L + messageId;
    }

    /** Выбрать из пула порцию: лучшие по score, без повтора канала в окне, с квотой exploration. */
    private void placeNextBatch() {
        // убираем из пула просмотренное (мог отметиться после dwell) — bounds рост пула
        for (int i = candidatePool.size() - 1; i >= 0; i--) {
            FeedPost c = candidatePool.get(i);
            if (isPostSeen(c.dialogId, c.getId())) {
                candidatePool.remove(i);
                feedKeys.remove(feedKey(c.dialogId, c.getId()));
            }
        }
        candidatePool.sort((a, b) -> Float.compare(b.score, a.score));
        final int window = 4;               // не повторять канал 4 поста подряд
        final int explorationEvery = 6;     // каждый 6-й — «на пробу» из незнакомого канала
        int placed = 0;
        while (placed < BATCH && !candidatePool.isEmpty()) {
            FeedPost pick = null;
            boolean wantExploration = ((posts.size()) % explorationEvery) == explorationEvery - 1;
            // первый проход: с учётом diversity (и exploration-предпочтения)
            for (FeedPost cand : candidatePool) {
                if (isPostSeen(cand.dialogId, cand.getId())) {
                    continue;
                }
                if (recentlyPlacedChannel(cand.dialogId, window)) {
                    continue;
                }
                if (wantExploration && hasChannelHistory(cand.dialogId)) {
                    continue;
                }
                pick = cand;
                break;
            }
            // второй проход: только diversity
            if (pick == null) {
                for (FeedPost cand : candidatePool) {
                    if (isPostSeen(cand.dialogId, cand.getId())) {
                        continue;
                    }
                    if (recentlyPlacedChannel(cand.dialogId, window)) {
                        continue;
                    }
                    pick = cand;
                    break;
                }
            }
            // третий проход: что осталось (не seen)
            if (pick == null) {
                for (FeedPost cand : candidatePool) {
                    if (!isPostSeen(cand.dialogId, cand.getId())) {
                        pick = cand;
                        break;
                    }
                }
            }
            if (pick == null) {
                break; // всё оставшееся — seen
            }
            candidatePool.remove(pick);
            posts.add(pick);
            placed++;
        }
    }

    private boolean recentlyPlacedChannel(long dialogId, int window) {
        int from = Math.max(0, posts.size() - window);
        for (int i = posts.size() - 1; i >= from; i--) {
            if (posts.get(i).dialogId == dialogId) {
                return true;
            }
        }
        return false;
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
        // exploration: каналы, с которыми ещё не взаимодействовали, подмешиваем выше
        if (!hasChannelHistory(post.dialogId)) {
            score += 1.5f;
        }
        // предпочтение по типу контента (видео/фото/текст)
        score += getContentTypeBias(contentTypeOf(post));
        return score;
    }

    /* Предпочтение по типу контента: копим отклик отдельно на видео / фото / текст */

    public static final int TYPE_TEXT = 0;
    public static final int TYPE_PHOTO = 1;
    public static final int TYPE_VIDEO = 2;

    public static int contentTypeOf(FeedPost post) {
        if (post.message.isVideo()) {
            return TYPE_VIDEO;
        }
        if (post.message.photoThumbs != null && !post.message.photoThumbs.isEmpty()) {
            return TYPE_PHOTO;
        }
        return TYPE_TEXT;
    }

    private float getTypeRating(int type) {
        SharedPreferences prefs = getRatingPrefs();
        float rating = prefs.getFloat("type_" + type, 0);
        if (rating == 0) {
            return 0;
        }
        long updated = prefs.getLong("typet_" + type, 0);
        double days = Math.max(0, System.currentTimeMillis() - updated) / 86400000.0;
        return rating * (float) Math.pow(0.5, days / RATING_HALF_LIFE_DAYS);
    }

    private void addTypeRating(int type, float delta) {
        float rating = Math.max(-20f, Math.min(30f, getTypeRating(type) + delta));
        getRatingPrefs().edit()
            .putFloat("type_" + type, rating)
            .putLong("typet_" + type, System.currentTimeMillis())
            .apply();
    }

    /** Смещение скора: любимый тип выше, нелюбимый ниже, относительно среднего по типам. */
    private float getContentTypeBias(int type) {
        float avg = (getTypeRating(TYPE_TEXT) + getTypeRating(TYPE_PHOTO) + getTypeRating(TYPE_VIDEO)) / 3f;
        return 0.15f * (getTypeRating(type) - avg);
    }

    /* Рейтинг каналов: копится от взаимодействий, затухает с полураспадом в неделю. */

    private SharedPreferences getRatingPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("feed_rank" + currentAccount, 0);
    }

    public float getChannelRating(long dialogId) {
        SharedPreferences prefs = getRatingPrefs();
        float rating = prefs.getFloat("r_" + dialogId, 0);
        if (rating == 0) {
            return 0;
        }
        long updated = prefs.getLong("t_" + dialogId, 0);
        double days = Math.max(0, System.currentTimeMillis() - updated) / 86400000.0;
        // затухает к нулю и позитив, и негатив (дизлайки со временем прощаются)
        return rating * (float) Math.pow(0.5, days / RATING_HALF_LIFE_DAYS);
    }

    public boolean hasChannelHistory(long dialogId) {
        return getRatingPrefs().contains("r_" + dialogId);
    }

    private void addRating(long dialogId, float delta) {
        float rating = Math.max(MIN_RATING, Math.min(MAX_RATING, getChannelRating(dialogId) + delta));
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
            case INTERACTION_SHARE:
                addRating(dialogId, 2.5f);
                break;
            case INTERACTION_COMMENTS_OPEN:
                addRating(dialogId, 1.5f);
                break;
            case INTERACTION_FULL_POST_OPEN:
                addRating(dialogId, 1f);
                break;
        }
    }

    public void trackReadTime(long dialogId, long millis) {
        if (!isTrackedChannel(dialogId) || millis < 3000) {
            return;
        }
        addRating(dialogId, Math.min(2f, millis / 1000f * 0.03f));
    }

    /* TikTok-сигналы уровня поста: сколько секунд смотрели, был ли быстрый свайп-скип */

    public void trackPostDwell(long dialogId, int messageId, long millis, boolean videoCompleted) {
        trackPostDwell(dialogId, messageId, millis, videoCompleted, TYPE_TEXT, null);
    }

    public void trackPostDwell(long dialogId, int messageId, long millis, boolean videoCompleted, int contentType, String title) {
        if (!isTrackedChannel(dialogId)) {
            return;
        }
        markPostSeen(dialogId, messageId, contentType, title);
        if (millis < 1200) {
            addRating(dialogId, -0.2f);       // мгновенный свайп = «не интересно»
            addTypeRating(contentType, -0.15f);
        } else if (millis > 4000) {
            float boost = Math.min(1.2f, (millis - 4000) / 1000f * 0.08f);
            addRating(dialogId, boost);
            addTypeRating(contentType, boost * 0.5f);
        }
        if (videoCompleted) {
            addRating(dialogId, 0.6f);
            addTypeRating(contentType, 0.4f);
        }
    }

    /* Дизлайк: сильный минус каналу (замена скрытию каналов) */

    public void trackDislike(long dialogId, int messageId) {
        trackDislike(dialogId, messageId, TYPE_TEXT);
    }

    public void trackDislike(long dialogId, int messageId, int contentType) {
        if (!isTrackedChannel(dialogId)) {
            return;
        }
        markPostSeen(dialogId, messageId, contentType, null);
        addRating(dialogId, -6f);
        addTypeRating(contentType, -1.5f);
        int count = getRatingPrefs().getInt("disc_" + dialogId, 0) + 1;
        getRatingPrefs().edit().putInt("disc_" + dialogId, count).apply();
    }

    public int getDislikeCount(long dialogId) {
        return getRatingPrefs().getInt("disc_" + dialogId, 0);
    }

    /* История просмотренного: постоянная, просмотренное не показываем повторно */

    private static final int MAX_HISTORY = 3000;

    public static class HistoryEntry {
        public long dialogId;
        public int messageId;
        public int contentType;
        public String title;
        public long seenAt;
    }

    private java.util.LinkedHashSet<String> seenPosts;

    private SharedPreferences getHistoryPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("feed_history" + currentAccount, 0);
    }

    private java.util.LinkedHashSet<String> getSeenPosts() {
        if (seenPosts == null) {
            seenPosts = new java.util.LinkedHashSet<>();
            String stored = getHistoryPrefs().getString("seen_ids", "");
            if (!stored.isEmpty()) {
                Collections.addAll(seenPosts, stored.split(","));
            }
        }
        return seenPosts;
    }

    public void markPostSeen(long dialogId, int messageId) {
        markPostSeen(dialogId, messageId, TYPE_TEXT, null);
    }

    public void markPostSeen(long dialogId, int messageId, int contentType, String title) {
        java.util.LinkedHashSet<String> seen = getSeenPosts();
        String key = dialogId + "_" + messageId;
        if (!seen.add(key)) {
            return;
        }
        while (seen.size() > MAX_HISTORY) {
            seen.remove(seen.iterator().next());
        }
        SharedPreferences prefs = getHistoryPrefs();
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("seen_ids", TextUtils.join(",", seen));
        // детали для экрана истории (title|type|time)
        String detail = (title == null ? "" : title.replace("\n", " ").replace("\u0001", " ")) + "\u0001" + contentType + "\u0001" + System.currentTimeMillis();
        editor.putString("h_" + key, detail);
        editor.apply();
    }

    public boolean isPostSeen(long dialogId, int messageId) {
        return getSeenPosts().contains(dialogId + "_" + messageId);
    }

    /** История для экрана «Просмотренное», новые сверху. */
    public ArrayList<HistoryEntry> getHistory() {
        java.util.LinkedHashSet<String> seen = getSeenPosts();
        SharedPreferences prefs = getHistoryPrefs();
        ArrayList<HistoryEntry> result = new ArrayList<>();
        for (String key : seen) {
            String detail = prefs.getString("h_" + key, null);
            HistoryEntry entry = new HistoryEntry();
            int us = key.indexOf('_');
            if (us <= 0) {
                continue;
            }
            try {
                entry.dialogId = Long.parseLong(key.substring(0, us));
                entry.messageId = Integer.parseInt(key.substring(us + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (detail != null) {
                String[] parts = detail.split("\u0001", -1);
                entry.title = parts.length > 0 ? parts[0] : "";
                try {
                    entry.contentType = parts.length > 1 ? Integer.parseInt(parts[1]) : TYPE_TEXT;
                    entry.seenAt = parts.length > 2 ? Long.parseLong(parts[2]) : 0;
                } catch (NumberFormatException ignore) {
                }
            }
            result.add(entry);
        }
        Collections.reverse(result);
        return result;
    }

    public void clearHistory() {
        getSeenPosts().clear();
        getHistoryPrefs().edit().clear().apply();
    }
}
