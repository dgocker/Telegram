package org.telegram.messenger.vpn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import libbox.Libbox;
import libbox.OverrideOptions;
import libbox.SetupOptions;

/**
 * Встроенный VPN Telegram. Два режима на одном ядре sing-box:
 *
 *  * MODE_TUN — VpnService внутри самого Telegram, в туннель включён только наш пакет.
 *    Звонки (UDP-реле, p2p, групповые) идут в туннель как обычный трафик. Основной режим.
 *  * MODE_PROXY — ядро без туннеля, только mixed на 127.0.0.1, MTProto заворачивается штатными
 *    настройками прокси. Запасной путь, когда TUN недоступен (нет согласия или занят другим VPN).
 *
 * Конфиги приходят готовыми с сервера подписки (/singbox/{token}), нода выбирается автоматически —
 * первая, через которую проходит проба.
 */
public class TgVpnController {

    public static final String STATUS_OFF = "off";
    public static final String STATUS_CONNECTING = "connecting";
    public static final String STATUS_CONNECTED = "connected";
    public static final String STATUS_ERROR = "error";

    public static final String MODE_TUN = "tun";
    public static final String MODE_PROXY = "proxy";

    public static final String DEFAULT_BASE_URL = "https://78.17.74.156:8443/";

    private static final String PREFS = "tgvpn";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_MODE = "mode";
    private static final String KEY_PORT = "port";
    private static final String KEY_LAST_NODE = "last_node";

    private static final String PROBE_URL = "http://cp.cloudflare.com/generate_204";
    private static final int PROBE_TIMEOUT_MS = 8000;
    private static final long HEALTH_PERIOD_SEC = 60;
    private static final long SERVICE_WAIT_MS = 5000;

    private static volatile TgVpnController instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService healthExecutor = Executors.newSingleThreadScheduledExecutor();
    private final TgVpnCore core = new TgVpnCore();

    private static boolean libboxReady;
    private TgVpnPlatform proxyPlatform;
    private ScheduledFuture<?> healthTask;
    private int healthFailures;
    /** TUN отвалился в этой сессии (отозван или занят) — не долбимся в него до перезапуска. */
    private volatile boolean tunBlocked;

    private volatile String status = STATUS_OFF;
    private volatile String activeMode = MODE_PROXY;
    private volatile String nodeName = "";
    private volatile String lastError = "";
    private volatile int localPort;

    public static TgVpnController getInstance() {
        if (instance == null) {
            synchronized (TgVpnController.class) {
                if (instance == null) {
                    instance = new TgVpnController();
                }
            }
        }
        return instance;
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    /** Со вшитой в сборку подпиской VPN включён с первого запуска — это и есть «включается сам». */
    public boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, !TextUtils.isEmpty(BuildConfig.VPN_SUB_TOKEN));
    }

    public String getToken() {
        return prefs().getString(KEY_TOKEN, BuildConfig.VPN_SUB_TOKEN);
    }

    public String getBaseUrl() {
        String saved = prefs().getString(KEY_BASE_URL, "");
        if (!TextUtils.isEmpty(saved)) {
            return saved;
        }
        return TextUtils.isEmpty(BuildConfig.VPN_BASE_URL) ? DEFAULT_BASE_URL : BuildConfig.VPN_BASE_URL;
    }

    public String getMode() {
        return prefs().getString(KEY_MODE, MODE_TUN);
    }

    public void setMode(String mode) {
        prefs().edit().putString(KEY_MODE, MODE_TUN.equals(mode) ? MODE_TUN : MODE_PROXY).apply();
    }

    public String getStatus() {
        return status;
    }

    /** Режим, в котором ядро реально работает сейчас (может отличаться от выбранного — фолбэк). */
    public String getActiveMode() {
        return activeMode;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getLastError() {
        return lastError;
    }

    public int getLocalPort() {
        return localPort;
    }

    public void setSubscription(String token, String baseUrl) {
        prefs().edit()
                .putString(KEY_TOKEN, token == null ? "" : token.trim())
                .putString(KEY_BASE_URL, TextUtils.isEmpty(baseUrl) ? DEFAULT_BASE_URL : baseUrl.trim())
                .apply();
    }

    /** Системный диалог согласия на VPN; null — согласие уже есть. Показывать только из Activity. */
    public static Intent vpnConsentIntent(Context context) {
        try {
            return VpnService.prepare(context);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) {
            tunBlocked = false;
            start();
        } else {
            stop();
        }
    }

    /** Автостарт при запуске процесса и после смены сети — безопасно звать сколько угодно раз. */
    public void start() {
        executor.execute(this::startInternal);
    }

    public void stop() {
        executor.execute(this::stopInternal);
    }

    public void restart(String reason) {
        FileLog.d("tgvpn: restart (" + reason + ")");
        executor.execute(() -> {
            teardownCore();
            startInternal();
        });
    }

    /** Туннель отобрали (revoke или чужой VPN) — доживаем сессию в прокси-режиме. */
    public void onTunLost(String reason) {
        tunBlocked = true;
        lastError = reason;
        restart("tun lost: " + reason);
    }

    /** Смена сети: ядро само не всегда переоткрывает соединения. */
    public void onNetworkChanged() {
        executor.execute(core::resetNetwork);
    }

    // --- ядро ---

    private void startInternal() {
        if (!isEnabled() || TextUtils.isEmpty(getToken())) {
            return;
        }
        if (!isSupportedAbi()) {
            fail("устройство не arm64 — ядро VPN недоступно");
            return;
        }
        if (STATUS_CONNECTED.equals(status) && core.isRunning() && probe()) {
            return; // уже поднято
        }

        status = STATUS_CONNECTING;
        lastError = "";
        notifyChanged();

        try {
            setupLibbox();
        } catch (Throwable t) {
            FileLog.e(t);
            fail("ядро не инициализировалось: " + t.getMessage());
            return;
        }

        if (localPort == 0) {
            localPort = choosePort();
        }

        boolean wantTun = MODE_TUN.equals(getMode()) && !tunBlocked;
        if (wantTun && vpnConsentIntent(ApplicationLoader.applicationContext) != null) {
            wantTun = false;
            lastError = "нет разрешения на VPN — работаю через прокси";
        }
        if (wantTun && tryMode(MODE_TUN)) {
            return;
        }
        if (tryMode(MODE_PROXY)) {
            return;
        }

        teardownCore();
        fail(TextUtils.isEmpty(lastError) ? "ни одна нода не ответила" : lastError);
    }

    /** Поднимает ядро в заданном режиме, перебирая ноды. true — трафик пошёл. */
    private boolean tryMode(String mode) {
        if (MODE_TUN.equals(mode) && !startTunService()) {
            lastError = "VPN-сервис не запустился";
            return false;
        }

        List<TgVpnApi.Node> nodes = TgVpnApi.loadNodes(ApplicationLoader.applicationContext, getBaseUrl(), getToken(), localPort, mode);
        if (nodes.isEmpty()) {
            lastError = "подписка не отдала ни одной ноды";
            return false;
        }

        for (TgVpnApi.Node node : preferLastGood(nodes)) {
            if (!isEnabled()) {
                teardownCore();
                return false;
            }
            try {
                startNode(mode, node.config);
            } catch (Exception e) {
                FileLog.e(e);
                lastError = String.valueOf(e.getMessage());
                if (MODE_TUN.equals(mode) && lastError != null && lastError.contains("establish")) {
                    // Туннель не отдают (скорее всего активен другой VPN) — ноды тут ни при чём.
                    tunBlocked = true;
                    teardownCore();
                    return false;
                }
                continue;
            }
            if (probe()) {
                activeMode = mode;
                nodeName = node.name;
                prefs().edit().putString(KEY_LAST_NODE, node.name).putInt(KEY_PORT, localPort).apply();
                if (MODE_TUN.equals(mode)) {
                    clearProxy();
                } else {
                    applyProxy();
                }
                status = STATUS_CONNECTED;
                lastError = "";
                healthFailures = 0;
                scheduleHealth();
                FileLog.d("tgvpn: connected via " + node.name + " (" + mode + ") on 127.0.0.1:" + localPort);
                notifyChanged();
                return true;
            }
        }
        teardownCore();
        return false;
    }

    private void startNode(String mode, String config) throws Exception {
        OverrideOptions options = new OverrideOptions();
        if (MODE_TUN.equals(mode)) {
            TgVpnService service = TgVpnService.instance;
            if (service == null) {
                throw new Exception("VPN service is not running");
            }
            // В туннель — только Telegram. Остальные приложения телефона идут напрямую.
            options.setIncludePackage(new TgVpnPlatform.StringListIterator(
                    Collections.singletonList(ApplicationLoader.applicationContext.getPackageName())));
            core.startOrReload(service, service, config, options);
        } else {
            if (proxyPlatform == null) {
                proxyPlatform = new TgVpnPlatform(ApplicationLoader.applicationContext);
            }
            core.startOrReload(proxyPlatform, proxyPlatform, config, options);
        }
    }

    private boolean startTunService() {
        if (TgVpnService.instance != null) {
            return true;
        }
        Context context = ApplicationLoader.applicationContext;
        try {
            Intent intent = new Intent(context, TgVpnService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
        long deadline = SystemClock.elapsedRealtime() + SERVICE_WAIT_MS;
        while (TgVpnService.instance == null && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return TgVpnService.instance != null;
    }

    private void stopInternal() {
        cancelHealth();
        teardownCore();
        clearProxy();
        status = STATUS_OFF;
        nodeName = "";
        notifyChanged();
    }

    private void teardownCore() {
        core.close();
        TgVpnService service = TgVpnService.instance;
        if (service != null) {
            service.stopSelf();
        }
    }

    private void setupLibbox() throws Exception {
        if (libboxReady) {
            return;
        }
        try {
            // Go-сборщик мусора: без потолка ядро ловит фоновой OOM и GC-паузы (проверено в vyom).
            Os.setenv("GOGC", "75", true);
        } catch (Exception ignore) {
        }
        File base = new File(ApplicationLoader.applicationContext.getFilesDir(), "vpn");
        if (!base.exists()) {
            base.mkdirs();
        }
        SetupOptions options = new SetupOptions();
        options.setBasePath(base.getAbsolutePath());
        options.setWorkingPath(base.getAbsolutePath());
        options.setTempPath(ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath());
        Libbox.setup(options);
        libboxReady = true;
    }

    private List<TgVpnApi.Node> preferLastGood(List<TgVpnApi.Node> nodes) {
        String last = prefs().getString(KEY_LAST_NODE, "");
        if (TextUtils.isEmpty(last)) {
            return nodes;
        }
        List<TgVpnApi.Node> ordered = new ArrayList<>(nodes.size());
        for (TgVpnApi.Node node : nodes) {
            if (last.equals(node.name)) {
                ordered.add(node);
            }
        }
        for (TgVpnApi.Node node : nodes) {
            if (!last.equals(node.name)) {
                ordered.add(node);
            }
        }
        return ordered;
    }

    /**
     * Проба через HTTP-часть mixed-инбаунда, а не SOCKS: тогда домен резолвит ядро внутри
     * туннеля. Через SOCKS Java резолвила бы его локально — по задушенному DNS.
     */
    private boolean probe() {
        HttpURLConnection connection = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", localPort));
            connection = (HttpURLConnection) new URL(PROBE_URL).openConnection(proxy);
            connection.setConnectTimeout(PROBE_TIMEOUT_MS);
            connection.setReadTimeout(PROBE_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Connection", "close");
            int code = connection.getResponseCode();
            return code == 204 || code == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private int choosePort() {
        int saved = prefs().getInt(KEY_PORT, 0);
        if (saved > 0) {
            return saved;
        }
        try {
            ServerSocket socket = new ServerSocket(0);
            int port = socket.getLocalPort();
            socket.close();
            return port;
        } catch (Exception e) {
            return 10808;
        }
    }

    private static boolean isSupportedAbi() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    // --- настройки прокси Telegram (только для MODE_PROXY) ---

    private void applyProxy() {
        final int port = localPort;
        AndroidUtilities.runOnUIThread(() -> {
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            preferences.edit()
                    .putString("proxy_ip", "127.0.0.1")
                    .putInt("proxy_port", port)
                    .putString("proxy_user", "")
                    .putString("proxy_pass", "")
                    .putString("proxy_secret", "")
                    .putBoolean("proxy_enabled", true)
                    // Звонки через SOCKS живут только по TCP-реле: UDP-путь WebRTC прокси не видит
                    // (NetworkManager.cpp ставит ProxyInfo лишь на TCP), и без этого они утекли бы мимо.
                    .putBoolean("proxy_enabled_calls", true)
                    .putBoolean("dbg_force_tcp_in_calls", true)
                    .commit();
            SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo("127.0.0.1", port, "", "", "");
            SharedConfig.currentProxy = SharedConfig.addProxy(info);
            ConnectionsManager.setProxySettings(true, "127.0.0.1", port, "", "", "");
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        });
    }

    private void clearProxy() {
        AndroidUtilities.runOnUIThread(() -> {
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            // Чужой прокси, выставленный руками, не трогаем.
            if (!"127.0.0.1".equals(preferences.getString("proxy_ip", ""))) {
                return;
            }
            preferences.edit()
                    .putBoolean("proxy_enabled", false)
                    .putBoolean("proxy_enabled_calls", false)
                    .putBoolean("dbg_force_tcp_in_calls", false)
                    .commit();
            ConnectionsManager.setProxySettings(false, "", 1080, "", "", "");
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        });
    }

    // --- живучесть ---

    private void scheduleHealth() {
        cancelHealth();
        healthTask = healthExecutor.scheduleWithFixedDelay(() -> {
            if (!isEnabled()) {
                return;
            }
            if (probe()) {
                healthFailures = 0;
                return;
            }
            healthFailures++;
            FileLog.d("tgvpn: health probe failed (" + healthFailures + ")");
            if (healthFailures >= 2) {
                healthFailures = 0;
                restart("health probe failed twice");
            }
        }, HEALTH_PERIOD_SEC, HEALTH_PERIOD_SEC, TimeUnit.SECONDS);
    }

    private void cancelHealth() {
        if (healthTask != null) {
            healthTask.cancel(false);
            healthTask = null;
        }
    }

    private void fail(String message) {
        status = STATUS_ERROR;
        lastError = message;
        FileLog.e("tgvpn: " + message);
        notifyChanged();
    }

    private void notifyChanged() {
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged));
    }
}
