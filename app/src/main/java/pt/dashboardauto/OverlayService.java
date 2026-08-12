package pt.dashboardauto;

import android.app.Notification;
import android.app.AlertDialog;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.content.res.Configuration;

public class OverlayService extends Service {
    private static final String ACTION_CLOSE_PLAYER = "pt.dashboardauto.action.CLOSE_PLAYER";
    private static final String ACTION_RESET_LAYOUT = "pt.dashboardauto.action.RESET_LAYOUT";
    private WindowManager manager;
    private LinearLayout overlay;
    private TextView track;
    private TextView artist;
    private TextView album;
    private TextView timeLabel;
    private ImageView artwork;
    private ImageButton playButton;
    private SeekBar progressBar;
    private long durationMs;
    private boolean userSeeking;
    private TextView dropZone;
    private boolean playingState;
    private long optimisticPlaybackUntil;
    private boolean landscape;
    private boolean expanded;
    private boolean miniPlayerHidden;
    private WindowManager.LayoutParams windowParams;
    private float downX, downY;
    private int startX, startY;
    private boolean dragMoved;
    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshTrack = new Runnable() {
        @Override public void run() {
            boolean currentLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            if (overlay != null && currentLandscape != landscape) {
                removeOverlay();
                addOverlay();
                return;
            }
            updateTrack();
            refreshHandler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        expanded = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getBoolean("overlay_expanded", false);
        createNotification();
        if (PermissionManager.canDrawOverlay(this)) addOverlay();
        else stopSelf();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CLOSE_PLAYER.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RESET_LAYOUT.equals(intent.getAction())) {
            resetLayout();
            return START_NOT_STICKY;
        }
        if (overlay == null && PermissionManager.canDrawOverlay(this)) addOverlay();
        if (intent != null && intent.getBooleanExtra("launch_apps", false)) CarModeLauncher.openConfiguredApps(this, intent.getStringExtra("launch_mode"));
        return START_NOT_STICKY;
    }

    private void addOverlay() {
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        overlay = new LinearLayout(this);
        overlay.setOrientation(landscape && !expanded ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(dp(8), dp(4), dp(8), dp(4));
        overlay.setBackground(panelBackground());
        LinearLayout mediaInfo = new LinearLayout(this);
        mediaInfo.setOrientation(LinearLayout.HORIZONTAL);
        mediaInfo.setGravity(Gravity.CENTER_VERTICAL);
        mediaInfo.setPadding(dp(expanded ? 8 : 6), dp(expanded ? 8 : 5), dp(expanded ? 10 : 8), dp(expanded ? 8 : 5));
        mediaInfo.setBackground(mediaBackground());
        mediaInfo.setOnClickListener(v -> openConfigured("music_app"));
        mediaInfo.setOnTouchListener(this::dragOverlay);
        artwork = new ImageView(this);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setBackgroundColor(Color.rgb(65, 27, 40));
        int artworkSize = dp(expanded ? 58 : 44);
        mediaInfo.addView(artwork, new LinearLayout.LayoutParams(artworkSize, artworkSize));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(8), 0, 0, 0);
        track = new TextView(this);
        track.setTextColor(Color.WHITE); track.setTextSize(expanded ? 15 : 12); track.setMaxLines(1);
        track.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artist = new TextView(this);
        artist.setTextColor(Color.rgb(166, 166, 178)); artist.setTextSize(expanded ? 12 : 10); artist.setMaxLines(1);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        album = new TextView(this);
        album.setTextColor(Color.rgb(128, 128, 140)); album.setTextSize(10); album.setMaxLines(1);
        album.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(track, new LinearLayout.LayoutParams(-1, -2));
        labels.addView(artist, new LinearLayout.LayoutParams(-1, -2));
        if (expanded) {
            labels.addView(album, new LinearLayout.LayoutParams(-1, -2));
            timeLabel = new TextView(this);
            timeLabel.setTextColor(Color.rgb(145, 145, 156));
            timeLabel.setTextSize(9);
            timeLabel.setText("0:00 / 0:00");
            labels.addView(timeLabel, new LinearLayout.LayoutParams(-1, -2));
            progressBar = new SeekBar(this);
            progressBar.setMax(1000);
            progressBar.setPadding(0, 0, 0, 0);
            progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    userSeeking = false;
                    if (durationMs > 0) MusicController.seekTo(OverlayService.this, durationMs * seekBar.getProgress() / 1000L);
                }
                @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                    if (fromUser && timeLabel != null && durationMs > 0) timeLabel.setText(formatTime(durationMs * value / 1000L) + " / " + formatTime(durationMs));
                }
            });
            labels.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(24)));
        }
        mediaInfo.addView(labels, new LinearLayout.LayoutParams(0, -1, 1f));
        ImageButton expandButton = actionButton(expanded ? R.drawable.ic_collapse : R.drawable.ic_expand, expanded ? "Recolher player" : "Expandir player", true);
        expandButton.setOnClickListener(v -> toggleExpanded());
        mediaInfo.addView(expandButton, mediaButtonParams());
        overlay.addView(mediaInfo, new LinearLayout.LayoutParams(landscape && !expanded ? dp(240) : dp(330), dp(expanded ? 102 : 58)));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        if (!landscape || expanded) overlay.addView(controls, new LinearLayout.LayoutParams(-2, dp(expanded ? 84 : 54)));
        int[] icons = new int[]{R.drawable.ic_skip_previous, R.drawable.ic_play, R.drawable.ic_skip_next, R.drawable.ic_home};
        String[] descriptions = {"Faixa anterior", "Reproduzir ou pausar", "Faixa seguinte", "Abrir Dashboard"};
        for (int i = 0; i < icons.length; i++) {
            ImageButton b = actionButton(icons[i], descriptions[i], i == 1 || i == icons.length - 1);
            if (i == 1) {
                playButton = b;
                setPlayButtonState(playingState, false);
            }
            final int actionIndex = i;
            b.setOnClickListener(v -> handleAction(actionIndex));
            (landscape && !expanded ? overlay : controls).addView(b, buttonParams());
        }
        if (expanded) {
            if (landscape) addExpandedActions(overlay);
            else {
                LinearLayout extra = new LinearLayout(this);
                extra.setOrientation(LinearLayout.HORIZONTAL);
                extra.setGravity(Gravity.CENTER);
                addExpandedActions(extra);
                overlay.addView(extra, new LinearLayout.LayoutParams(-2, dp(84)));
            }
            addResizeHandle();
        }
        float savedScale = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getFloat("overlay_scale", 1f);
        overlay.setScaleX(savedScale);
        overlay.setScaleY(savedScale);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getInt("overlay_x", dp(16));
        params.y = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getInt("overlay_y", defaultOverlayY());
        windowParams = params;
        try {
            manager.addView(overlay, params);
            overlay.setAlpha(0f);
            overlay.setScaleX(savedScale * .92f);
            overlay.setScaleY(savedScale * .92f);
            overlay.animate().alpha(1f).scaleX(savedScale).scaleY(savedScale).setDuration(220).start();
            overlay.post(this::clampOverlayPosition);
            refreshHandler.post(refreshTrack);
        } catch (WindowManager.BadTokenException | SecurityException error) {
            overlay = null;
            stopSelf();
        }
    }

    private GradientDrawable panelBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(18, 18, 23));
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.rgb(58, 58, 70));
        return background;
    }

    private GradientDrawable mediaBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(31, 31, 39));
        background.setCornerRadius(dp(14));
        return background;
    }

    private void updateTrack() {
        if (track == null) return;
        String value = MusicController.currentTrack(this);
        String[] parts = value.split("\\n", 2);
        track.setText(parts.length > 0 ? parts[0] : "Sem música ativa");
        if (artist != null) artist.setText(parts.length > 1 ? parts[1] : "");
        if (album != null) album.setText(expanded ? MusicController.currentAlbum(this) : "");
        MusicController.PlaybackInfo playback = MusicController.playbackInfo(this);
        if (android.os.SystemClock.uptimeMillis() >= optimisticPlaybackUntil) playingState = playback.playing;
        setPlayButtonState(playingState, false);
        durationMs = playback.durationMs;
        if (progressBar != null && !userSeeking) {
            progressBar.setEnabled(durationMs > 0);
            progressBar.setProgress(durationMs <= 0 ? 0 : (int) Math.min(1000L, playback.positionMs * 1000L / durationMs));
            if (timeLabel != null) timeLabel.setText(formatTime(playback.positionMs) + " / " + formatTime(durationMs));
        }
        if (artwork != null) {
            android.graphics.Bitmap bitmap = MusicController.currentArtwork(this);
            if (bitmap != null) artwork.setImageBitmap(bitmap); else artwork.setImageDrawable(null);
        }
    }

    private ImageButton actionButton(int icon, String description, boolean accent) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(accent ? Color.rgb(255, 55, 95) : Color.WHITE);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setPadding(dp(expanded ? 14 : 10), dp(expanded ? 14 : 10), dp(expanded ? 14 : 10), dp(expanded ? 14 : 10));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(accent ? Color.rgb(69, 27, 42) : Color.rgb(36, 36, 45));
        background.setCornerRadius(dp(14));
        button.setBackground(background);
        button.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                view.animate().scaleX(.90f).scaleY(.90f).setDuration(80).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                if (event.getAction() == MotionEvent.ACTION_UP) view.performClick();
            }
            return true;
        });
        return button;
    }

    private void setPlayButtonState(boolean playing, boolean animate) {
        if (playButton == null) return;
        int icon = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        if (!animate) {
            playButton.setImageResource(icon);
            return;
        }
        playButton.animate().alpha(.35f).setDuration(70).withEndAction(() -> {
            if (playButton == null) return;
            playButton.setImageResource(icon);
            playButton.animate().alpha(1f).setDuration(130).start();
        }).start();
    }

    private void addExpandedActions(LinearLayout parent) {
        ImageButton navigation = actionButton(R.drawable.ic_map, "Abrir navegação", false);
        navigation.setOnClickListener(v -> openConfigured("navigation_app"));
        parent.addView(navigation, buttonParams());
        ImageButton music = actionButton(R.drawable.ic_music, "Escolher app de áudio", false);
        music.setOnClickListener(v -> showAudioChooser());
        parent.addView(music, buttonParams());
        ImageButton close = actionButton(R.drawable.ic_close, "Opções para fechar", true);
        close.setOnClickListener(v -> showCloseOptions());
        parent.addView(close, buttonParams());
    }

    private LinearLayout.LayoutParams buttonParams() {
        int size = dp(expanded ? 72 : 54);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        int margin = dp(expanded ? 5 : 2);
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    private boolean dragOverlay(android.view.View view, MotionEvent event) {
        if (windowParams == null || manager == null) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getRawX(); downY = event.getRawY();
            startX = windowParams.x; startY = windowParams.y;
            dragMoved = false;
            showDropZone();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (Math.abs(event.getRawX() - downX) > dp(8) || Math.abs(event.getRawY() - downY) > dp(8)) dragMoved = true;
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int draggedX = Math.max(0, startX + (int) (event.getRawX() - downX));
            int draggedY = Math.max(0, startY + (int) (event.getRawY() - downY));
            int playerWidth = overlay == null ? 0 : overlay.getWidth();
            int playerHeight = overlay == null ? 0 : overlay.getHeight();
            int targetX = Math.max(0, (screenWidth - playerWidth) / 2);
            int targetY = Math.max(0, screenHeight - playerHeight - dp(18));
            float normalizedDistance = Math.max(0f, Math.min(1f, (event.getRawY() - (screenHeight - dp(320))) / (float) dp(320)));
            float magnetProgress = normalizedDistance * normalizedDistance * (3f - 2f * normalizedDistance);
            windowParams.x = Math.max(0, (int) (draggedX + (targetX - draggedX) * magnetProgress * .9f));
            windowParams.y = Math.max(0, (int) (draggedY + (targetY - draggedY) * magnetProgress * .9f));
            boolean overDropZone = magnetProgress > .45f;
            if (dropZone != null) dropZone.setBackground(dropZoneBackground(overDropZone));
            try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float deltaY = event.getRawY() - downY;
            float deltaX = event.getRawX() - downX;
            boolean overDropZone = event.getRawY() > getResources().getDisplayMetrics().heightPixels - dp(180);
            hideDropZone();
            if (dragMoved && !expanded && deltaY < -dp(64) && Math.abs(deltaY) > Math.abs(deltaX) * 1.2f) {
                toggleExpanded();
                return true;
            }
            if (!dragMoved && Math.abs(deltaX) < dp(12) && Math.abs(deltaY) < dp(12)) {
                view.performClick();
                return true;
            }
            if (deltaY > dp(84) && overDropZone && Math.abs(deltaY) > Math.abs(deltaX) * 1.2f) {
                hideMiniPlayer();
                return true;
            }
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int playerWidth = overlay == null ? 0 : overlay.getWidth();
            int rightEdge = Math.max(0, screenWidth - playerWidth);
            if (windowParams.x <= dp(28)) windowParams.x = 0;
            else if (windowParams.x >= rightEdge - dp(28)) windowParams.x = rightEdge;
            getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit().putInt("overlay_x", windowParams.x).putInt("overlay_y", windowParams.y).apply();
            return true;
        }
        return true;
    }

    private int defaultOverlayY() {
        return Math.max(dp(24), getResources().getDisplayMetrics().heightPixels - dp(150));
    }

    private void clampOverlayPosition() {
        if (overlay == null || windowParams == null || manager == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxX = Math.max(0, screenWidth - overlay.getWidth());
        int maxY = Math.max(0, screenHeight - overlay.getHeight());
        int clampedX = Math.max(0, Math.min(windowParams.x, maxX));
        int clampedY = Math.max(0, Math.min(windowParams.y, maxY));
        if (clampedX == windowParams.x && clampedY == windowParams.y) return;
        windowParams.x = clampedX;
        windowParams.y = clampedY;
        try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
        getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit()
                .putInt("overlay_x", clampedX)
                .putInt("overlay_y", clampedY)
                .apply();
    }

    private void resetLayout() {
        getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit()
                .remove("overlay_x")
                .remove("overlay_y")
                .remove("overlay_scale")
                .apply();
        if (overlay == null || windowParams == null || manager == null) return;
        overlay.setScaleX(1f);
        overlay.setScaleY(1f);
        windowParams.x = dp(16);
        windowParams.y = defaultOverlayY();
        try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private void removeOverlay() {
        refreshHandler.removeCallbacks(refreshTrack);
        if (overlay != null && manager != null) {
            try { manager.removeView(overlay); } catch (IllegalArgumentException ignored) { }
        }
        hideDropZone();
        overlay = null;
        track = null;
        artist = null;
        album = null;
        timeLabel = null;
        progressBar = null;
        durationMs = 0L;
        artwork = null;
        playButton = null;
        windowParams = null;
    }

    private void handleAction(int index) {
        if (index == 0) { runMediaAction(() -> MusicController.previous(this)); return; }
        if (index == 1) {
            playingState = !playingState;
            optimisticPlaybackUntil = android.os.SystemClock.uptimeMillis() + 1300;
            setPlayButtonState(playingState, true);
            runMediaAction(() -> MusicController.playPause(this));
            refreshHandler.postDelayed(this::updateTrack, 1300);
            return;
        }
        if (index == 2) { runMediaAction(() -> MusicController.next(this)); return; }
        if (index == 3) {
            Intent launch = new Intent(this, ComposeMainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return;
        }
        if (index == 4) { toggleExpanded(); return; }
    }

    private void toggleExpanded() {
        boolean nextState = !expanded;
        if (overlay == null) return;
        overlay.animate().alpha(0f).scaleX(.92f).scaleY(.92f).setDuration(160).withEndAction(() -> {
            expanded = nextState;
            getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit().putBoolean("overlay_expanded", expanded).apply();
            removeOverlay();
            addOverlay();
        }).start();
    }

    private void showCloseOptions() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Fechar Car Mode")
                .setItems(new String[]{"Fechar mini player", "Fechar tudo"}, (ignored, which) -> {
                    if (which == 0) hideMiniPlayer();
                    else closeEverything();
                })
                .setNegativeButton("Cancelar", null)
                .create();
        if (dialog.getWindow() != null) dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
    }

    private void closeEverything() {
        stopSelf();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { startActivity(home); } catch (RuntimeException ignored) { }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::stopConfiguredApps, 600);
    }

    private void stopConfiguredApps() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (activityManager == null) return;
        android.content.SharedPreferences preferences = getSharedPreferences("dashboard_auto", MODE_PRIVATE);
        String navigation = preferences.getString("navigation_app", "");
        String music = preferences.getString("music_app", "");
        if (!navigation.isEmpty() && !navigation.equals(getPackageName())) activityManager.killBackgroundProcesses(navigation);
        if (!music.isEmpty() && !music.equals(getPackageName()) && !music.equals(navigation)) activityManager.killBackgroundProcesses(music);
    }

    private void hideMiniPlayer() {
        if (overlay == null) return;
        miniPlayerHidden = true;
        overlay.setPivotX(overlay.getWidth() / 2f);
        overlay.setPivotY(overlay.getHeight());
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        float targetX = screenWidth / 2f - (windowParams.x + overlay.getWidth() / 2f);
        float targetY = screenHeight - dp(18) - (windowParams.y + overlay.getHeight());
        overlay.animate()
                .translationX(targetX)
                .translationY(targetY)
                .scaleX(.16f)
                .scaleY(.08f)
                .alpha(0f)
                .setInterpolator(new AccelerateInterpolator(1.8f))
                .setDuration(280)
                .withEndAction(this::removeOverlay)
                .start();
    }

    private void showDropZone() {
        if (dropZone != null || manager == null) return;
        dropZone = new TextView(this);
        dropZone.setText("↓  Soltar para fechar  ↓");
        dropZone.setTextColor(Color.WHITE);
        dropZone.setTextSize(12);
        dropZone.setGravity(Gravity.CENTER);
        dropZone.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        dropZone.setBackground(dropZoneBackground(false));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(210), dp(48), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(10);
        try {
            manager.addView(dropZone, params);
            dropZone.setAlpha(0f);
            dropZone.animate().alpha(1f).setDuration(140).start();
        } catch (RuntimeException ignored) { dropZone = null; }
    }

    private GradientDrawable dropZoneBackground(boolean active) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(active ? Color.rgb(210, 42, 78) : Color.rgb(44, 44, 55));
        background.setCornerRadius(dp(20));
        background.setStroke(dp(1), active ? Color.rgb(255, 130, 150) : Color.rgb(100, 100, 115));
        return background;
    }

    private void hideDropZone() {
        if (dropZone == null) return;
        TextView current = dropZone;
        dropZone = null;
        current.animate().alpha(0f).setDuration(100).withEndAction(() -> {
            try { if (manager != null) manager.removeView(current); } catch (IllegalArgumentException ignored) { }
        }).start();
    }

    private LinearLayout.LayoutParams mediaButtonParams() {
        int size = dp(expanded ? 64 : 52);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private void addResizeHandle() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ImageButton handle = new ImageButton(this);
        handle.setImageResource(R.drawable.ic_resize);
        handle.setContentDescription("Redimensionar player");
        handle.setTooltipText("Arrastar para redimensionar");
        handle.setPadding(dp(9), dp(9), dp(9), dp(9));
        handle.setBackgroundColor(Color.TRANSPARENT);
        final float[] initialScale = {1f};
        final float[] initialX = {0f};
        final float[] initialY = {0f};
        handle.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                initialScale[0] = overlay.getScaleX();
                initialX[0] = event.getRawX();
                initialY[0] = event.getRawY();
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float horizontalDelta = event.getRawX() - initialX[0];
                float verticalDelta = event.getRawY() - initialY[0];
                float delta = Math.max(horizontalDelta, verticalDelta) / dp(420f);
                float scale = Math.max(.72f, Math.min(1.45f, initialScale[0] + delta));
                overlay.setScaleX(scale);
                overlay.setScaleY(scale);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit().putFloat("overlay_scale", overlay.getScaleX()).apply();
                if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                return true;
            }
            return true;
        });
        row.addView(handle, new LinearLayout.LayoutParams(dp(48), dp(42)));
        overlay.addView(row, new LinearLayout.LayoutParams(-1, dp(42)));
    }

    private void runMediaAction(Runnable action) {
        new Thread(action, "dashboard-auto-media").start();
        refreshHandler.postDelayed(this::updateTrack, 160);
    }

    private String formatTime(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    private void openConfigured(String key) {
        String packageName = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getString(key, "");
        if (packageName.isEmpty()) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); }
    }

    private void showAudioChooser() {
        android.content.SharedPreferences preferences = getSharedPreferences("dashboard_auto", MODE_PRIVATE);
        String rawFavorites = preferences.getString("audio_favorites", "");
        java.util.ArrayList<String> packages = new java.util.ArrayList<>();
        if (!rawFavorites.isEmpty()) {
            for (String packageName : rawFavorites.split(",")) {
                if (!packageName.isEmpty() && getPackageManager().getLaunchIntentForPackage(packageName) != null) packages.add(packageName);
            }
        }
        String primary = preferences.getString("music_app", "");
        if (packages.isEmpty() && !primary.isEmpty()) packages.add(primary);
        if (packages.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Apps de áudio").setMessage("Adiciona apps de áudio favoritas nas definições.").setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[packages.size()];
        for (int i = 0; i < packages.size(); i++) {
            try { labels[i] = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(packages.get(i), 0)).toString(); }
            catch (android.content.pm.PackageManager.NameNotFoundException error) { labels[i] = packages.get(i); }
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Escolher áudio")
                .setItems(labels, (ignored, which) -> openAudioApp(packages.get(which)))
                .setNegativeButton("Cancelar", null)
                .create();
        if (dialog.getWindow() != null) dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
    }

    private void openAudioApp(String packageName) {
        getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit().putString("music_app", packageName).apply();
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { startActivity(launch); } catch (RuntimeException ignored) { return; }
        if (getSharedPreferences("dashboard_auto", MODE_PRIVATE).getBoolean("auto_play_music_on_car_mode", true)) {
            refreshHandler.postDelayed(() -> MusicController.play(this), 750L);
        }
    }

    private void createNotification() {
        String channel = "dashboard_auto_overlay";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(channel, "DriveDeck", NotificationManager.IMPORTANCE_LOW));
        Intent openApp = new Intent(this, ComposeMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 10, openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent closePlayer = new Intent(this, OverlayService.class).setAction(ACTION_CLOSE_PLAYER);
        PendingIntent closeIntent = PendingIntent.getService(this, 11, closePlayer, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action closeAction = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Fechar player", closeIntent).build();
        Notification notification = new Notification.Builder(this, channel)
                .setContentTitle("DriveDeck ativo")
                .setContentText("Overlay de condução em execução")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(contentIntent)
                .addAction(closeAction)
                .setOngoing(true)
                .build();
        startForeground(7, notification);
    }

    @Override public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
