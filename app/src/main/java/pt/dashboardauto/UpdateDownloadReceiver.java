package pt.dashboardauto;

import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Moves a completed update into cache, installs it, and removes all temporary data. */
public final class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (UpdateChecker.UPDATE_INSTALL_COMPLETE_ACTION.equals(intent.getAction())) {
            String path = intent.getStringExtra("temp_apk_path");
            if (path != null) new File(path).delete();
            UpdateChecker.clearPendingDownload(context);
            UpdateChecker.cleanupTemporaryDownloads(context);
            return;
        }
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
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
        String version = context.getSharedPreferences(UpdateChecker.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(UpdateChecker.PENDING_UPDATE_VERSION, "update");
        File directory = new File(context.getCacheDir(), UpdateChecker.TEMP_UPDATE_DIRECTORY);
        File temporaryApk = new File(directory, "drivedeck-" + safeFilePart(version) + ".apk");
        try {
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create update cache");
            copyToCache(context, apkUri, temporaryApk);
            // Remove the DownloadManager entry and its external-files payload now that
            // the APK is in the private cache and the PackageInstaller owns the stream.
            manager.remove(id);
            installFromCache(context, temporaryApk);
        } catch (Exception ignored) {
            temporaryApk.delete();
            UpdateChecker.clearPendingDownload(context);
            UpdateChecker.cleanupTemporaryDownloads(context);
        }
    }

    private static String safeFilePart(String value) {
        if (value == null || value.trim().isEmpty()) return "update";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void copyToCache(Context context, Uri source, File destination) throws IOException {
        try (java.io.InputStream input = context.getContentResolver().openInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Cannot open downloaded APK");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private static void installFromCache(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            try (FileInputStream input = new FileInputStream(apk);
                 java.io.OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                session.fsync(output);
            }
            Intent callback = new Intent(context, UpdateDownloadReceiver.class)
                    .setAction(UpdateChecker.UPDATE_INSTALL_COMPLETE_ACTION)
                    .putExtra("temp_apk_path", apk.getAbsolutePath());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            session.commit(pendingIntent.getIntentSender());
        } catch (Exception exception) {
            try { session.abandon(); } catch (RuntimeException ignored) { }
            throw exception;
        } finally {
            session.close();
        }
    }
}
