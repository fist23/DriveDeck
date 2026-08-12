package pt.dashboardauto;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** Opens music first, then navigation so navigation remains the visible app. */
public final class CarModeLauncher {
    private CarModeLauncher() { }

    public static void openConfiguredApps(Context context) {
        openConfiguredApps(context, "car_mode");
    }

    public static void openConfiguredApps(Context context, String mode) {
        android.content.SharedPreferences preferences = context.getSharedPreferences("dashboard_auto", Context.MODE_PRIVATE);
        String music = preferences.getString("music_app", "");
        String navigation = preferences.getString("navigation_app", "");
        boolean autoPlay = preferences.getBoolean("auto_play_music_on_car_mode", true);
        if ("music_only".equals(mode)) {
            if (!music.isEmpty()) open(context, music);
            schedulePlay(context, autoPlay, 850);
            return;
        }
        if (!music.isEmpty()) open(context, music);
        schedulePlay(context, autoPlay, 850);
        if (!navigation.isEmpty() && !navigation.equals(music)) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> open(context, navigation), 950);
        }
    }

    private static void schedulePlay(Context context, boolean autoPlay, long delayMs) {
        if (!autoPlay) return;
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> MusicController.play(context), delayMs);
        handler.postDelayed(() -> MusicController.play(context), delayMs + 900);
    }

    private static void open(Context context, String packageName) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { context.startActivity(launch); } catch (RuntimeException ignored) {
            // Android may block activity launches initiated from background components.
        }
    }
}
