package org.telegram.messenger.vpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

import libbox.CommandServerHandler;
import libbox.ConnectionOwner;
import libbox.InterfaceUpdateListener;
import libbox.Libbox;
import libbox.LocalDNSTransport;
import libbox.NetworkInterface;
import libbox.NetworkInterfaceIterator;
import libbox.PlatformInterface;
import libbox.StringIterator;
import libbox.SystemProxyStatus;
import libbox.TunOptions;
import libbox.WIFIState;

/**
 * Хост-часть ядра sing-box для прокси-режима: TUN нет, ядро слушает только mixed на 127.0.0.1.
 * Сетевые куски (перечисление интерфейсов, монитор дефолтного интерфейса) вынесены в static —
 * их же переиспользует TUN-режим, где PlatformInterface обязан наследовать VpnService.
 */
public class TgVpnPlatform implements PlatformInterface, CommandServerHandler {

    private final Context context;
    private final DefaultInterfaceMonitor monitor;

    public TgVpnPlatform(Context context) {
        this.context = context.getApplicationContext();
        this.monitor = new DefaultInterfaceMonitor(this.context);
    }

    // --- PlatformInterface ---

    @Override
    public int openTun(TunOptions options) throws Exception {
        // Прокси-режим: туннеля нет. Конфиг с tun-inbound сюда приходить не должен.
        throw new Exception("tun is not available in proxy mode");
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() {
        return listInterfaces();
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
    public void autoDetectInterfaceControl(int fd) {
        // Защищать нечего: без TUN исходящие сокеты ядра и так идут по обычному маршруту.
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return false;
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

    // --- общее с TUN-режимом ---

    /**
     * Физические интерфейсы для auto_detect_interface. tun/ppp/p2p исключены намеренно:
     * иначе ядро может выбрать наш же туннель и закольцевать трафик.
     */
    public static NetworkInterfaceIterator listInterfaces() {
        final List<NetworkInterface> list = new ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                String name = ni.getName();
                if (ni.isLoopback() || !ni.isUp() || name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("p2p")) {
                    continue;
                }
                NetworkInterface item = new NetworkInterface();
                item.setName(name);
                item.setIndex(ni.getIndex());
                try {
                    item.setMTU(ni.getMTU());
                } catch (Exception ignore) {
                }
                int flags = 1 | 64; // IFF_UP | IFF_RUNNING
                try {
                    if (ni.isPointToPoint()) flags |= 16;
                    if (ni.supportsMulticast()) flags |= 32768;
                } catch (Exception ignore) {
                }
                item.setFlags(flags);

                final List<String> addresses = new ArrayList<>();
                for (java.net.InterfaceAddress addr : ni.getInterfaceAddresses()) {
                    String host = addr.getAddress().getHostAddress();
                    if (host == null) {
                        continue;
                    }
                    int scope = host.indexOf('%');
                    if (scope >= 0) {
                        host = host.substring(0, scope);
                    }
                    addresses.add(host + "/" + addr.getNetworkPrefixLength());
                }
                item.setAddresses(new StringListIterator(addresses));

                if (name.startsWith("wlan") || name.startsWith("ap")) {
                    item.setType(Libbox.InterfaceTypeWIFI);
                } else if (name.startsWith("rmnet") || name.startsWith("ccmni")) {
                    item.setType(Libbox.InterfaceTypeCellular);
                } else if (name.startsWith("eth")) {
                    item.setType(Libbox.InterfaceTypeEthernet);
                } else {
                    item.setType(Libbox.InterfaceTypeOther);
                }
                list.add(item);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return new NetworkInterfaceIterator() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < list.size();
            }

            @Override
            public NetworkInterface next() {
                return list.get(index++);
            }
        };
    }

    public static class StringListIterator implements StringIterator {
        private final List<String> values;
        private int index = 0;

        public StringListIterator(List<String> values) {
            this.values = values;
        }

        @Override
        public boolean hasNext() {
            return index < values.size();
        }

        @Override
        public int len() {
            return values.size();
        }

        @Override
        public String next() {
            return values.get(index++);
        }
    }

    /** Сообщает ядру физический интерфейс по умолчанию; VPN-сети (в т.ч. наш будущий TUN) игнорируются. */
    public static class DefaultInterfaceMonitor {
        private final Context context;
        private InterfaceUpdateListener listener;
        private ConnectivityManager.NetworkCallback callback;

        public DefaultInterfaceMonitor(Context context) {
            this.context = context;
        }

        public void start(InterfaceUpdateListener listener) {
            this.listener = listener;
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            if (callback == null) {
                callback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        update();
                    }

                    @Override
                    public void onLost(Network network) {
                        update();
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                        update();
                    }
                };
                try {
                    cm.registerDefaultNetworkCallback(callback);
                } catch (Exception e) {
                    callback = null;
                    FileLog.e(e);
                }
            }
            update();
        }

        public void stop() {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && callback != null) {
                try {
                    cm.unregisterNetworkCallback(callback);
                } catch (Exception ignore) {
                }
            }
            callback = null;
            listener = null;
        }

        public void update() {
            InterfaceUpdateListener current = listener;
            if (current == null) {
                return;
            }
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network network = cm != null ? cm.getActiveNetwork() : null;
                NetworkCapabilities caps = network != null ? cm.getNetworkCapabilities(network) : null;
                LinkProperties props = network != null ? cm.getLinkProperties(network) : null;
                String name = props != null ? props.getInterfaceName() : null;
                if (caps == null || name == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || name.startsWith("tun")) {
                    current.updateDefaultInterface("", -1, false, false);
                    return;
                }
                java.net.NetworkInterface ni = java.net.NetworkInterface.getByName(name);
                current.updateDefaultInterface(name, ni != null ? ni.getIndex() : -1,
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }
}
