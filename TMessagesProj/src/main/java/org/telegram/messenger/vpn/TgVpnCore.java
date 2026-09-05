package org.telegram.messenger.vpn;

import org.telegram.messenger.FileLog;

import libbox.CommandServer;
import libbox.CommandServerHandler;
import libbox.Libbox;
import libbox.OverrideOptions;
import libbox.PlatformInterface;

/**
 * Жизненный цикл ядра sing-box. Один и тот же код для обоих режимов: платформу отдаёт либо
 * TgVpnPlatform (прокси), либо TgVpnService (TUN — он обязан быть VpnService ради protect/establish).
 */
public class TgVpnCore {

    private static final String ERR_ADDR_IN_USE = "address already in use";

    private CommandServer service;

    public boolean isRunning() {
        return service != null;
    }

    /** Первый вызов поднимает ядро, последующие — горячий релоад с новым конфигом. */
    public void startOrReload(PlatformInterface platform, CommandServerHandler handler, String config, OverrideOptions options) throws Exception {
        if (service == null) {
            CommandServer created = Libbox.newCommandServer(handler, platform);
            created.start();
            service = created;
        }
        // Ретрай только на коллизию своего же mixed-порта: прошлый listener мог не успеть
        // отпустить сокет. Остальные ошибки — наверх, они означают битый конфиг или мёртвую ноду.
        for (int attempt = 0; ; attempt++) {
            try {
                service.startOrReloadService(config, options);
                return;
            } catch (Exception e) {
                String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                if (attempt < 3 && message.contains(ERR_ADDR_IN_USE)) {
                    Thread.sleep(400);
                    continue;
                }
                throw e;
            }
        }
    }

    public void resetNetwork() {
        CommandServer current = service;
        if (current == null) {
            return;
        }
        try {
            current.resetNetwork();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void close() {
        CommandServer current = service;
        service = null;
        if (current != null) {
            try {
                current.closeService();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }
}
