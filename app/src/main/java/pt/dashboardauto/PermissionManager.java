package pt.dashboardauto;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/** Centralizes checks and system-settings flows for every special permission. */
public final class PermissionManager {
    public static final int REQUEST_BLUETOOTH = 41;
    public static final int REQUEST_NOTIFICATIONS = 42;
    public static final int REQUEST_PHONE_STATE = 43;

    private PermissionManager() { }

    public static boolean canDrawOverlay(Context context) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context); }

    public static boolean canConnectBluetooth(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static void requestBluetooth(Activity activity) {
        if (Build.VERSION.SDK_INT >= 31 && !canConnectBluetooth(activity)) activity.requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, REQUEST_BLUETOOTH);
    }

    public static boolean canPostNotifications(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static void requestNotifications(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33 && !canPostNotifications(activity)) activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQUEST_NOTIFICATIONS);
    }

    public static boolean canReadPhoneState(Context context) {
        return context.checkSelfPermission("android.permission.READ_PHONE_STATE") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static void requestPhoneState(Activity activity) {
        if (!canReadPhoneState(activity)) activity.requestPermissions(new String[]{"android.permission.READ_PHONE_STATE"}, REQUEST_PHONE_STATE);
    }

    public static boolean isMediaAccessEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName expected = new ComponentName(context, MusicNotificationListener.class);
        for (String item : enabled.split(":")) if (expected.equals(ComponentName.unflattenFromString(item))) return true;
        return false;
    }

    public static void openOverlaySettings(Context context) {
        context.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.getPackageName())));
    }

    public static void openMediaSettings(Context context) { context.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }

    public static void openAssistantSettings(Context context) {
        context.startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
    }
}
