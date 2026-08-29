package pt.dashboardauto;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Moves a completed update into private cache and opens Android's installer UI. */
public final class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (UpdateChecker.UPDATE_INSTALL_COMPLETE_ACTION.equals(intent.getAction())) {
            String path = intent.getStringExtra("temp_apk_path");
            if (path != null) new File(path).delete();
            UpdateChecker.clearPendingDownload(context);
            UpdateChecker.cleanupTemporaryDownloads(context);
            UpdateChecker.setStatus(context, "installed", "Atualização concluída");
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
                UpdateChecker.setStatus(context, "failed", "O download não foi concluído");
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
            UpdateChecker.setStatus(context, "failed", "O Android não encontrou o APK descarregado");
            return;
        }
        String version = context.getSharedPreferences(UpdateChecker.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(UpdateChecker.PENDING_UPDATE_VERSION, "update");
        File directory = new File(context.getCacheDir(), UpdateChecker.TEMP_UPDATE_DIRECTORY);
        File temporaryApk = new File(directory, "drivedeck-" + safeFilePart(version) + ".apk");
        try {
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create update cache");
            copyToCache(context, apkUri, temporaryApk);
            if (!isCompatibleApk(context, temporaryApk)) {
                throw new IOException("Downloaded APK does not match DriveDeck signature");
            }
            UpdateChecker.setStatus(context, "installing", "A abrir o instalador do Android");
            // Keep the private temporary file alive while the system installer reads it.
            // It is removed on the next download, so the update never becomes permanent
            // user storage.
            openSystemInstaller(context, temporaryApk, apkUri);
            manager.remove(id);
            UpdateChecker.clearPendingDownload(context);
        } catch (Exception ignored) {
            temporaryApk.delete();
            UpdateChecker.clearPendingDownload(context);
            UpdateChecker.cleanupTemporaryDownloads(context);
            UpdateChecker.setStatus(context, "failed", "Não foi possível validar ou abrir o APK");
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

    private static boolean isCompatibleApk(Context context, File apk) {
        try {
            android.content.pm.PackageManager packageManager = context.getPackageManager();
            android.content.pm.Signature[] installedSigners;
            android.content.pm.Signature[] candidateSigners;
            android.content.pm.PackageInfo installed;
            android.content.pm.PackageInfo candidate;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                int flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES;
                installed = packageManager.getPackageInfo(context.getPackageName(), flags);
                candidate = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
                if (candidate == null || installed.signingInfo == null || candidate.signingInfo == null) return false;
                installedSigners = installed.signingInfo.getApkContentsSigners();
                candidateSigners = candidate.signingInfo.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation") int flags = android.content.pm.PackageManager.GET_SIGNATURES;
                installed = packageManager.getPackageInfo(context.getPackageName(), flags);
                candidate = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
                if (candidate == null) return false;
                installedSigners = installed.signatures;
                candidateSigners = candidate.signatures;
            }
            if (!context.getPackageName().equals(candidate.packageName)) return false;
            if (installedSigners == null || candidateSigners == null || installedSigners.length != candidateSigners.length) return false;
            for (android.content.pm.Signature signer : installedSigners) {
                boolean found = false;
                for (android.content.pm.Signature candidateSigner : candidateSigners) {
                    if (signer.equals(candidateSigner)) { found = true; break; }
                }
                if (!found) return false;
            }
            return true;
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }

    private static void openSystemInstaller(Context context, File apk, Uri downloadUri) throws IOException {
        Uri apkUri;
        try {
            apkUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    apk);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Cannot expose update APK", exception);
        }
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        install.setClipData(android.content.ClipData.newRawUri("DriveDeck update", apkUri));
        try {
            context.startActivity(install);
        } catch (RuntimeException exception) {
            // Some Android builds expose the package installer only for ACTION_VIEW.
            Intent fallback = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(downloadUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            fallback.setClipData(android.content.ClipData.newRawUri("DriveDeck update", downloadUri));
            try {
                context.startActivity(fallback);
            } catch (RuntimeException fallbackException) {
                throw new IOException("Android installer is unavailable", fallbackException);
            }
        }
    }
}
