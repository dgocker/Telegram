package org.telegram.messenger.feed;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Скачивание файлов модели суммаризации обычным HTTP с прогрессом,
 * отменой и докачкой через Range. Запускается только явно из UI.
 */
public class FeedModelDownloader {

    public interface Listener {
        void onProgress(long downloaded, long total);
        void onDone(boolean success);
    }

    private static volatile FeedModelDownloader active;

    public static FeedModelDownloader getActive() {
        return active;
    }

    public static boolean isDownloading() {
        return active != null;
    }

    public static synchronized FeedModelDownloader start(Listener listener) {
        if (active != null) {
            active.listener = listener;
            return active;
        }
        FeedModelDownloader downloader = new FeedModelDownloader(listener);
        active = downloader;
        downloader.thread.start();
        return downloader;
    }

    private volatile Listener listener;
    private volatile boolean cancelled;
    private final Thread thread;

    private FeedModelDownloader(Listener listener) {
        this.listener = listener;
        thread = new Thread(this::run, "feedModelDownloader");
    }

    public void setListener(Listener value) {
        listener = value;
    }

    public void cancel() {
        cancelled = true;
        thread.interrupt();
    }

    private void run() {
        boolean success = false;
        try {
            File dir = FeedSummarizer.getModelDir();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            final long total = FeedSummarizer.getTotalModelSize();
            long done = 0;
            for (FeedSummarizer.ModelFile modelFile : FeedSummarizer.MODEL_FILES) {
                File target = new File(dir, modelFile.name);
                if (target.exists() && target.length() == modelFile.size) {
                    done += modelFile.size;
                    postProgress(done, total);
                    continue;
                }
                if (!downloadFile(modelFile, target, done, total)) {
                    postDone(false);
                    return;
                }
                done += modelFile.size;
                postProgress(done, total);
            }
            success = true;
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            synchronized (FeedModelDownloader.class) {
                active = null;
            }
        }
        postDone(success);
    }

    private boolean downloadFile(FeedSummarizer.ModelFile modelFile, File target, long doneBefore, long total) {
        File partial = new File(target.getAbsolutePath() + ".part");
        HttpURLConnection connection = null;
        try {
            long offset = partial.exists() ? partial.length() : 0;
            if (offset > modelFile.size) {
                partial.delete();
                offset = 0;
            }
            URL url = new URL(FeedSummarizer.MODEL_BASE_URL + modelFile.name);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=" + offset + "-");
            }
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                offset = 0;
            } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                return false;
            }
            try (InputStream in = connection.getInputStream();
                 RandomAccessFile out = new RandomAccessFile(partial, "rw")) {
                out.seek(offset);
                byte[] buffer = new byte[65536];
                long written = offset;
                long lastNotify = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (cancelled) {
                        return false;
                    }
                    out.write(buffer, 0, read);
                    written += read;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 100) {
                        lastNotify = now;
                        postProgress(doneBefore + written, total);
                    }
                }
            }
            if (partial.length() != modelFile.size) {
                return false;
            }
            return partial.renameTo(target);
        } catch (Throwable e) {
            if (!cancelled) {
                FileLog.e(e);
            }
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void postProgress(long downloaded, long total) {
        AndroidUtilities.runOnUIThread(() -> {
            Listener l = listener;
            if (l != null) {
                l.onProgress(downloaded, total);
            }
        });
    }

    private void postDone(boolean success) {
        AndroidUtilities.runOnUIThread(() -> {
            Listener l = listener;
            if (l != null) {
                l.onDone(success);
            }
        });
    }
}
