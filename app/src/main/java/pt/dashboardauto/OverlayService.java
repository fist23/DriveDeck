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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
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
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.content.res.Configuration;

public class OverlayService extends Service {
    private static volatile boolean active;
    // Permite compactar o player para libertar espaço, mantendo sempre os
    // controlos utilizáveis durante a condução.
    private static final float MIN_OVERLAY_SCALE = .70f;
    private static final float MAX_OVERLAY_SCALE = 1.25f;
    private static final String ACTION_CLOSE_PLAYER = "pt.dashboardauto.action.CLOSE_PLAYER";
    public static final String ACTION_CLOSE_EVERYTHING = "pt.dashboardauto.action.CLOSE_EVERYTHING";
    private static final String ACTION_RESET_LAYOUT = "pt.dashboardauto.action.RESET_LAYOUT";
    private static final String ACTION_REBUILD_LAYOUT = "pt.dashboardauto.action.REBUILD_LAYOUT";
    private WindowManager manager;
    private FrameLayout overlay;
    private TextView track;
    private TextView artist;
    private TextView timeLabel;
    private ImageView artwork;
    private android.view.View musicInfoContainer;
    private Bitmap renderedArtwork;
    private ImageButton playButton;
    private AnimatedWaveDrawable playingDrawable;
    private ImageButton resizeHandle;
    private SeekBar progressBar;
    private android.widget.ProgressBar compactProgressBar;
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
    private boolean expanded;
    private boolean miniPlayerHidden;
    private WindowManager.LayoutParams windowParams;
    private android.view.View playerContent;
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
    private int dragGestureId;
    private float pendingResizeScale = 1f;
    private boolean resizeFramePosted;
    private int resizeGestureId;
    private boolean layoutTransitionRunning;
    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshTrack = new Runnable() {
        @Override public void run() {
            updateTrack();
            refreshHandler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        active = true;
        // Cada sessão começa como uma Dynamic Island compacta. A expansão é
        // temporária e acontece apenas por interação direta do utilizador.
        expanded = false;
        getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit()
                .putBoolean("overlay_expanded", false)
                .remove("overlay_x")
                .remove("overlay_y")
                .remove("overlay_scale")
                .apply();
        createNotification();
        if (PermissionManager.canDrawOverlay(this)) addOverlay();
        else stopSelf();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CLOSE_EVERYTHING.equals(intent.getAction())) {
            closeEverything();
            return START_NOT_STICKY;
        }
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
        boolean launchApps = intent != null && intent.getBooleanExtra("launch_apps", false);
        if (overlay == null && PermissionManager.canDrawOverlay(this)) addOverlay();
        if (launchApps) CarModeLauncher.openConfiguredApps(this, intent.getStringExtra("launch_mode"));
        return START_NOT_STICKY;
    }

    private void addOverlay() {
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        baseOverlayWidth = 0;
        baseOverlayHeight = 0;
        overlay = new FrameLayout(this);
        overlay.setClipChildren(false);
        LinearLayout content = new LinearLayout(this);
        content.setClipChildren(false);
        content.setOrientation(!expanded ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setClickable(true);
        content.setOnClickListener(v -> {
            if (!expanded) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                toggleExpanded();
            }
        });
        content.setPadding(dp(expanded ? 8 : 4), dp(expanded ? 4 : 3), dp(expanded ? 8 : 4), dp(expanded ? 4 : 3));
        content.setBackground(panelBackground());
        FrameLayout mediaContainer = new FrameLayout(this);
        musicInfoContainer = mediaContainer;
        LinearLayout mediaInfo = new LinearLayout(this);
        mediaInfo.setOrientation(LinearLayout.HORIZONTAL);
        mediaInfo.setGravity(Gravity.CENTER_VERTICAL);
        mediaInfo.setPadding(dp(expanded ? 8 : 4), dp(expanded ? 8 : 3), dp(expanded ? 10 : 4), dp(expanded ? 8 : 3));
        mediaInfo.setBackground(rippleBackground(mediaBackground(), Color.rgb(90, 90, 110)));
        mediaInfo.setOnClickListener(v -> {
            if (!expanded) toggleExpanded();
            else openConfigured("music_app");
        });
        artwork = new ImageView(this);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setBackgroundColor(Color.rgb(65, 27, 40));
        int artworkSize = controlDp(expanded ? 58 : 28);
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
        labels.addView(track, new LinearLayout.LayoutParams(-1, -2));
        labels.addView(artist, new LinearLayout.LayoutParams(-1, -2));
        if (!expanded) {
            compactProgressBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            compactProgressBar.setMax(1000);
            compactProgressBar.setProgress(0);
            compactProgressBar.setIndeterminate(false);
            compactProgressBar.setVisibility(android.view.View.GONE);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                compactProgressBar.setProgressTintList(ColorStateList.valueOf(accentColor()));
                compactProgressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(62, 62, 72)));
            }
            labels.addView(compactProgressBar, new LinearLayout.LayoutParams(-1, dp(3)));
        }
        if (expanded) {
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
        // In compact mode the island behaves like an iPhone Dynamic Island:
        // artwork and the expand affordance remain visible, while metadata is
        // revealed only after the user opens the player.
        labels.setVisibility(expanded ? android.view.View.VISIBLE : android.view.View.GONE);
        mediaInfo.addView(labels, new LinearLayout.LayoutParams(0, -1, 1f));
        if (expanded) {
            ImageButton expandButton = actionButton(R.drawable.ic_collapse, "Recolher player", true);
            expandButton.setOnClickListener(v -> {
                v.animate().rotationBy(-180f).setDuration(180).start();
                toggleExpanded();
            });
            mediaInfo.addView(expandButton, mediaButtonParams());
        }
        mediaContainer.setContentDescription(expanded
                ? "Player expandido"
                : "Dynamic Island do DriveDeck. Toque para expandir");
        mediaContainer.setOnClickListener(v -> {
            if (!expanded) toggleExpanded();
        });
        mediaContainer.addView(mediaInfo, new FrameLayout.LayoutParams(-1, -1));
        int availableWidth = Math.max(dp(1), getResources().getDisplayMetrics().widthPixels - dp(16));
        int compactActionWidth = controlDp(42) + dp(4);
        int compactAvailableWidth = Math.max(dp(1), availableWidth - compactActionWidth);
        int compactMediaWidth = controlDp(42);
        int mediaWidth = !expanded ? Math.min(compactMediaWidth, compactAvailableWidth) : Math.min(dp(480), availableWidth);
        int minimumMediaWidth = Math.min(dp(expanded ? 280 : 42), expanded ? availableWidth : compactAvailableWidth);
        content.addView(mediaContainer, new LinearLayout.LayoutParams(Math.max(minimumMediaWidth, mediaWidth), controlDp(expanded ? 108 : 42)));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-2, controlDp(expanded ? 72 : 46));
        if (!expanded && isCenterIslandPosition()) {
            controlsParams.setMargins(cutoutGapWidth(), 0, 0, 0);
        }
        content.addView(controls, controlsParams);
        int[] icons = new int[]{R.drawable.ic_skip_previous, R.drawable.ic_play, R.drawable.ic_skip_next};
        String[] descriptions = {"Faixa anterior", "Reproduzir ou pausar", "Faixa seguinte"};
        for (int i = 0; i < icons.length; i++) {
            if (!expanded && i != 1) continue;
            ImageButton b = actionButton(icons[i], descriptions[i], i == 1 || i == icons.length - 1);
            if (i == 1) {
                playButton = b;
                setPlayButtonState(playingState, false);
            }
            final int actionIndex = i;
            b.setOnClickListener(v -> handleAction(actionIndex));
            controls.addView(b, buttonParams());
        }
        if (expanded) {
            LinearLayout extra = new LinearLayout(this);
            extra.setOrientation(LinearLayout.HORIZONTAL);
            extra.setGravity(Gravity.CENTER);
            addExpandedActions(extra);
            content.addView(extra, new LinearLayout.LayoutParams(-2, controlDp(58)));
        }
        // O content fica num FrameLayout independente para que o tamanho da janela
        // possa acompanhar a escala sem esticar novamente os botões por dentro.
        overlay.addView(content, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        playerContent = content;
        // A Dynamic Island has one stable size. Valores antigos de resize são
        // ignorados para que a atualização não herde uma escala incompatível.
        final float requestedScale = 1f;
        content.setScaleX(requestedScale);
        content.setScaleY(requestedScale);
        int overlayFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (isCenterIslandPosition()) {
            // A Dynamic Island must occupy the display area behind the status
            // bar/cutout. Without these flags Android repositions overlays
            // below the safe inset, which makes the island visibly detached.
            overlayFlags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, overlayFlags, PixelFormat.TRANSLUCENT);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        // Dynamic Island: posição fixa e centrada no topo. Não há resize nem
        // drag para impedir que o gesto de condução roube toques aos botões.
        applyIslandPosition(params);
        windowParams = params;
        try {
            manager.addView(overlay, params);
            FrameLayout createdOverlay = overlay;
            overlay.setOnApplyWindowInsetsListener((view, insets) -> {
                if (isCenterIslandPosition()) view.post(this::alignIslandToCutout);
                return insets;
            });
            overlay.requestApplyInsets();
            overlay.post(() -> {
                if (overlay != createdOverlay) return;
                measureLogicalContent(content);
                baseOverlayWidth = content.getMeasuredWidth() > 0 ? content.getMeasuredWidth() : overlay.getMeasuredWidth();
                baseOverlayHeight = content.getMeasuredHeight() > 0 ? content.getMeasuredHeight() : overlay.getMeasuredHeight();
                // Mantém o layout lógico estável. Se o WindowManager medir
                // novamente este LinearLayout com os bounds já reduzidos,
                // alguns controlos podem ser comprimidos ou cortados.
                FrameLayout.LayoutParams contentParams = (FrameLayout.LayoutParams) content.getLayoutParams();
                contentParams.width = baseOverlayWidth;
                contentParams.height = baseOverlayHeight;
                content.setLayoutParams(contentParams);
                overlay.requestLayout();
                overlay.post(() -> {
                    if (overlay != createdOverlay) return;
                    // A expansão/recolha recria os controlos. Forçar uma nova
                    // medição aqui evita reutilizar os bounds da janela antiga,
                    // que era o motivo de botões ficarem cortados após resize.
                    measureLogicalContent(content);
                    int measuredWidth = content.getMeasuredWidth();
                    int measuredHeight = content.getMeasuredHeight();
                    if (measuredWidth > 0 && measuredHeight > 0
                            && (measuredWidth != baseOverlayWidth || measuredHeight != baseOverlayHeight)) {
                        baseOverlayWidth = measuredWidth;
                        baseOverlayHeight = measuredHeight;
                        FrameLayout.LayoutParams stableParams = (FrameLayout.LayoutParams) content.getLayoutParams();
                        stableParams.width = baseOverlayWidth;
                        stableParams.height = baseOverlayHeight;
                        content.setLayoutParams(stableParams);
                        overlay.requestLayout();
                    }
                    float effectiveScale = 1f;
                    content.setScaleX(effectiveScale * (expanded ? .94f : .92f));
                    content.setScaleY(effectiveScale * (expanded ? .68f : .92f));
                    content.setAlpha(expanded ? .86f : 1f);
                    overlay.setPivotX(baseOverlayWidth / 2f);
                    overlay.setPivotY(0f);
                    overlay.setScaleX(expanded ? .92f : .96f);
                    overlay.setScaleY(expanded ? .76f : .90f);
                    alignIslandToCutout();
                    clampOverlayPosition();
                    content.animate()
                            .scaleX(effectiveScale)
                            .scaleY(effectiveScale)
                            .alpha(1f)
                            .setInterpolator(new OvershootInterpolator(1.08f))
                            .setDuration(expanded ? 360 : 260)
                            .start();
                    overlay.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setInterpolator(new DecelerateInterpolator(1.4f))
                            .setDuration(expanded ? 340 : 240)
                            .start();
                });
            });
            overlay.setAlpha(0f);
            refreshHandler.post(refreshTrack);
        } catch (WindowManager.BadTokenException | SecurityException error) {
            overlay = null;
            stopSelf();
        }
    }

    private void measureLogicalContent(android.view.View content) {
        int unspecified = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED);
        content.measure(unspecified, unspecified);
    }

    private GradientDrawable panelBackground() {
        GradientDrawable background = expanded
                ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
                    blendColor(accentColor(), Color.BLACK, .42f),
                    Color.rgb(18, 25, 58),
                    Color.rgb(7, 8, 17)})
                : new GradientDrawable();
        if (!expanded) background.setColor(Color.rgb(5, 6, 9));
        background.setCornerRadius(dp(expanded ? 22 : 32));
        background.setStroke(dp(1), Color.argb(150, Color.red(accentColor()), Color.green(accentColor()), Color.blue(accentColor())));
        return background;
    }

    private int blendColor(int foreground, int background, float backgroundWeight) {
        float weight = Math.max(0f, Math.min(1f, backgroundWeight));
        float foregroundWeight = 1f - weight;
        return Color.rgb(
                Math.round(Color.red(foreground) * foregroundWeight + Color.red(background) * weight),
                Math.round(Color.green(foreground) * foregroundWeight + Color.green(background) * weight),
                Math.round(Color.blue(foreground) * foregroundWeight + Color.blue(background) * weight));
    }

    private GradientDrawable mediaBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(expanded ? Color.rgb(31, 31, 39) : Color.rgb(12, 13, 17));
        background.setCornerRadius(dp(expanded ? 14 : 24));
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
        if (compactProgressBar != null && !userSeeking) {
            compactProgressBar.setVisibility(durationMs > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
            compactProgressBar.setEnabled(durationMs > 0);
            compactProgressBar.setProgress(durationMs <= 0 ? 0 : (int) Math.min(1000L, playback.positionMs * 1000L / durationMs));
        }
    }

    private void renderTrack(String value) {
        if (track == null) return;
        String[] parts = value.split("\\n", 2);
        boolean hasTrack = parts.length > 0 && !parts[0].isBlank() && !"Sem música ativa".equals(parts[0]);
        track.setText(hasTrack ? parts[0] : "Sem música ativa");
        if (artist != null) artist.setText(hasTrack ? (parts.length > 1 ? parts[1] : "") : "Toque para escolher áudio");
        if (musicInfoContainer != null) {
            musicInfoContainer.setContentDescription(hasTrack
                    ? "A tocar: " + parts[0] + (parts.length > 1 ? ", por " + parts[1] : "")
                    : "Sem música ativa. Toque para escolher áudio");
        }
        if (artwork != null) {
            Bitmap bitmap = MusicController.currentArtwork(this);
            if (bitmap != renderedArtwork || artwork.getDrawable() == null) {
                renderedArtwork = bitmap;
                artwork.animate().cancel();
                artwork.setAlpha(0f);
                if (bitmap != null) artwork.setImageBitmap(bitmap); else artwork.setImageResource(R.drawable.ic_music);
                artwork.animate().alpha(1f).setDuration(180).start();
            }
        }
    }

    private void animateTrackChange(String value) {
        if (overlay == null || track == null || musicInfoContainer == null) return;
        if (trackTransitionRunning) {
            pendingTrackValue = value;
            return;
        }
        trackTransitionRunning = true;
        pendingTrackValue = null;
        FrameLayout transitionOverlay = overlay;
        android.view.View transitionContainer = musicInfoContainer;
        float distance = Math.max(dp(72), transitionContainer.getWidth() * .42f);
        transitionContainer.animate()
                .translationX(-distance)
                .translationY(-dp(5))
                .scaleX(.96f)
                .scaleY(.96f)
                .alpha(.12f)
                .setDuration(160)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> {
                    if (overlay != transitionOverlay || musicInfoContainer != transitionContainer || track == null) {
                        trackTransitionRunning = false;
                        return;
                    }
                    renderTrack(value);
                    transitionContainer.setTranslationX(distance);
                    transitionContainer.setTranslationY(dp(5));
                    transitionContainer.setScaleX(.96f);
                    transitionContainer.setScaleY(.96f);
                    transitionContainer.animate()
                            .translationX(0f)
                            .translationY(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(460)
                            .setInterpolator(new OvershootInterpolator(1.35f))
                            .withEndAction(() -> {
                                if (overlay != transitionOverlay || musicInfoContainer != transitionContainer) {
                                    trackTransitionRunning = false;
                                    return;
                                }
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
        boolean showPlayingWaves = playing && !expanded;
        playButton.animate().cancel();
        playButton.setScaleX(1f);
        playButton.setScaleY(1f);
        if (!showPlayingWaves && playingDrawable != null) {
            playingDrawable.stop();
            playingDrawable = null;
        }
        if (!animate) {
            playButton.setRotation(0f);
            playButton.setAlpha(1f);
            if (showPlayingWaves) {
                playingDrawable = new AnimatedWaveDrawable(accentColor());
                playButton.setImageDrawable(playingDrawable);
            } else {
                playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            }
            return;
        }
        // Atualiza o estado antes da animação para que toques rápidos nunca
        // deixem uma animação anterior restaurar o ícone errado.
        if (showPlayingWaves) {
            playingDrawable = new AnimatedWaveDrawable(accentColor());
            playButton.setImageDrawable(playingDrawable);
        } else {
            playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        }
        playButton.setRotation(0f);
        playButton.setScaleX(.84f);
        playButton.setScaleY(.84f);
        playButton.setAlpha(.55f);
        playButton.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setInterpolator(new OvershootInterpolator(1.1f))
                .setDuration(180)
                .start();
    }

    /** Indicador leve de reprodução para o botão direito do player minimizado. */
    private static final class AnimatedWaveDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.animation.ValueAnimator animator;
        private final float[] phases = new float[]{0f, 1.7f, 3.2f, 4.8f};

        AnimatedWaveDrawable(int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            animator = android.animation.ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
            animator.setDuration(920L);
            animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            animator.setInterpolator(new android.view.animation.LinearInterpolator());
            animator.addUpdateListener(value -> invalidateSelf());
            animator.start();
        }

        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            if (bounds.isEmpty()) return;
            float centerY = bounds.centerY();
            float barWidth = Math.max(2f, bounds.width() * .13f);
            float gap = Math.max(2f, bounds.width() * .08f);
            float totalWidth = barWidth * 4f + gap * 3f;
            float left = bounds.centerX() - totalWidth / 2f;
            float time = animator.getAnimatedFraction() * (float) (Math.PI * 2);
            for (int i = 0; i < 4; i++) {
                float wave = (float) ((Math.sin(time * 1.35f + phases[i]) + 1f) * .5f);
                float height = bounds.height() * (.28f + wave * .48f);
                float top = centerY - height / 2f;
                canvas.drawRoundRect(left, top, left + barWidth, top + height,
                        barWidth / 2f, barWidth / 2f, paint);
                left += barWidth + gap;
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override protected void onBoundsChange(android.graphics.Rect bounds) { invalidateSelf(); }
        void stop() { animator.cancel(); invalidateSelf(); }
    }

    private void addExpandedActions(LinearLayout parent) {
        ImageButton navigation = actionButton(R.drawable.ic_map, "Abrir navegação", false);
        navigation.setOnClickListener(v -> openConfigured("navigation_app"));
        parent.addView(navigation, secondaryButtonParams());
        ImageButton music = actionButton(R.drawable.ic_music, "Escolher app de áudio", false);
        music.setOnClickListener(v -> showAudioChooser());
        parent.addView(music, secondaryButtonParams());
        ImageButton close = actionButton(R.drawable.ic_close, "Opções para fechar", true);
        close.setOnClickListener(v -> showCloseOptions());
        parent.addView(close, secondaryButtonParams());
    }

    private LinearLayout.LayoutParams secondaryButtonParams() {
        int size = controlDp(48);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        int margin = dp(4);
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    private LinearLayout.LayoutParams buttonParams() {
        int size = controlDp(expanded ? 64 : 42);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        int margin = controlDp(expanded ? 5 : 2);
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    private boolean dragOverlay(android.view.View view, MotionEvent event) {
        if (windowParams == null || manager == null) return false;
        if (layoutTransitionRunning) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dragGestureId++;
            dragActive = true;
            dragTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
            if (dragVelocity != null) dragVelocity.recycle();
            dragVelocity = VelocityTracker.obtain();
            dragVelocity.addMovement(event);
            downX = event.getRawX(); downY = event.getRawY();
            startX = windowParams.x; startY = windowParams.y;
            pendingDragX = startX;
            pendingDragY = startY;
            dragMoved = false;
            if (musicInfoContainer != null) {
                musicInfoContainer.animate().cancel();
                musicInfoContainer.setTranslationX(0f);
                musicInfoContainer.setAlpha(1f);
            }
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
            // A drop zone existe apenas na parte inferior e serve exclusivamente
            // para fechar o mini player. Nunca a mover para o topo: isso ocupava
            // espaço e impedia posicionar o player junto ao limite superior.
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
                dragGestureId++;
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
            boolean overDropZone = event.getRawY() > screenHeight - dp(180);
            dropZoneActive = false;
            hideDropZone();
            boolean verticalSwipe = Math.abs(deltaY) > Math.abs(deltaX) * 1.2f
                    || Math.abs(velocityY) > Math.abs(velocityX) * 1.2f;
            boolean fastDownwardSwipe = velocityY > dp(900f);
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
            persistOverlayPosition();
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
        FrameLayout scheduledOverlay = overlay;
        int scheduledGestureId = dragGestureId;
        overlay.postOnAnimation(() -> {
            dragFramePosted = false;
            if (!dragActive || dragGestureId != scheduledGestureId || overlay != scheduledOverlay || windowParams == null || manager == null) return;
            windowParams.x = pendingDragX;
            windowParams.y = pendingDragY;
            try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
        });
    }

    private int visualOverlayWidth() {
        if (overlay == null) return 0;
        int measured = baseOverlayWidth > 0 ? baseOverlayWidth : overlay.getWidth();
        float scale = playerContent == null ? 1f : playerContent.getScaleX();
        return Math.max(1, Math.round(measured * scale));
    }

    private int visualOverlayHeight() {
        if (overlay == null) return 0;
        int measured = baseOverlayHeight > 0 ? baseOverlayHeight : overlay.getHeight();
        float scale = playerContent == null ? 1f : playerContent.getScaleY();
        return Math.max(1, Math.round(measured * scale));
    }

    private int defaultOverlayY() {
        return Math.max(dp(24), getResources().getDisplayMetrics().heightPixels - dp(150));
    }

    private int topIslandY() {
        int statusBarId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusBarHeight = statusBarId == 0 ? dp(24) : getResources().getDimensionPixelSize(statusBarId);
        android.view.DisplayCutout cutout = displayCutout();
        int cutoutInset = cutout == null ? 0 : cutout.getSafeInsetTop();
        if (isCenterIslandPosition() && cutout != null && !cutout.getBoundingRects().isEmpty()) return cutoutAlignedY();
        if (isCenterIslandPosition()) return 0;
        return Math.max(statusBarHeight, cutoutInset) + dp(8);
    }

    private boolean isCenterIslandPosition() {
        return "center".equals(getSharedPreferences("dashboard_auto", MODE_PRIVATE)
                .getString("overlay_position", "center"));
    }

    private int cutoutAlignedY() {
        android.graphics.Rect selected = centralCutout();
        if (selected == null) return dp(8);
        int islandHeight = baseOverlayHeight > 0 ? baseOverlayHeight : (expanded ? dp(180) : dp(42));
        if (!expanded) {
            // No modo compacto, os controlos ficam de cada lado do recorte.
            // O vazio central deixa a câmara visível, como no Dynamic Island.
            return Math.max(0, selected.top + (selected.height() - islandHeight) / 2);
        }
        return Math.max(selected.bottom + dp(4), dp(4));
    }

    private int cutoutGapWidth() {
        android.graphics.Rect selected = centralCutout();
        if (selected == null) return dp(16);
        return Math.max(dp(18), Math.min(dp(96), selected.width() + dp(12)));
    }

    private android.view.DisplayCutout displayCutout() {
        if (android.os.Build.VERSION.SDK_INT < 28) return null;
        if (android.os.Build.VERSION.SDK_INT >= 30 && manager != null) {
            android.view.WindowMetrics metrics = manager.getMaximumWindowMetrics();
            android.view.WindowInsets insets = metrics.getWindowInsets();
            if (insets.getDisplayCutout() != null) return insets.getDisplayCutout();
        }
        if (overlay != null && overlay.getRootWindowInsets() != null) {
            return overlay.getRootWindowInsets().getDisplayCutout();
        }
        return null;
    }

    private android.graphics.Rect centralCutout() {
        android.view.DisplayCutout cutout = displayCutout();
        if (cutout == null || cutout.getBoundingRects().isEmpty()) return null;
        int screenCenter = getResources().getDisplayMetrics().widthPixels / 2;
        android.graphics.Rect selected = null;
        int bestDistance = Integer.MAX_VALUE;
        for (android.graphics.Rect rect : cutout.getBoundingRects()) {
            int distance = Math.abs(rect.centerX() - screenCenter);
            if (distance < bestDistance) {
                bestDistance = distance;
                selected = rect;
            }
        }
        return selected;
    }

    private void alignIslandToCutout() {
        if (!isCenterIslandPosition() || overlay == null || windowParams == null || manager == null) return;
        android.graphics.Rect cutout = centralCutout();
        if (cutout == null) return;
        int islandWidth = visualOverlayWidth();
        int maxX = Math.max(0, getResources().getDisplayMetrics().widthPixels - islandWidth);
        int targetX = Math.max(0, Math.min(maxX, cutout.centerX() - islandWidth / 2));
        int targetY = topIslandY();
        int centerLeftGravity = Gravity.TOP | Gravity.LEFT;
        if (windowParams.gravity == centerLeftGravity && windowParams.x == targetX && windowParams.y == targetY) return;
        windowParams.gravity = centerLeftGravity;
        windowParams.x = targetX;
        windowParams.y = targetY;
        try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
    }

    private void applyIslandPosition(WindowManager.LayoutParams params) {
        String position = getSharedPreferences("dashboard_auto", MODE_PRIVATE)
                .getString("overlay_position", "center");
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.x = 0;
        params.y = topIslandY();
        if ("left".equals(position)) {
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = dp(8);
        } else if ("right".equals(position)) {
            params.gravity = Gravity.TOP | Gravity.RIGHT;
            params.x = dp(8);
        }
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
        persistOverlayPosition();
    }

    private void persistOverlayPosition() {
        if (windowParams == null) return;
        getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit()
                .putInt("overlay_x", windowParams.x)
                .putInt("overlay_y", windowParams.y)
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
        applyIslandPosition(windowParams);
        try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
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
        layoutTransitionRunning = false;
        dragActive = false;
        dragMoved = false;
        dragGestureId++;
        dragFramePosted = false;
        resizeFramePosted = false;
        resizeGestureId++;
        if (dragVelocity != null) {
            dragVelocity.recycle();
            dragVelocity = null;
        }
        if (overlay != null) {
            overlay.animate().cancel();
            overlay.setTranslationX(0f);
            overlay.setTranslationY(0f);
        }
        if (musicInfoContainer != null) {
            musicInfoContainer.animate().cancel();
            musicInfoContainer.setTranslationX(0f);
            musicInfoContainer.setAlpha(1f);
        }
        if (overlay != null && manager != null) {
            try { manager.removeView(overlay); } catch (IllegalArgumentException ignored) { }
        }
        hideDropZone();
        overlay = null;
        track = null;
        artist = null;
        timeLabel = null;
        progressBar = null;
        compactProgressBar = null;
        durationMs = 0L;
        artwork = null;
        musicInfoContainer = null;
        renderedArtwork = null;
        if (playingDrawable != null) playingDrawable.stop();
        playingDrawable = null;
        playButton = null;
        windowParams = null;
        lastTrackValue = null;
        pendingTrackValue = null;
        trackTransitionRunning = false;
        playerContent = null;
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
            try { startActivity(launch); } catch (RuntimeException ignored) { }
            return;
        }
        if (index == 4) { toggleExpanded(); return; }
    }

    private void toggleExpanded() {
        boolean nextState = !expanded;
        if (overlay == null || layoutTransitionRunning) return;
        layoutTransitionRunning = true;
        overlay.setPivotX(overlay.getWidth() / 2f);
        overlay.setPivotY(0f);
        overlay.animate()
                .alpha(0f)
                .scaleX(nextState ? 1.04f : .90f)
                .scaleY(nextState ? .72f : .90f)
                .translationY(nextState ? -dp(2) : -dp(4))
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .setDuration(nextState ? 135 : 170)
                .withEndAction(() -> {
            expanded = nextState;
            getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit().putBoolean("overlay_expanded", expanded).apply();
            removeOverlay();
            addOverlay();
            layoutTransitionRunning = false;
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
        sendBroadcast(new Intent(ACTION_CLOSE_EVERYTHING).setPackage(getPackageName()));
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

    private void showDropZone(boolean ignoredTop) {
        if (dropZone != null || manager == null) return;
        dropZoneTop = false;
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
        dropZoneParams = params;
        try {
            manager.addView(dropZone, params);
            dropZone.setAlpha(0f);
            dropZone.animate().alpha(1f).setDuration(140).start();
        } catch (RuntimeException ignored) { dropZone = null; }
    }

    private void setDropZoneMode(boolean top) {
        // Mantido como ponto de compatibilidade para chamadas antigas. A zona
        // nunca muda de posição: fica sempre em baixo para fechar o player.
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
        int size = controlDp(expanded ? 56 : 36);
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
                resizeGestureId++;
                initialScale[0] = playerContent == null ? 1f : playerContent.getScaleX();
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
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    applyOverlayScale(pendingResizeScale, true);
                    view.performClick();
                } else {
                    resizeGestureId++;
                    pendingResizeScale = initialScale[0];
                    applyOverlayScale(initialScale[0], false);
                }
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
        FrameLayout scheduledOverlay = overlay;
        int scheduledGestureId = resizeGestureId;
        overlay.postOnAnimation(() -> {
            resizeFramePosted = false;
            if (overlay == scheduledOverlay && resizeGestureId == scheduledGestureId) applyOverlayScale(pendingResizeScale, false);
        });
    }

    private void applyOverlayScale(float scale, boolean persist) {
        if (overlay == null) return;
        float clampedScale = clampOverlayScale(scale);
        if (playerContent != null) {
            playerContent.setPivotX(0f);
            playerContent.setPivotY(0f);
            playerContent.setScaleX(clampedScale);
            playerContent.setScaleY(clampedScale);
        }
        positionResizeHandle(clampedScale);
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

    private void positionResizeHandle(float scale) {
        if (resizeHandle == null || overlay == null || baseOverlayWidth <= 0 || baseOverlayHeight <= 0) return;
        int handleWidth = resizeHandle.getMeasuredWidth() > 0 ? resizeHandle.getMeasuredWidth() : dp(32);
        int handleHeight = resizeHandle.getMeasuredHeight() > 0 ? resizeHandle.getMeasuredHeight() : dp(32);
        resizeHandle.setX(Math.max(0, baseOverlayWidth * scale - handleWidth));
        resizeHandle.setY(Math.max(0, baseOverlayHeight * scale - handleHeight));
    }

    private void syncWindowBounds(float scale) {
        if (overlay == null || windowParams == null || manager == null || baseOverlayWidth <= 0 || baseOverlayHeight <= 0) return;
        // A janela e o conteúdo usam a mesma escala. A raiz mantém escala 1,
        // evitando que o Android faça hit-testing com uma matriz diferente da
        // que desenha os botões.
        windowParams.width = Math.max(1, Math.round(baseOverlayWidth * scale));
        windowParams.height = Math.max(1, Math.round(baseOverlayHeight * scale));
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
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try { startActivity(launch); } catch (RuntimeException ignored) { }
        }
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
