package pt.dashboardauto;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Starts/stops the driving overlay when a car audio device connects. */
public class BluetoothReceiver extends BroadcastReceiver {
    @SuppressWarnings("deprecation")
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            context.getSharedPreferences("dashboard_auto", Context.MODE_PRIVATE).edit()
                    .putBoolean("selected_bluetooth_connected", false)
                    .remove("bluetooth_last_event")
                    .remove("bluetooth_last_event_at")
                    .apply();
            Log.d("DriveDeckBT", "Estado Bluetooth reiniciado após boot");
            return;
        }
        boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);
        boolean disconnected = BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action);
        if (BluetoothA2dpAction.isConnectionAction(action) || BluetoothHeadsetAction.isConnectionAction(action)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
            connected = state == BluetoothProfile.STATE_CONNECTED;
            disconnected = state == BluetoothProfile.STATE_DISCONNECTED;
        }
        if (!connected && !disconnected) return;
        android.content.SharedPreferences preferences = context.getSharedPreferences("dashboard_auto", Context.MODE_PRIVATE);
        BluetoothDevice device;
        try {
            if (Build.VERSION.SDK_INT >= 33) device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            else device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (SecurityException denied) {
            Log.w("DriveDeckBT", "Sem permissão para ler o dispositivo Bluetooth", denied);
            return;
        }
        String selectedAddress = preferences.getString("bluetooth_device_address", "");
        if (device == null || selectedAddress.isEmpty() || !selectedAddress.equals(device.getAddress())) { Log.d("DriveDeckBT", "Dispositivo ignorado"); return; }
        // O estado de ligação é útil para chamadas mesmo quando a abertura
        // automática do Car Mode está desligada.
        preferences.edit().putBoolean("selected_bluetooth_connected", connected).apply();
        boolean autoBluetooth = preferences.getBoolean("auto_bluetooth", false);
        boolean closeOnDisconnect = preferences.getBoolean("close_on_bluetooth_disconnect", true);
        if (!autoBluetooth && !(disconnected && closeOnDisconnect)) {
            Log.d("DriveDeckBT", "Automação Bluetooth desativada");
            return;
        }
        if (connected && (!PermissionManager.canDrawOverlay(context) || !PermissionManager.canConnectBluetooth(context))) {
            Log.w("DriveDeckBT", "Permissão de overlay/Bluetooth em falta");
            return;
        }
        long now = System.currentTimeMillis();
        String eventKey = (connected ? "connected:" : "disconnected:") + device.getAddress();
        String previousEvent = preferences.getString("bluetooth_last_event", "");
        long previousAt = preferences.getLong("bluetooth_last_event_at", 0L);
        if (eventKey.equals(previousEvent) && now - previousAt < 3500L) return;
        preferences.edit().putString("bluetooth_last_event", eventKey).putLong("bluetooth_last_event_at", now).apply();
        if (disconnected) {
            final long disconnectEventAt = now;
            final PendingResult pendingResult = goAsync();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.content.SharedPreferences latest = context.getSharedPreferences("dashboard_auto", Context.MODE_PRIVATE);
                    String latestEvent = latest.getString("bluetooth_last_event", "");
                    long latestEventAt = latest.getLong("bluetooth_last_event_at", 0L);
                    if (!eventKey.equals(latestEvent) || latestEventAt != disconnectEventAt) {
                        Log.d("DriveDeckBT", "Desligação ignorada: houve uma reconexão ou novo evento");
                        return;
                    }
                    if (latest.getBoolean("pause_music_on_bluetooth_disconnect", false)) MusicController.pause(context);
                    if (latest.getBoolean("close_on_bluetooth_disconnect", true)) {
                        // Fecha a ilha, regressa ao Home e termina os processos de
                        // navegação/música em segundo plano quando o Android permitir.
                        Intent closeEverything = new Intent(context, OverlayService.class)
                                .setAction(OverlayService.ACTION_CLOSE_EVERYTHING);
                        try {
                            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(closeEverything);
                            else context.startService(closeEverything);
                        } catch (RuntimeException error) {
                            Log.e("DriveDeckBT", "Não foi possível fechar o Car Mode", error);
                        }
                    }
                } finally {
                    pendingResult.finish();
                }
            }, 1200L);
            return;
        }
        Intent service = new Intent(context, OverlayService.class);
        service.putExtra("launch_apps", true);
        service.putExtra("launch_mode", preferences.getString("bluetooth_launch_mode", "car_mode"));
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service); else context.startService(service);
        } catch (RuntimeException error) {
            Log.e("DriveDeckBT", "Não foi possível iniciar o Car Mode", error);
        }
    }

    private static final class BluetoothA2dpAction {
        static boolean isConnectionAction(String action) { return "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(action); }
    }

    private static final class BluetoothHeadsetAction {
        static boolean isConnectionAction(String action) { return "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED".equals(action); }
    }

}
