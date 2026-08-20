package pt.dashboardauto;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Player Activity used as the small pane in Android multi-window mode. */
public final class SplitPlayerActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView track;
    private TextView artist;
    private TextView time;
    private ImageView artwork;
    private ImageButton playButton;
    private SeekBar progress;
    private LinearLayout playerPanel;
    private TextView heading;
    private LinearLayout mediaRow;
    private LinearLayout labels;
    private LinearLayout controlRow;
    private TextView closeButton;
    private int lastResponsiveWidth;
    private long durationMs;
    private boolean userSeeking;
    private boolean navigationRequested;
    private BitmapKey renderedArtwork;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderPlayer();
            handler.postDelayed(this, 1000L);
        }
    };

    private static final class BitmapKey {
        final android.graphics.Bitmap bitmap;
        BitmapKey(android.graphics.Bitmap bitmap) { this.bitmap = bitmap; }
    }

    public static void open(Context context) {
        android.content.SharedPreferences preferences = context.getSharedPreferences("dashboard_auto", MODE_PRIVATE);
        String music = preferences.getString("music_app", "");
        if (!music.isEmpty()) {
            Intent musicIntent = context.getPackageManager().getLaunchIntentForPackage(music);
            if (musicIntent != null) {
                musicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                try { context.startActivity(musicIntent); } catch (RuntimeException ignored) { }
            }
        }
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            Intent player = new Intent(context, SplitPlayerActivity.class);
            player.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try { context.startActivity(player); } catch (RuntimeException ignored) { }
        }, music.isEmpty() ? 0L : 350L);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        stopService(new Intent(this, OverlayService.class));
        getWindow().setStatusBarColor(Color.rgb(10, 10, 14));
        getWindow().setNavigationBarColor(Color.rgb(10, 10, 14));
        setContentView(createContent());
        handler.postDelayed(() -> {
            if (getSharedPreferences("dashboard_auto", MODE_PRIVATE).getBoolean("auto_play_music_on_car_mode", true)) MusicController.playWhenReady(this);
            openNavigationAdjacent();
        }, 500L);
    }

    private View createContent() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(10, 10, 14));
        LinearLayout panel = new LinearLayout(this);
        playerPanel = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackground(panelBackground());
        heading = text("DriveDeck  •  Player", 16, Color.WHITE);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout media = new LinearLayout(this);
        mediaRow = media;
        media.setGravity(Gravity.CENTER_VERTICAL);
        artwork = new ImageView(this);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setBackgroundColor(Color.rgb(48, 30, 42));
        media.addView(artwork, new LinearLayout.LayoutParams(dp(68), dp(68)));
        labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        track = text("Sem música ativa", 15, Color.WHITE);
        artist = text("", 12, Color.rgb(170, 170, 180));
        labels.addView(track, new LinearLayout.LayoutParams(-1, -2));
        labels.addView(artist, new LinearLayout.LayoutParams(-1, -2));
        media.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(media, new LinearLayout.LayoutParams(-1, dp(80)));
        installMediaGestures(media);

        time = text("0:00 / 0:00", 10, Color.rgb(160, 160, 170));
        panel.addView(time, new LinearLayout.LayoutParams(-1, dp(22)));
        progress = new SeekBar(this);
        progress.setMax(1000);
        progress.setPadding(0, 0, 0, 0);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                if (durationMs > 0) MusicController.seekTo(SplitPlayerActivity.this, durationMs * bar.getProgress() / 1000L);
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && durationMs > 0) time.setText(formatTime(durationMs * value / 1000L) + " / " + formatTime(durationMs));
            }
        });
        panel.addView(progress, new LinearLayout.LayoutParams(-1, dp(28)));

        LinearLayout controls = new LinearLayout(this);
        controlRow = controls;
        controls.setGravity(Gravity.CENTER);
        ImageButton previous = button(R.drawable.ic_skip_previous, "Faixa anterior");
        playButton = button(R.drawable.ic_play, "Reproduzir ou pausar");
        ImageButton next = button(R.drawable.ic_skip_next, "Faixa seguinte");
        previous.setOnClickListener(v -> MusicController.previous(this));
        next.setOnClickListener(v -> MusicController.next(this));
        playButton.setOnClickListener(v -> { MusicController.playPause(this); renderPlayer(); });
        controls.addView(previous, controlParams());
        controls.addView(playButton, controlParams());
        controls.addView(next, controlParams());
        panel.addView(controls, new LinearLayout.LayoutParams(-1, dp(76)));

        closeButton = text("Fechar player dividido", 12, Color.rgb(170, 170, 180));
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setOnClickListener(v -> finish());
        panel.addView(closeButton, new LinearLayout.LayoutParams(-1, dp(42)));
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> adaptToWindowWidth(root.getWidth()));
        return root;
    }

    private void installMediaGestures(View target) {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }

            @Override public boolean onDoubleTap(MotionEvent event) {
                MusicController.playPause(SplitPlayerActivity.this);
                renderPlayer();
                target.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                return true;
            }

            @Override public boolean onFling(MotionEvent start, MotionEvent end, float velocityX, float velocityY) {
                float deltaX = end.getX() - start.getX();
                float deltaY = end.getY() - start.getY();
                float minimumDistance = dp(48);
                if (Math.abs(deltaX) > Math.abs(deltaY) && Math.abs(deltaX) > minimumDistance && Math.abs(velocityX) > dp(180f)) {
                    if (deltaX < 0) MusicController.next(SplitPlayerActivity.this);
                    else MusicController.previous(SplitPlayerActivity.this);
                    target.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    return true;
                }
                if (deltaY > minimumDistance && Math.abs(deltaY) > Math.abs(deltaX) && Math.abs(velocityY) > dp(180f)) {
                    finish();
                    return true;
                }
                return false;
            }
        });
        target.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(.985f).scaleY(.985f).setDuration(70).start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
            }
            detector.onTouchEvent(event);
            return true;
        });
    }

    private void adaptToWindowWidth(int width) {
        if (width <= 0 || width == lastResponsiveWidth || playerPanel == null) return;
        lastResponsiveWidth = width;
        boolean narrow = width < dp(360);
        boolean veryNarrow = width < dp(285);
        int padding = dp(narrow ? 8 : 12);
        playerPanel.setPadding(padding, dp(narrow ? 6 : 10), padding, dp(narrow ? 6 : 10));
        heading.setTextSize(narrow ? 14 : 16);
        heading.setText(narrow ? "DriveDeck" : "DriveDeck  •  Player");
        updateSize(mediaRow, -1, dp(narrow ? 62 : 80));
        updateSize(artwork, dp(narrow ? 50 : 68), dp(narrow ? 50 : 68));
        labels.setPadding(dp(narrow ? 7 : 10), 0, 0, 0);
        track.setTextSize(narrow ? 13 : 15);
        artist.setTextSize(narrow ? 10 : 12);
        time.setVisibility(veryNarrow ? View.GONE : View.VISIBLE);
        updateSize(progress, -1, dp(narrow ? 24 : 28));
        updateSize(controlRow, -1, dp(narrow ? 68 : 76));
        for (int i = 0; i < controlRow.getChildCount(); i++) {
            View child = controlRow.getChildAt(i);
            if (child instanceof ImageButton) {
                int inset = dp(narrow ? 8 : 12);
                child.setPadding(inset, inset, inset, inset);
            }
        }
        updateSize(closeButton, -1, dp(narrow ? 36 : 42));
    }

    private void updateSize(View view, int width, int height) {
        if (view == null || view.getLayoutParams() == null) return;
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        if (width >= 0) params.width = width;
        if (height >= 0) params.height = height;
        view.setLayoutParams(params);
    }

    private void openNavigationAdjacent() {
        if (navigationRequested || CarModeLauncher.isAndroidAutoActive(this)) return;
        String packageName = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getString("navigation_app", "");
        Intent navigation = getPackageManager().getLaunchIntentForPackage(packageName);
        if (navigation == null) return;
        navigationRequested = true;
        navigation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 24) {
                int width = getResources().getDisplayMetrics().widthPixels;
                int height = getResources().getDisplayMetrics().heightPixels;
                options.setLaunchBounds(new Rect(0, 0, Math.round(width * .70f), height));
            }
            startActivity(navigation, options.toBundle());
            handler.postDelayed(this::fallbackToOverlayIfNeeded, 1500L);
        } catch (RuntimeException ignored) {
            startFallbackOverlay();
        }
    }

    private void fallbackToOverlayIfNeeded() {
        if (!isFinishing() && Build.VERSION.SDK_INT >= 24 && !isInMultiWindowMode()) startFallbackOverlay();
    }

    private void startFallbackOverlay() {
        getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit().putFloat("overlay_scale", .75f).putBoolean("overlay_expanded", false).apply();
        Intent service = new Intent(this, OverlayService.class).putExtra("compact_fallback", true);
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service); } catch (RuntimeException ignored) { }
        finish();
    }

    private void renderPlayer() {
        if (track == null) return;
        String[] parts = MusicController.currentTrack(this).split("\\n", 2);
        track.setText(parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "Sem música ativa");
        artist.setText(parts.length > 1 ? parts[1] : "");
        android.graphics.Bitmap bitmap = MusicController.currentArtwork(this);
        if (renderedArtwork == null || renderedArtwork.bitmap != bitmap) {
            renderedArtwork = new BitmapKey(bitmap);
            if (bitmap != null) artwork.setImageBitmap(bitmap); else artwork.setImageDrawable(null);
        }
        MusicController.PlaybackInfo playback = MusicController.playbackInfo(this);
        playButton.setImageResource(playback.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        durationMs = playback.durationMs;
        progress.setEnabled(durationMs > 0);
        if (!userSeeking) progress.setProgress(durationMs == 0 ? 0 : (int) Math.min(1000L, playback.positionMs * 1000L / durationMs));
        time.setText(formatTime(playback.positionMs) + " / " + formatTime(durationMs));
    }

    private ImageButton button(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(description);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        return button;
    }
    private LinearLayout.LayoutParams controlParams() { return new LinearLayout.LayoutParams(0, dp(68), 1f); }
    private TextView text(String value, float size, int color) {
        TextView result = new TextView(this);
        result.setText(value); result.setTextSize(size); result.setTextColor(color); result.setMaxLines(1);
        result.setEllipsize(android.text.TextUtils.TruncateAt.END); return result;
    }
    private GradientDrawable panelBackground() {
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(18, 18, 23)); background.setCornerRadius(dp(18)); return background;
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private String formatTime(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1000L;
        return (totalSeconds / 60L) + ":" + (totalSeconds % 60L < 10 ? "0" : "") + (totalSeconds % 60L);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
