package pt.dashboardauto;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Opens the Android package installer after an update APK finishes downloading. */
public final class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        if (id < 0L) return;
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return;
        long pendingId = context.getSharedPreferences(UpdateChecker.PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(UpdateChecker.PENDING_UPDATE_DOWNLOAD_ID, -1L);
        if (pendingId != -1L && pendingId != id) return;
        android.database.Cursor cursor = null;
        try {
            cursor = manager.query(new DownloadManager.Query().setFilterById(id));
            if (cursor == null || !cursor.moveToFirst()
                    || cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    != DownloadManager.STATUS_SUCCESSFUL) {
                UpdateChecker.clearPendingDownload(context);
                return;
            }
        } catch (RuntimeException ignored) {
            UpdateChecker.clearPendingDownload(context);
            return;
        } finally {
            if (cursor != null) cursor.close();
        }
        Uri apkUri = manager.getUriForDownloadedFile(id);
        if (apkUri == null) {
            UpdateChecker.clearPendingDownload(context);
            return;
        }
        Intent installer = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { context.startActivity(installer); } catch (RuntimeException ignored) {
            UpdateChecker.clearPendingDownload(context);
        }
    }
}
