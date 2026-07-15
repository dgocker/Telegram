package org.telegram.ui.Components;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.feed.FeedModelDownloader;
import org.telegram.messenger.feed.FeedSummarizer;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

/**
 * Интерактивная загрузка модели суммаризации: сначала явное подтверждение
 * с размером (~260МБ), затем прогресс с возможностью отмены.
 */
public class FeedModelDownloadAlert {

    public static void show(Context context, Theme.ResourcesProvider resourcesProvider, Runnable onSuccess) {
        if (FeedSummarizer.isModelDownloaded()) {
            if (onSuccess != null) {
                onSuccess.run();
            }
            return;
        }
        if (FeedModelDownloader.isDownloading()) {
            showProgress(context, resourcesProvider, onSuccess);
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.SmartFeedModelTitle));
        builder.setMessage(LocaleController.formatString("SmartFeedModelText", R.string.SmartFeedModelText, AndroidUtilities.formatFileSize(FeedSummarizer.getTotalModelSize())));
        builder.setPositiveButton(getString(R.string.SmartFeedModelDownload), (dialog, which) -> showProgress(context, resourcesProvider, onSuccess));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.show();
    }

    private static void showProgress(Context context, Theme.ResourcesProvider resourcesProvider, Runnable onSuccess) {
        final AlertDialog progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_LOADING, resourcesProvider);
        progressDialog.setMessage(getString(R.string.SmartFeedDownloading));
        progressDialog.setCancelable(true);

        final boolean[] cancelledByUser = {false};
        final FeedModelDownloader downloader = FeedModelDownloader.start(new FeedModelDownloader.Listener() {
            @Override
            public void onProgress(long downloaded, long total) {
                progressDialog.setProgress((int) (downloaded * 100 / Math.max(1, total)));
            }

            @Override
            public void onDone(boolean success) {
                try {
                    progressDialog.dismiss();
                } catch (Exception ignore) {}
                if (success) {
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                } else if (!cancelledByUser[0]) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
                    builder.setTitle(getString(R.string.AppName));
                    builder.setMessage(getString(R.string.SmartFeedDownloadFailed));
                    builder.setPositiveButton(getString(R.string.OK), null);
                    builder.show();
                }
            }
        });
        progressDialog.setOnCancelListener(dialog -> {
            cancelledByUser[0] = true;
            downloader.cancel();
        });
        progressDialog.show();
    }
}
