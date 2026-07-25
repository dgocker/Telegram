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
        /** Подпись из отдельного соседнего текстового сообщения (у альбома нет своей). */
        public String externalCaption;

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

        /** Сообщение, из которого взят текст getText() (нужно для entities-ссылок); null — текст из externalCaption. */
        public MessageObject textMessage() {
            if (!TextUtils.isEmpty(message.messageOwner.message)) {
                return message;
            }
            if (album != null) {
                for (int i = 0; i < album.size(); i++) {
                    if (!TextUtils.isEmpty(album.get(i).messageOwner.message)) {
                        return album.get(i);
                    }
                }
            }
            return null;
        }

        public String getText() {
            MessageObject textSource = textMessage();
            if (textSource != null) {
                return textSource.messageOwner.message;
            }
            if (!TextUtils.isEmpty(externalCaption)) {
                return externalCaption;
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
            // у альбома forwards лежат на одном (обычно подписанном) сообщении и относятся
            // ко всему посту — берём max, суммирование задвоило бы один и тот же репост
            int forwards = message.messageOwner.forwards;
            if (album != null) {
                for (int i = 0; i < album.size(); i++) {
                    forwards = Math.max(forwards, album.get(i).messageOwner.forwards);
                }
            }
            return forwards;
        }

        public int getReactionsCount() {
            // реакции у альбома могут висеть на разных сообщениях (обычно на подписанном) — суммируем
            int count = 0;
            if (album != null) {
                for (int i = 0; i < album.size(); i++) {
                    count += reactionsOf(album.get(i).messageOwner);
                }
            } else {
                count = reactionsOf(message.messageOwner);
            }
            return count;
        }

        private static int reactionsOf(TLRPC.Message owner) {
            int count = 0;
            if (owner.reactions != null && owner.reactions.results != null) {
                for (int i = 0; i < owner.reactions.results.size(); i++) {
                    count += owner.reactions.results.get(i).count;
                }
            }
            return count;
        }

        /** Битовая маска модальностей (1 << MOD_*): у поста их может быть несколько (фото+текст и т.д.). */
        public int modalityMask() {
            int mask = 0;
            if (getText().trim().length() >= MEANINGFUL_TEXT_MIN_CHARS) {
                mask |= 1 << MOD_TEXT;
            }
            ArrayList<MessageObject> media = renderableMedia();
            for (int i = 0; i < media.size(); i++) {
                mask |= media.get(i).isVideo() ? (1 << MOD_VIDEO) : (1 << MOD_PHOTO);
            }
            if (media.size() > 1) {
                mask |= 1 << MOD_ALBUM;
            }
            return mask;
        }
    }

    /* Бесконечная лента: показанное (posts, append-only) + пул кандидатов + курсоры каналов */

    private static final int BATCH = 12;             // сколько постов выдаём за раз
    private static final int POOL_LOW = 25;          // ниже — догружаем старые посты каналов
    private static final int PAGE_MAX_AGE_SECONDS = 45 * 24 * 60 * 60; // не глубже ~45 дней
    private static final long TOPUP_INTERVAL = 5 * 60 * 1000L; // как часто подтягиваем свежие посты

    private final ArrayList<FeedPost> posts = new ArrayList<>();
    private final ArrayList<FeedPost> candidatePool = new ArrayList<>();
    private final ArrayList<Long> channelIds = new ArrayList<>();
    private final HashMap<Long, Integer> oldestFetchedId = new HashMap<>();
    private final java.util.HashSet<Long> channelExhausted = new java.util.HashSet<>();
    private final java.util.HashSet<Long> feedKeys = new java.util.HashSet<>();      // O(1) дедуп (пул+показанные)
    // ponytail: getId() альбома дрейфует при in-place склейке хвоста (message = нижний
    // renderable, хвост приходит с меньшими id), поэтому ключ в feedKeys устаревает —
    // мелкая утечка множества до перезагрузки ленты, дублей не даёт (poolGroups держит
    // один FeedPost на grouped_id). Seen-ключи стабильны: гейт albumComplete() не даёт
    // показать/отметить пост до полной склейки, а после неё id уже не меняется.
    // Если всё же появятся повторы альбомов — ключевать дедуп/seen по grouped_id
    // с миграцией seen-ключей "dialogId_messageId" и правкой FeedHistoryActivity
    // (там навигация в чат идёт по messageId).
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
        // курсоры/флаги отвалившихся каналов иначе перевешивают allChannelsExhausted()
        channelExhausted.retainAll(channelIds);
        oldestFetchedId.keySet().retainAll(channelIds);
    }

    /** Первичная загрузка (или обновление пустой ленты). */
    public void loadFeed(boolean force) {
        if (loading) {
            return;
        }
        // показанную ленту не пересобираем при возврате — иначе теряются порядок и позиция;
        // но свежие посты в пул подтянуть надо, иначе в живом процессе лента их не увидит
        if (!force && loadedOnce && !posts.isEmpty()) {
            if (isTopUpDue()) {
                topUpFresh();
            }
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

    private boolean isTopUpDue() {
        return loadedOnce && System.currentTimeMillis() - lastRefreshTime >= TOPUP_INTERVAL;
    }

    /**
     * Подтянуть свежие посты каналов в пул, НЕ пересобирая показанную ленту.
     * fetchPages(true) идёт с offset_id=0, дедуп по feedKeys отсекает уже известное,
     * курсор пагинации не сдвигается (oldestFetchedId держит минимум по каналу).
     * Без этого новые посты не попадали в ленту, пока жив процесс: скролл тянет только старое.
     */
    private void topUpFresh() {
        refreshChannelList(); // канал мог добавиться/отвалиться
        if (channelIds.isEmpty()) {
            return;
        }
        fetchPages(true, () -> {
            boolean placed = false;
            try {
                lastRefreshTime = System.currentTimeMillis();
                if (countPlaceable() > 0) {
                    exhaustedAll = false; // «лента кончилась» снимаем — есть что показать
                    placeNextBatch();     // append в хвост, позиция и порядок не сбиваются
                    placed = true;
                }
            } finally {
                loading = false;
            }
            getNotificationCenter().postNotificationName(NotificationCenter.smartFeedDidLoad);
            if (!placed) {
                loadMore(); // свежего нет — обычная догрузка старого, гейт по времени уже закрыт
            }
        });
    }

    /** Догрузка при приближении к концу ленты. */
    public void loadMore() {
        if (loading) {
            return;
        }
        // давно не обновлялись — сперва свежее, иначе лента в живом процессе копает только вглубь
        if (isTopUpDue()) {
            topUpFresh();
            return;
        }
        if (exhaustedAll) {
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

    /**
     * Альбом «полон», когда мы уже подтянули сообщение СТАРШЕ его нижнего элемента
     * (значит ниже границы фетча members этого альбома нет) либо канал исчерпан.
     * Иначе альбом упирается в границу страницы getHistory (limit=POSTS_PER_CHANNEL)
     * и может быть обрезан — не показываем, пока пагинация не догрузит хвост
     * (склейка идёт in-place через poolGroups). Так лечится потеря части фото,
     * подписи и комментариев у больших альбомов.
     * ponytail: полный альбом, случайно вставший ровно на границе (albumMin==boundary),
     * отложится на 1 цикл фетча лишним — приемлемо, само-лечится следующей догрузкой.
     */
    private boolean albumComplete(FeedPost p) {
        if (p.album == null) {
            return true; // одиночное медиа — не альбом
        }
        if (channelExhausted.contains(p.dialogId)) {
            return true; // старее уже не будет — берём как есть (анти-залипание)
        }
        Integer boundary = oldestFetchedId.get(p.dialogId);
        if (boundary == null) {
            return true;
        }
        int albumMin = Integer.MAX_VALUE;
        for (int i = 0; i < p.album.size(); i++) {
            albumMin = Math.min(albumMin, p.album.get(i).getId());
        }
        return albumMin > boundary; // ниже альбома есть ещё сообщения → альбом целиком
    }

    private int countPlaceable() {
        int c = 0;
        for (FeedPost p : candidatePool) {
            if (!isPostSeen(p.dialogId, p.getId()) && albumComplete(p)) {
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

            // сперва склеиваем альбомы, затем подхватываем отдельные текстовые сообщения-подписи
            for (FeedPost post : fresh) {
                rebuildAlbum(post);
            }
            mergeSeparateCaptions(fresh);

            for (FeedPost post : fresh) {
                if (post == null) {
                    continue; // текст ушёл подписью к соседнему альбому — отдельной карточкой не показываем
                }
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

    /** Медиа и его описание нередко приходят двумя сообщениями — такой зазор считаем «одним постом». */
    private static final int CAPTION_LINK_MAX_GAP_SECONDS = 90;

    /**
     * Некоторые каналы постят альбом (часто без подписи) и описание отдельным
     * текстовым сообщением. Привязываем такой текст к ближайшему по времени
     * медиа-посту того же канала без своей подписи, а отдельную карточку убираем.
     */
    private void mergeSeparateCaptions(ArrayList<FeedPost> fresh) {
        for (int i = 0; i < fresh.size(); i++) {
            FeedPost text = fresh.get(i);
            if (text == null || text.hasMedia() || TextUtils.isEmpty(text.getText())) {
                continue; // кандидат-подпись — только текст, без медиа
            }
            FeedPost target = findCaptionTarget(fresh, i, text);
            if (target != null) {
                target.externalCaption = text.getText();
                fresh.set(i, null); // отдельную текстовую карточку больше не показываем
            }
        }
    }

    private FeedPost findCaptionTarget(ArrayList<FeedPost> fresh, int textIndex, FeedPost text) {
        final long channel = text.dialogId;
        final int textDate = text.message.messageOwner.date;
        FeedPost best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int j = 0; j < fresh.size(); j++) {
            if (j == textIndex) {
                continue;
            }
            FeedPost cand = fresh.get(j);
            if (cand == null || cand.dialogId != channel) {
                continue;
            }
            if (!cand.hasMedia() || !TextUtils.isEmpty(cand.getText())) {
                continue; // цель — медиа-пост без собственной подписи
            }
            int gap = Math.abs(cand.message.messageOwner.date - textDate);
            if (gap > CAPTION_LINK_MAX_GAP_SECONDS) {
                continue;
            }
            if (gap < bestGap) {
                bestGap = gap;
                best = cand;
            }
        }
        return best;
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
                if (!albumComplete(cand)) {
                    continue; // альбом ещё не догружен целиком — не показываем обрезанным
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
                    if (!albumComplete(cand)) {
                        continue; // альбом ещё не догружен целиком
                    }
                    pick = cand;
                    break;
                }
            }
            // третий проход: что осталось (не seen)
            if (pick == null) {
                for (FeedPost cand : candidatePool) {
                    if (!isPostSeen(cand.dialogId, cand.getId()) && albumComplete(cand)) {
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
        // предпочтения по модальностям (текст/фото/видео/альбом, у поста их может быть несколько)
        score += getModalityBias(post.modalityMask());
        return score;
    }

    /*
     * Модальности контента. Пост описывается не одним типом, а набором независимых
     * измерений: осмысленный текст, фото, видео, альбом (несколько медиа). Сигнал
     * пользователя (dwell/скип/дизлайк) начисляется КАЖДОЙ модальности поста, а в
     * скоре берётся среднее по его модальностям — так фото+текст обучает и фото-,
     * и текст-предпочтение, без задвоения при смешанных постах.
     */

    public static final int MOD_TEXT = 0;
    public static final int MOD_PHOTO = 1;
    public static final int MOD_VIDEO = 2;
    public static final int MOD_ALBUM = 3;   // мультимедиа-пост: отдельное измерение «любит подборки»
    private static final int MOD_COUNT = 4;

    // Тюнинг модальностей
    private static final int MEANINGFUL_TEXT_MIN_CHARS = 16;          // короче — эмодзи/тех.подпись, текстом не считаем
    private static final float MODALITY_BIAS_WEIGHT = 0.15f;          // вклад модальных предпочтений в итоговый скор
    private static final float MODALITY_SKIP_DELTA = -0.15f;          // мгновенный свайп: минус каждой модальности поста
    private static final float MODALITY_DWELL_FACTOR = 0.5f;          // доля dwell-буста канала, идущая модальностям
    private static final float MODALITY_VIDEO_COMPLETE_DELTA = 0.4f;  // досмотр до конца — сигнал именно про видео
    private static final float MODALITY_DISLIKE_DELTA = -1.5f;        // дизлайк: минус каждой модальности поста
    private static final float MODALITY_RATING_MAX = 30f;
    private static final float MODALITY_RATING_MIN = -20f;

    /* Тип «одним словом» — только для подписи в истории просмотров (FeedHistoryActivity). */

    public static final int TYPE_TEXT = 0;
    public static final int TYPE_PHOTO = 1;
    public static final int TYPE_VIDEO = 2;

    public static int contentTypeOf(FeedPost post) {
        int mask = post.modalityMask();
        if ((mask & (1 << MOD_VIDEO)) != 0) {
            return TYPE_VIDEO;
        }
        if ((mask & (1 << MOD_PHOTO)) != 0) {
            return TYPE_PHOTO;
        }
        return TYPE_TEXT;
    }

    private float getModalityRating(int mod) {
        SharedPreferences prefs = getRatingPrefs();
        float rating = prefs.getFloat("m_" + mod, 0);
        if (rating == 0) {
            return 0;
        }
        long updated = prefs.getLong("mt_" + mod, 0);
        double days = Math.max(0, System.currentTimeMillis() - updated) / 86400000.0;
        return rating * (float) Math.pow(0.5, days / RATING_HALF_LIFE_DAYS);
    }

    private void addModalityRating(int mod, float delta) {
        float rating = Math.max(MODALITY_RATING_MIN, Math.min(MODALITY_RATING_MAX, getModalityRating(mod) + delta));
        getRatingPrefs().edit()
            .putFloat("m_" + mod, rating)
            .putLong("mt_" + mod, System.currentTimeMillis())
            .apply();
    }

    /** Полная дельта каждой модальности поста: пост экспонировал каждую целиком,
     *  а в скоре берётся среднее по модальностям — задвоения смешанных постов нет. */
    private void addModalityRatingAll(int mask, float delta) {
        for (int m = 0; m < MOD_COUNT; m++) {
            if ((mask & (1 << m)) != 0) {
                addModalityRating(m, delta);
            }
        }
    }

    /** Смещение скора: средний рейтинг модальностей поста относительно среднего по всем. */
    private float getModalityBias(int mask) {
        if (mask == 0) {
            return 0;
        }
        float avgAll = 0;
        for (int m = 0; m < MOD_COUNT; m++) {
            avgAll += getModalityRating(m);
        }
        avgAll /= MOD_COUNT;
        float sum = 0;
        int n = 0;
        for (int m = 0; m < MOD_COUNT; m++) {
            if ((mask & (1 << m)) != 0) {
                sum += getModalityRating(m);
                n++;
            }
        }
        return MODALITY_BIAS_WEIGHT * (sum / n - avgAll);
    }

    /* Рейтинг каналов: копится от взаимодействий, затухает с полураспадом в неделю. */

    private boolean modalityMigrationChecked;

    private SharedPreferences getRatingPrefs() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("feed_rank" + currentAccount, 0);
        if (!modalityMigrationChecked) {
            modalityMigrationChecked = true;
            migrateTypeRatingsToModalities(prefs);
        }
        return prefs;
    }

    /** Разовый перенос старых односложных type_*-рейтингов в модальности (индексы совпадают).
     *  Старые ключи не трогаем (безвредны, оставляют путь отката); каналы ("r_"/"t_") без изменений. */
    private void migrateTypeRatingsToModalities(SharedPreferences prefs) {
        if (prefs.getBoolean("mod_migrated", false)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        for (int t = TYPE_TEXT; t <= TYPE_VIDEO; t++) {
            float rating = prefs.getFloat("type_" + t, 0);
            if (rating != 0) {
                editor.putFloat("m_" + t, rating);
                editor.putLong("mt_" + t, prefs.getLong("typet_" + t, System.currentTimeMillis()));
            }
        }
        editor.putBoolean("mod_migrated", true).apply();
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

    public void trackPostDwell(FeedPost post, long millis, boolean videoCompleted) {
        if (!isTrackedChannel(post.dialogId)) {
            return;
        }
        markPostSeen(post.dialogId, post.getId(), contentTypeOf(post), post.chat != null ? post.chat.title : null);
        final int mask = post.modalityMask();
        if (millis < 1200) {
            addRating(post.dialogId, -0.2f);       // мгновенный свайп = «не интересно»
            addModalityRatingAll(mask, MODALITY_SKIP_DELTA);
        } else if (millis > 4000) {
            float boost = Math.min(1.2f, (millis - 4000) / 1000f * 0.08f);
            addRating(post.dialogId, boost);
            addModalityRatingAll(mask, boost * MODALITY_DWELL_FACTOR);
        }
        if (videoCompleted) {
            addRating(post.dialogId, 0.6f);
            // досмотр — сигнал именно про видео, а не про подпись/фото рядом
            addModalityRating(MOD_VIDEO, MODALITY_VIDEO_COMPLETE_DELTA);
        }
    }

    /* Дизлайк: сильный минус каналу (замена скрытию каналов) */

    public void trackDislike(FeedPost post) {
        if (!isTrackedChannel(post.dialogId)) {
            return;
        }
        markPostSeen(post.dialogId, post.getId(), contentTypeOf(post), null);
        addRating(post.dialogId, -6f);
        addModalityRatingAll(post.modalityMask(), MODALITY_DISLIKE_DELTA);
        int count = getRatingPrefs().getInt("disc_" + post.dialogId, 0) + 1;
        getRatingPrefs().edit().putInt("disc_" + post.dialogId, count).apply();
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
