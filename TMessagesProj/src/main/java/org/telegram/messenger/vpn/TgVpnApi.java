package org.telegram.messenger.vpn;

import android.content.Context;
import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Ноды для встроенного VPN: сервер отдаёт ГОТОВЫЕ конфиги sing-box, клиент их не собирает.
 * См. /root/singbox_convert.py и ручку GET /singbox/{token} на сервере подписки.
 */
public class TgVpnApi {

    public static class Node {
        public final String name;
        public final String config;

        Node(String name, String config) {
            this.name = name;
            this.config = config;
        }
    }

    private static final int TIMEOUT_MS = 15000;

    /** Ответ сервера, иначе — последний удачно скачанный (кэш переживает офлайн и старт без сети). */
    public static List<Node> loadNodes(Context context, String baseUrl, String token, int port, String mode) {
        File cache = cacheFile(context, port, mode);
        String cachedHash = null;
        String body = null;
        if (cache.exists()) {
            body = readFile(cache);
            if (body != null) {
                try {
                    cachedHash = JsonParser.parseString(body).getAsJsonObject().get("hash").getAsString();
                } catch (Exception ignore) {
                }
            }
        }

        String fresh = request(baseUrl, token, port, mode, cachedHash);
        if (fresh != null) {
            writeFile(cache, fresh);
            body = fresh;
        }
        return parse(body);
    }

    private static String request(String baseUrl, String token, int port, String mode, String lastHash) {
        HttpURLConnection connection = null;
        try {
            StringBuilder url = new StringBuilder(baseUrl);
            if (url.charAt(url.length() - 1) != '/') {
                url.append('/');
            }
            url.append("singbox/").append(token).append("?port=").append(port).append("&mode=").append(mode);
            if (!TextUtils.isEmpty(lastHash)) {
                url.append("&last_hash=").append(lastHash);
            }
            connection = (HttpURLConnection) new URL(url.toString()).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Connection", "close");
            int code = connection.getResponseCode();
            if (code == 304) {
                return null; // конфиги не менялись — берём кэш
            }
            if (code != 200) {
                FileLog.e("tgvpn: subscription http " + code);
                return null;
            }
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static List<Node> parse(String body) {
        List<Node> nodes = new ArrayList<>();
        if (TextUtils.isEmpty(body)) {
            return nodes;
        }
        try {
            JsonArray array = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("nodes");
            for (JsonElement element : array) {
                JsonObject node = element.getAsJsonObject();
                nodes.add(new Node(node.get("name").getAsString(), node.get("config").toString()));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return nodes;
    }

    private static File cacheFile(Context context, int port, String mode) {
        File dir = new File(context.getFilesDir(), "vpn");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // порт и режим — часть ключа: конфиг под них уже подставлен сервером
        return new File(dir, "nodes-" + mode + "-" + port + ".json");
    }

    private static String readFile(File file) {
        try {
            byte[] data = new byte[(int) file.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            int read = in.read(data);
            in.close();
            return read > 0 ? new String(data, 0, read, "UTF-8") : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(File file, String content) {
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.write(content.getBytes("UTF-8"));
            out.close();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
