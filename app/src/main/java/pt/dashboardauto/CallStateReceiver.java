package pt.dashboardauto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;

/** Brings the selected navigation app back after a phone call starts, when the user enabled it. */
public class CallStateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
        if (!"OFFHOOK".equals(intent.getStringExtra("state"))) return;
        if (!PermissionManager.canReadPhoneState(context)) return;
        android.content.SharedPreferences preferences = context.getSharedPreferences("dashboard_auto", Context.MODE_PRIVATE);
        if (!preferences.getBoolean("return_navigation_during_call", true)) return;
        // Não interferir no ecrã de chamadas fora do contexto de condução.
        // A verificação é repetida dentro do atraso para cobrir desligamentos
        // Bluetooth que aconteçam enquanto o sistema ainda está a abrir a chamada.
        if (!isDrivingContext(preferences)) return;
        String navigationPackage = preferences.getString("navigation_app", "");
        if (navigationPackage.isEmpty()) return;
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            if (isDrivingContext(context.getSharedPreferences("dashboard_auto", Context.MODE_PRIVATE))) {
                openNavigation(context, navigationPackage);
            }
        }, 1200);
        handler.postDelayed(() -> {
            if (isDrivingContext(context.getSharedPreferences("dashboard_auto", Context.MODE_PRIVATE))) {
                openNavigation(context, navigationPackage);
            }
        }, 3000);
    }

    private boolean isDrivingContext(android.content.SharedPreferences preferences) {
        return OverlayService.isActive() || preferences.getBoolean("selected_bluetooth_connected", false);
    }

    private void openNavigation(Context context, String packageName) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { context.startActivity(launch); } catch (RuntimeException ignored) { }
    }
}
