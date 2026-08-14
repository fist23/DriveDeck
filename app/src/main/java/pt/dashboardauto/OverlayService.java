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
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.content.res.Configuration;

public class OverlayService extends Service {
    private static volatile boolean active;
    private static final float MIN_OVERLAY_SCALE = .70f;
    private static final float MAX_OVERLAY_SCALE = 1.25f;
    private static final String ACTION_CLOSE_PLAYER = "pt.dashboardauto.action.CLOSE_PLAYER";
    private static final String ACTION_RESET_LAYOUT = "pt.dashboardauto.action.RESET_LAYOUT";
    private static final String ACTION_REBUILD_LAYOUT = "pt.dashboardauto.action.REBUILD_LAYOUT";
    private WindowManager manager;
    private FrameLayout overlay;
    private TextView track;
    private TextView artist;
    private TextView album;
    private TextView timeLabel;
    private ImageView artwork;
    private Bitmap renderedArtwork;
    private ImageButton playButton;
    private ImageButton resizeHandle;
    private SeekBar progressBar;
    private long durationMs;
    private boolean userSeeking;
    private TextView dropZone;
    private WindowManager.LayoutParams dropZoneParams;
    private boolean dropZoneTop;
    private boolean playingState;
    private String lastTrackValue;
    private String pendingTrackValue;
    private boolean trackTransitionRunning;
    private long optimisticPlaybackUntil;
    private boolean landscape;
    private boolean physicalLandscape;
    private boolean expanded;
    private boolean miniPlayerHidden;
    private WindowManager.LayoutParams windowParams;
    private int baseOverlayWidth;
    private int baseOverlayHeight;
    private float downX, downY;
    private int startX, startY;
    private boolean dragMoved;
    private boolean dragActive;
    private boolean dropZoneActive;
    private VelocityTracker dragVelocity;
    private int dragTouchSlop;
    private int pendingDragX;
    private int pendingDragY;
    private boolean dragFramePosted;
    private float pendingResizeScale = 1f;
    private boolean resizeFramePosted;
    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshTrack = new Runnable() {
        @Override public void run() {
            boolean currentLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            if (overlay != null && currentLandscape != physicalLandscape) {
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
        active = true;
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
        if (intent != null && ACTION_REBUILD_LAYOUT.equals(intent.getAction())) {
            if (overlay != null) removeOverlay();
            if (PermissionManager.canDrawOverlay(this)) addOverlay();
            return START_NOT_STICKY;
        }
        if (overlay == null && PermissionManager.canDrawOverlay(this)) addOverlay();
        if (intent != null && intent.getBooleanExtra("launch_apps", false)) CarModeLauncher.openConfiguredApps(this, intent.getStringExtra("launch_mode"));
        return START_NOT_STICKY;
    }

    private void addOverlay() {
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        physicalLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        String orientation = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getString("overlay_orientation", "auto");
        landscape = "horizontal".equals(orientation) || ("auto".equals(orientation) && physicalLandscape);
        overlay = new FrameLayout(this);
        overlay.setClipChildren(false);
        LinearLayout content = new LinearLayout(this);
        content.setClipChildren(false);
        content.setOrientation(landscape && !expanded ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(8), dp(4), dp(8), dp(4));
        content.setBackground(panelBackground());
        FrameLayout mediaContainer = new FrameLayout(this);
        LinearLayout mediaInfo = new LinearLayout(this);
        mediaInfo.setOrientation(LinearLayout.HORIZONTAL);
        mediaInfo.setGravity(Gravity.CENTER_VERTICAL);
        mediaInfo.setPadding(dp(expanded ? 8 : 6), dp(expanded ? 8 : 5), dp(expanded ? 10 : 8), dp(expanded ? 8 : 5));
        mediaInfo.setBackground(rippleBackground(mediaBackground(), Color.rgb(90, 90, 110)));
        mediaInfo.setOnClickListener(v -> openConfigured("music_app"));
        mediaInfo.setOnTouchListener(this::dragOverlay);
        artwork = new ImageView(this);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setBackgroundColor(Color.rgb(65, 27, 40));
        int artworkSize = controlDp(expanded ? 58 : 44);
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
        expandButton.setOnClickListener(v -> {
            v.animate().rotationBy(expanded ? -180f : 180f).setDuration(180).start();
            toggleExpanded();
        });
        mediaInfo.addView(expandButton, mediaButtonParams());
        mediaContainer.addView(mediaInfo, new FrameLayout.LayoutParams(-1, -1));
        int mediaWidth = landscape && !expanded
                ? dp(240)
                : Math.min(dp(480), getResources().getDisplayMetrics().widthPixels - dp(32));
        content.addView(mediaContainer, new LinearLayout.LayoutParams(Math.max(dp(280), mediaWidth), controlDp(expanded ? 102 : 58)));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        if (!landscape || expanded) content.addView(controls, new LinearLayout.LayoutParams(-2, controlDp(expanded ? 84 : 54)));
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
            (landscape && !expanded ? content : controls).addView(b, buttonParams());
        }
        if (expanded) {
            if (landscape) addExpandedActions(content);
            else {
                LinearLayout extra = new LinearLayout(this);
                extra.setOrientation(LinearLayout.HORIZONTAL);
                extra.setGravity(Gravity.CENTER);
                addExpandedActions(extra);
                content.addView(extra, new LinearLayout.LayoutParams(-2, controlDp(84)));
            }
        }
        // O content fica num FrameLayout independente para que o tamanho da janela
        // possa acompanhar a escala sem esticar novamente os botões por dentro.
        overlay.addView(content, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        // O redimensionador pertence ao player completo, separado do botão de expandir.
        addResizeHandle(overlay);
        float savedScale = clampOverlayScale(getSharedPreferences("dashboard_auto", MODE_PRIVATE).getFloat("overlay_scale", 1f));
        overlay.setScaleX(savedScale);
        overlay.setScaleY(savedScale);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getInt("overlay_x", dp(16));
        params.y = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getInt("overlay_y", defaultOverlayY());
        windowParams = params;
        try {
            manager.addView(overlay, params);
            overlay.post(() -> {
                baseOverlayWidth = overlay.getMeasuredWidth();
                baseOverlayHeight = overlay.getMeasuredHeight();
                positionResizeHandle();
                syncWindowBounds(savedScale);
                clampOverlayPosition();
            });
            overlay.setAlpha(0f);
            overlay.setScaleX(savedScale * .92f);
            overlay.setScaleY(savedScale * .92f);
            overlay.animate().alpha(1f).scaleX(savedScale).scaleY(savedScale).setDuration(220).start();
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
        background.setStroke(dp(1), Color.argb(150, Color.red(accentColor()), Color.green(accentColor()), Color.blue(accentColor())));
        return background;
    }

    private GradientDrawable mediaBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(31, 31, 39));
        background.setCornerRadius(dp(14));
        return background;
    }

    private RippleDrawable rippleBackground(GradientDrawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    private void updateTrack() {
        if (track == null) return;
        String value = MusicController.currentTrack(this);
        boolean trackChanged = lastTrackValue != null && !lastTrackValue.equals(value);
        lastTrackValue = value;
        if (trackChanged) animateTrackChange(value); else renderTrack(value);
        MusicController.PlaybackInfo playback = MusicController.playbackInfo(this);
        if (android.os.SystemClock.uptimeMillis() >= optimisticPlaybackUntil) playingState = playback.playing;
        setPlayButtonState(playingState, false);
        durationMs = playback.durationMs;
        if (progressBar != null && !userSeeking) {
            progressBar.setEnabled(durationMs > 0);
            progressBar.setProgress(durationMs <= 0 ? 0 : (int) Math.min(1000L, playback.positionMs * 1000L / durationMs));
            if (timeLabel != null) timeLabel.setText(formatTime(playback.positionMs) + " / " + formatTime(durationMs));
        }
    }

    private void renderTrack(String value) {
        String[] parts = value.split("\\n", 2);
        track.setText(parts.length > 0 && !parts[0].isBlank() ? parts[0] : "Sem música ativa");
        if (artist != null) artist.setText(parts.length > 1 ? parts[1] : "");
        if (album != null) album.setText(expanded ? MusicController.currentAlbum(this) : "");
        if (artwork != null) {
            Bitmap bitmap = MusicController.currentArtwork(this);
            if (bitmap != renderedArtwork) {
                renderedArtwork = bitmap;
                artwork.animate().cancel();
                artwork.setAlpha(0f);
                if (bitmap != null) artwork.setImageBitmap(bitmap); else artwork.setImageDrawable(null);
                artwork.animate().alpha(1f).setDuration(180).start();
            }
        }
    }

    private void animateTrackChange(String value) {
        if (overlay == null) {
            renderTrack(value);
            return;
        }
        if (trackTransitionRunning) {
            pendingTrackValue = value;
            return;
        }
        trackTransitionRunning = true;
        pendingTrackValue = null;
        float distance = Math.max(dp(96), overlay.getWidth() * .72f);
        overlay.animate()
                .translationX(-distance)
                .alpha(.12f)
                .setDuration(145)
                .withEndAction(() -> {
                    renderTrack(value);
                    overlay.setTranslationX(distance);
                    overlay.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(225)
                            .withEndAction(() -> {
                                trackTransitionRunning = false;
                                if (pendingTrackValue != null && !pendingTrackValue.equals(value)) {
                                    String next = pendingTrackValue;
                                    pendingTrackValue = null;
                                    animateTrackChange(next);
                                }
                            })
                            .start();
                })
                .start();
    }

    private ImageButton actionButton(int icon, String description, boolean accent) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(accent ? accentColor() : Color.WHITE);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setPadding(controlDp(expanded ? 14 : 10), controlDp(expanded ? 14 : 10), controlDp(expanded ? 14 : 10), controlDp(expanded ? 14 : 10));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(accent ? Color.rgb(69, 27, 42) : Color.rgb(36, 36, 45));
        background.setCornerRadius(dp(14));
        button.setBackground(background);
        button.setBackground(rippleBackground(background, Color.rgb(110, 110, 128)));
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
        playButton.animate().rotationBy(180f).alpha(.35f).setDuration(90).withEndAction(() -> {
            if (playButton == null) return;
            playButton.setImageResource(icon);
            playButton.animate().rotation(0f).alpha(1f).setDuration(150).start();
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
        int size = controlDp(expanded ? 72 : 54);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        int margin = controlDp(expanded ? 5 : 2);
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    private boolean dragOverlay(android.view.View view, MotionEvent event) {
        if (windowParams == null || manager == null) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dragActive = true;
            dragTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
            if (dragVelocity != null) dragVelocity.recycle();
            dragVelocity = VelocityTracker.obtain();
            dragVelocity.addMovement(event);
            downX = event.getRawX(); downY = event.getRawY();
            startX = windowParams.x; startY = windowParams.y;
            dragMoved = false;
            if (overlay != null) {
                overlay.animate().cancel();
                overlay.setTranslationX(0f);
                overlay.setTranslationY(0f);
                overlay.setAlpha(1f);
            }
            showDropZone(false);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (dragVelocity != null) dragVelocity.addMovement(event);
            if (Math.abs(event.getRawX() - downX) > dragTouchSlop || Math.abs(event.getRawY() - downY) > dragTouchSlop) dragMoved = true;
            if (dragMoved) {
                boolean movingToTop = event.getRawY() < downY - dp(12);
                setDropZoneMode(movingToTop);
            }
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int draggedX = Math.max(0, startX + (int) (event.getRawX() - downX));
            int draggedY = Math.max(0, startY + (int) (event.getRawY() - downY));
            int playerWidth = visualOverlayWidth();
            int playerHeight = visualOverlayHeight();
            int targetY = Math.max(0, screenHeight - playerHeight - dp(18));
            boolean approachingBottom = event.getRawY() >= downY;
            float normalizedDistance = approachingBottom
                    ? Math.max(0f, Math.min(1f, (event.getRawY() - (screenHeight - dp(320))) / (float) dp(320)))
                    : 0f;
            float magnetProgress = normalizedDistance * normalizedDistance * (3f - 2f * normalizedDistance);
            // O íman atua apenas no eixo vertical. O eixo horizontal deve
            // continuar totalmente livre para o utilizador posicionar o
            // player onde quiser, incluindo tablets e ecrãs largos.
            int nextX = draggedX;
            int nextY = Math.max(0, (int) (draggedY + (targetY - draggedY) * magnetProgress * .9f));
            nextX = Math.min(nextX, Math.max(0, screenWidth - playerWidth));
            nextY = Math.min(nextY, Math.max(0, screenHeight - playerHeight));
            boolean overDropZone = magnetProgress > (dropZoneActive ? .25f : .45f);
            dropZoneActive = overDropZone;
            if (dropZone != null) dropZone.setBackground(dropZoneBackground(overDropZone));
            scheduleDragPosition(nextX, nextY);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (dragVelocity != null) dragVelocity.addMovement(event);
            float velocityY = 0f;
            float velocityX = 0f;
            if (dragVelocity != null) {
                dragVelocity.computeCurrentVelocity(1000);
                velocityY = dragVelocity.getYVelocity();
                velocityX = dragVelocity.getXVelocity();
                dragVelocity.recycle();
                dragVelocity = null;
            }
            dragActive = false;
            dragFramePosted = false;
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                dropZoneActive = false;
                hideDropZone();
                return true;
            }
            if (dragMoved) {
                // O último MOVE pode ainda estar agendado para o frame seguinte.
                // Usa-o já no UP para o snap não ficar um frame atrás do dedo.
                windowParams.x = pendingDragX;
                windowParams.y = pendingDragY;
            }
            float deltaY = event.getRawY() - downY;
            float deltaX = event.getRawX() - downX;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            boolean overTopZone = event.getRawY() <= dp(112);
            boolean overDropZone = event.getRawY() > screenHeight - dp(180);
            dropZoneActive = false;
            hideDropZone();
            boolean verticalSwipe = Math.abs(deltaY) > Math.abs(deltaX) * 1.2f
                    || Math.abs(velocityY) > Math.abs(velocityX) * 1.2f;
            boolean fastUpwardSwipe = velocityY < -dp(900f);
            boolean fastDownwardSwipe = velocityY > dp(900f);
            if (dragMoved && !expanded && overTopZone
                    && (deltaY < -dp(64) || fastUpwardSwipe) && verticalSwipe) {
                toggleExpanded();
                return true;
            }
            if (!dragMoved && Math.abs(deltaX) < dp(12) && Math.abs(deltaY) < dp(12)) {
                view.performClick();
                return true;
            }
            if ((deltaY > dp(84) || fastDownwardSwipe) && overDropZone && verticalSwipe) {
                hideMiniPlayer();
                return true;
            }
            // Não fazer snap lateral no fim do gesto: o arrastamento deve
            // preservar a posição escolhida, sem colar o player à esquerda
            // ou à direita.
            try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
            getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit().putInt("overlay_x", windowParams.x).putInt("overlay_y", windowParams.y).apply();
            return true;
        }
        return true;
    }

    private void scheduleDragPosition(int x, int y) {
        if (overlay == null || windowParams == null || manager == null) return;
        pendingDragX = x;
        pendingDragY = y;
        if (dragFramePosted) return;
        dragFramePosted = true;
        overlay.postOnAnimation(() -> {
            dragFramePosted = false;
            if (!dragActive || overlay == null || windowParams == null || manager == null) return;
            windowParams.x = pendingDragX;
            windowParams.y = pendingDragY;
            try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
        });
    }

    private int visualOverlayWidth() {
        if (overlay == null) return 0;
        int measured = baseOverlayWidth > 0 ? baseOverlayWidth : overlay.getWidth();
        return Math.max(1, Math.round(measured * overlay.getScaleX()));
    }

    private int visualOverlayHeight() {
        if (overlay == null) return 0;
        int measured = baseOverlayHeight > 0 ? baseOverlayHeight : overlay.getHeight();
        return Math.max(1, Math.round(measured * overlay.getScaleY()));
    }

    private int defaultOverlayY() {
        return Math.max(dp(24), getResources().getDisplayMetrics().heightPixels - dp(150));
    }

    private void clampOverlayPosition() {
        if (overlay == null || windowParams == null || manager == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxX = Math.max(0, screenWidth - visualOverlayWidth());
        int maxY = Math.max(0, screenHeight - visualOverlayHeight());
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
        applyOverlayScale(1f, false);
        windowParams.x = dp(16);
        windowParams.y = defaultOverlayY();
        clampOverlayPosition();
    }

    private int controlDp(int value) {
        String size = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getString("overlay_control_size", "normal");
        float factor = "compact".equals(size) ? .90f : ("large".equals(size) ? 1.12f : 1f);
        return Math.max(dp(2), Math.round(dp(value) * factor));
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private void removeOverlay() {
        refreshHandler.removeCallbacks(refreshTrack);
        if (overlay != null) {
            overlay.animate().cancel();
            overlay.setTranslationX(0f);
            overlay.setTranslationY(0f);
        }
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
        renderedArtwork = null;
        playButton = null;
        windowParams = null;
        lastTrackValue = null;
        pendingTrackValue = null;
        trackTransitionRunning = false;
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

    private void showDropZone(boolean top) {
        if (dropZone != null || manager == null) return;
        dropZoneTop = top;
        dropZone = new TextView(this);
        dropZone.setText(top ? "↑  Soltar para expandir  ↑" : "↓  Soltar para fechar  ↓");
        dropZone.setTextColor(Color.WHITE);
        dropZone.setTextSize(12);
        dropZone.setGravity(Gravity.CENTER);
        dropZone.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        dropZone.setBackground(dropZoneBackground(false));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(210), dp(48), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = (top ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        params.y = top ? dp(18) : dp(10);
        dropZoneParams = params;
        try {
            manager.addView(dropZone, params);
            dropZone.setAlpha(0f);
            dropZone.animate().alpha(1f).setDuration(140).start();
        } catch (RuntimeException ignored) { dropZone = null; }
    }

    private void setDropZoneMode(boolean top) {
        if (dropZone == null || dropZoneParams == null || dropZoneTop == top) return;
        dropZoneTop = top;
        dropZone.setText(top ? "↑  Soltar para expandir  ↑" : "↓  Soltar para fechar  ↓");
        dropZoneParams.gravity = (top ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        dropZoneParams.y = top ? dp(18) : dp(10);
        try { manager.updateViewLayout(dropZone, dropZoneParams); } catch (IllegalArgumentException ignored) { }
    }

    private GradientDrawable dropZoneBackground(boolean active) {
        GradientDrawable background = new GradientDrawable();
        int accent = accentColor();
        background.setColor(active ? Color.argb(220, Color.red(accent), Color.green(accent), Color.blue(accent)) : Color.rgb(44, 44, 55));
        background.setCornerRadius(dp(20));
        background.setStroke(dp(1), active ? Color.rgb(Math.min(255, Color.red(accent) + 55), Math.min(255, Color.green(accent) + 55), Math.min(255, Color.blue(accent) + 55)) : Color.rgb(100, 100, 115));
        return background;
    }

    private int accentColor() {
        String key = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getString("accent_color", "blue");
        if ("pink".equals(key)) return Color.rgb(255, 55, 95);
        if ("green".equals(key)) return Color.rgb(48, 209, 88);
        if ("purple".equals(key)) return Color.rgb(191, 90, 242);
        if ("amber".equals(key)) return Color.rgb(255, 179, 64);
        return Color.rgb(10, 132, 255);
    }

    private void hideDropZone() {
        dropZoneActive = false;
        if (dropZone == null) return;
        TextView current = dropZone;
        dropZone = null;
        dropZoneParams = null;
        current.animate().alpha(0f).setDuration(100).withEndAction(() -> {
            try { if (manager != null) manager.removeView(current); } catch (IllegalArgumentException ignored) { }
        }).start();
    }

    private LinearLayout.LayoutParams mediaButtonParams() {
        int size = controlDp(expanded ? 64 : 52);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private void addResizeHandle(FrameLayout parent) {
        ImageButton handle = new ImageButton(this);
        resizeHandle = handle;
        handle.setImageResource(R.drawable.ic_resize);
        handle.setContentDescription("Redimensionar player");
        handle.setTooltipText("Arrastar para redimensionar");
        // Ícone discreto com área de toque confortável para utilização em condução.
        handle.setPadding(dp(7), dp(7), dp(7), dp(7));
        handle.setMinimumWidth(dp(32));
        handle.setMinimumHeight(dp(32));
        handle.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        handle.setAlpha(.82f);
        handle.setBackgroundColor(Color.TRANSPARENT);
        // Área visual pequena no canto; os 44dp continuam a ser apenas a área
        // de toque para não obrigar a acertar num ícone minúsculo.
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP | Gravity.START);
        handleParams.setMargins(0, 0, 0, 0);
        parent.addView(handle, handleParams);
        final float[] initialScale = {1f};
        final float[] initialX = {0f};
        final float[] initialY = {0f};
        handle.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                initialScale[0] = overlay.getScaleX();
                pendingResizeScale = initialScale[0];
                initialX[0] = event.getRawX();
                initialY[0] = event.getRawY();
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float horizontalDelta = event.getRawX() - initialX[0];
                float verticalDelta = event.getRawY() - initialY[0];
                // O gesto acompanha a diagonal do canto e mantém a proporção do player inteiro.
                float diagonalDelta = (horizontalDelta + verticalDelta) * .5f;
                float delta = diagonalDelta / dp(300f);
                float scale = clampOverlayScale(initialScale[0] + delta);
                scheduleOverlayScale(scale);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                resizeFramePosted = false;
                applyOverlayScale(pendingResizeScale, true);
                if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                return true;
            }
            return true;
        });
    }

    private void scheduleOverlayScale(float scale) {
        if (overlay == null) return;
        pendingResizeScale = clampOverlayScale(scale);
        if (resizeFramePosted) return;
        resizeFramePosted = true;
        overlay.postOnAnimation(() -> {
            resizeFramePosted = false;
            if (overlay != null) applyOverlayScale(pendingResizeScale, false);
        });
    }

    private void applyOverlayScale(float scale, boolean persist) {
        if (overlay == null) return;
        float clampedScale = clampOverlayScale(scale);
        overlay.setPivotX(0f);
        overlay.setPivotY(0f);
        overlay.setScaleX(clampedScale);
        overlay.setScaleY(clampedScale);
        positionResizeHandle();
        syncWindowBounds(clampedScale);
        // Ao aumentar o player, conserva a posição escolhida mas garante que
        // a nova caixa visual continua totalmente dentro do ecrã.
        clampOverlayPosition();
        if (persist) {
            getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit()
                    .putFloat("overlay_scale", clampedScale)
                    .apply();
        }
    }

    private float clampOverlayScale(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) return 1f;
        float maxScale = maxOverlayScaleForScreen();
        float minScale = Math.min(MIN_OVERLAY_SCALE, maxScale);
        return Math.max(minScale, Math.min(maxScale, scale));
    }

    private float maxOverlayScaleForScreen() {
        if (baseOverlayWidth <= 0 || baseOverlayHeight <= 0) return MAX_OVERLAY_SCALE;
        int safeWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels - dp(16));
        int safeHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels - dp(16));
        float widthLimit = safeWidth / (float) baseOverlayWidth;
        float heightLimit = safeHeight / (float) baseOverlayHeight;
        return Math.max(.1f, Math.min(MAX_OVERLAY_SCALE, Math.min(widthLimit, heightLimit)));
    }

    private void positionResizeHandle() {
        if (resizeHandle == null || overlay == null || baseOverlayWidth <= 0 || baseOverlayHeight <= 0) return;
        resizeHandle.setX(Math.max(0, baseOverlayWidth - resizeHandle.getMeasuredWidth()));
        resizeHandle.setY(Math.max(0, baseOverlayHeight - resizeHandle.getMeasuredHeight()));
    }

    private void syncWindowBounds(float scale) {
        if (overlay == null || windowParams == null || manager == null || baseOverlayWidth <= 0 || baseOverlayHeight <= 0) return;
        // A View é escalada visualmente, mas a janela mantém o tamanho lógico
        // original. Reduzir também os bounds da WindowManager fazia o conteúdo
        // escalado ser recortado e deixava os targets tácteis deslocados.
        // Assim, a transformação é aplicada ao player inteiro e o sistema de
        // toque continua a usar a mesma origem (canto superior esquerdo).
        windowParams.width = Math.max(1, baseOverlayWidth);
        windowParams.height = Math.max(1, baseOverlayHeight);
        try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
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
            refreshHandler.postDelayed(() -> MusicController.playWhenReady(this), 650L);
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
        active = false;
        super.onDestroy();
    }
    public static boolean isActive() { return active; }
    @Override public IBinder onBind(Intent intent) { return null; }
}
