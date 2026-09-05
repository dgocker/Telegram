package org.telegram.messenger.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.telegram.messenger.FileLog;

import libbox.ConnectionOwner;
import libbox.InterfaceUpdateListener;
import libbox.LocalDNSTransport;
import libbox.NetworkInterfaceIterator;
import libbox.PlatformInterface;
import libbox.CommandServerHandler;
import libbox.RoutePrefix;
import libbox.StringIterator;
import libbox.SystemProxyStatus;
import libbox.TunOptions;
import libbox.WIFIState;

/**
 * TUN-режим: туннель поднимается ВНУТРИ Telegram и включает только собственный пакет
 * (addAllowedApplication). Остальные приложения телефона идут напрямую, отдельный VPN
 * включать не нужно. Звонки (UDP-реле, p2p, групповые) в этом режиме едут в туннель как есть.
 */
public class TgVpnService extends VpnService implements PlatformInterface, CommandServerHandler {

    public static final String CHANNEL_ID = "tgvpn";
    private static final int NOTIFICATION_ID = 20260903;

    public static volatile TgVpnService instance;

    private ParcelFileDescriptor tunInterface;
    private TgVpnPlatform.DefaultInterfaceMonitor monitor;

    @Override
    public void onCreate() {
        super.onCreate();
        monitor = new TgVpnPlatform.DefaultInterfaceMonitor(this);
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable e) {
            FileLog.e(e);
        }
        // Перезапуск системой без контроллера бесполезен: ядро поднимает он же при старте процесса.
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        FileLog.d("tgvpn: VPN revoked by system");
        TgVpnController.getInstance().onTunLost("VPN отозван системой");
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        instance = null;
        closeTun();
        if (monitor != null) {
            monitor.stop();
        }
        super.onDestroy();
    }

    private void closeTun() {
        ParcelFileDescriptor current = tunInterface;
        tunInterface = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignore) {
            }
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pending = launch == null ? null : PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        // applicationInfo.icon == 0 уронил бы startForeground — это единственная нотификация сервиса
        int icon = getApplicationInfo().icon != 0 ? getApplicationInfo().icon : android.R.drawable.ic_lock_lock;
        builder.setContentTitle("VPN")
                .setContentText("Трафик Telegram идёт через ваш сервер")
                .setSmallIcon(icon)
                .setOngoing(true);
        if (pending != null) {
            builder.setContentIntent(pending);
        }
        return builder.build();
    }

    // --- PlatformInterface ---

    @Override
    public int openTun(TunOptions options) throws Exception {
        closeTun();

        Builder builder = new Builder();
        builder.setSession("Telegram VPN");
        builder.setMtu(options.getMTU());

        for (RoutePrefix prefix : iterate(options.getInet4Address())) {
            builder.addAddress(prefix.address(), prefix.prefix());
        }
        try {
            for (RoutePrefix prefix : iterate(options.getInet6Address())) {
                builder.addAddress(prefix.address(), prefix.prefix());
            }
        } catch (Exception e) {
            FileLog.d("tgvpn: no ipv6 address for tun");
        }

        if (options.getAutoRoute()) {
            builder.addRoute("0.0.0.0", 0);
            try {
                builder.addRoute("::", 0);
            } catch (Exception ignore) {
            }
        } else {
            for (RoutePrefix prefix : iterate(options.getInet4RouteAddress())) {
                builder.addRoute(prefix.address(), prefix.prefix());
            }
            try {
                for (RoutePrefix prefix : iterate(options.getInet6RouteAddress())) {
                    builder.addRoute(prefix.address(), prefix.prefix());
                }
            } catch (Exception ignore) {
            }
        }

        String dnsServer = null;
        try {
            dnsServer = options.getDNSServerAddress().getValue();
        } catch (Exception ignore) {
        }
        builder.addDnsServer(dnsServer == null || dnsServer.isEmpty() ? "8.8.8.8" : dnsServer);

        // Главное отличие от обычного VPN-клиента: в туннель попадает ТОЛЬКО наш пакет.
        boolean hasInclude = false;
        StringIterator include = options.getIncludePackage();
        while (include != null && include.hasNext()) {
            String pkg = include.next();
            try {
                builder.addAllowedApplication(pkg);
                hasInclude = true;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (!hasInclude) {
            builder.addAllowedApplication(getPackageName());
        }

        // Пин на текущую физическую сеть: иначе после долгого сна ОС уводит underlying-сокеты
        // в протухший netId и туннель молча немой.
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                builder.setUnderlyingNetworks(new Network[]{active});
            } else {
                builder.setUnderlyingNetworks(null);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        ParcelFileDescriptor established = null;
        Exception last = null;
        for (int attempt = 0; attempt < 3 && established == null; attempt++) {
            try {
                established = builder.establish();
            } catch (Exception e) {
                last = e;
            }
            if (established == null && attempt < 2) {
                Thread.sleep(300);
            }
        }
        if (established == null) {
            // Чаще всего это чужой активный VPN — устройство разрешает только один туннель.
            throw new Exception("establish() failed" + (last == null ? "" : ": " + last.getMessage()));
        }
        tunInterface = established;
        FileLog.d("tgvpn: tun established, fd " + established.getFd());
        return established.getFd();
    }

    @Override
    public void autoDetectInterfaceControl(int fd) {
        // Без protect исходящие сокеты самого ядра попали бы в наш же туннель (UID тот же) — петля.
        if (!protect(fd)) {
            FileLog.d("tgvpn: protect(" + fd + ") returned false");
        }
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return true;
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() {
        return TgVpnPlatform.listInterfaces();
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        monitor.start(listener);
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        monitor.stop();
    }

    @Override
    public void clearDNSCache() {
    }

    @Override
    public ConnectionOwner findConnectionOwner(int protocol, String sourceAddress, int sourcePort, String destinationAddress, int destinationPort) {
        return new ConnectionOwner();
    }

    @Override
    public boolean includeAllNetworks() {
        return false;
    }

    @Override
    public LocalDNSTransport localDNSTransport() {
        return null;
    }

    @Override
    public WIFIState readWIFIState() {
        return null;
    }

    @Override
    public StringIterator systemCertificates() {
        return null;
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }

    @Override
    public boolean useProcFS() {
        return false;
    }

    @Override
    public void sendNotification(libbox.Notification notification) {
    }

    // --- CommandServerHandler ---

    @Override
    public SystemProxyStatus getSystemProxyStatus() {
        return new SystemProxyStatus();
    }

    @Override
    public void setSystemProxyEnabled(boolean enabled) {
    }

    @Override
    public void serviceReload() {
        TgVpnController.getInstance().restart("core requested reload");
    }

    @Override
    public void serviceStop() {
        TgVpnController.getInstance().stop();
    }

    @Override
    public void writeDebugMessage(String message) {
    }

    private static Iterable<RoutePrefix> iterate(final libbox.RoutePrefixIterator iterator) {
        return () -> new java.util.Iterator<RoutePrefix>() {
            @Override
            public boolean hasNext() {
                return iterator != null && iterator.hasNext();
            }

            @Override
            public RoutePrefix next() {
                return iterator.next();
            }
        };
    }
}
