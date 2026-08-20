package pt.dashboardauto;

import android.content.Context;
import android.content.Intent;
import android.app.UiModeManager;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
        boolean androidAutoActive = isAndroidAutoActive(context);
        if (preferences.getBoolean("navigation_split_player", false)) {
            SplitPlayerActivity.open(context);
            return;
        }
        if ("music_only".equals(mode)) {
            if (!music.isEmpty()) open(context, music);
            schedulePlay(context, autoPlay, 850);
            return;
        }
        if (!music.isEmpty()) open(context, music);
        schedulePlay(context, autoPlay, 850);
        // Quando o Android Auto já está a gerir a navegação, abrir a app de
        // navegação no telemóvel rouba o foco e pode interromper a projeção.
        if (!navigation.isEmpty() && !navigation.equals(music)) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isAndroidAutoActive(context)) {
                    Log.d("DriveDeckCar", "Android Auto iniciou: navegação do telemóvel cancelada");
                    return;
                }
                open(context, navigation);
            }, 950);
        } else if (androidAutoActive) {
            Log.d("DriveDeckCar", "Android Auto ativo: navegação do telemóvel não será aberta");
        }
    }

    /**
     * O Android Auto pode não aparecer como a Activity em primeiro plano no
     * telemóvel. O modo CAR do sistema é o sinal público e seguro para saber
     * que a sessão automóvel já está a ser gerida pelo Android.
     */
    public static boolean isAndroidAutoActive(Context context) {
        UiModeManager uiMode = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        return uiMode != null && uiMode.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR;
    }

    private static void schedulePlay(Context context, boolean autoPlay, long delayMs) {
        if (!autoPlay) return;
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> MusicController.playWhenReady(context), delayMs);
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
