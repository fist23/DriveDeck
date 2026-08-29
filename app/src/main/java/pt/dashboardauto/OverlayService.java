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
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AccelerateDecelerateInterpolator;
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
    private static volatile OverlayService runningService;
    // Permite compactar o player para libertar espaço, mantendo sempre os
    // controlos utilizáveis durante a condução.
    private static final String ACTION_CLOSE_PLAYER = "pt.dashboardauto.action.CLOSE_PLAYER";
    public static final String ACTION_CALL_STATE = "pt.dashboardauto.action.CALL_STATE";
    public static final String ACTION_CLOSE_EVERYTHING = "pt.dashboardauto.action.CLOSE_EVERYTHING";
    private static final String ACTION_RESET_LAYOUT = "pt.dashboardauto.action.RESET_LAYOUT";
    private static final String ACTION_REBUILD_LAYOUT = "pt.dashboardauto.action.REBUILD_LAYOUT";
    private WindowManager manager;
    private FrameLayout overlay;
    private TextView track;
    private TextView artist;
    private android.view.View musicLabelsContainer;
    private TextView timeLabel;
    private ImageView artwork;
    private android.view.View musicInfoContainer;
    private TextView compactTrackDetails;
    private Bitmap renderedArtwork;
    private long renderedArtworkSignature;
    private ImageButton playButton;
    private AnimatedWaveDrawable playingDrawable;
    private ImageView compactWave;
    private AnimatedWaveDrawable compactWaveDrawable;
    private SeekBar progressBar;
    private android.widget.ProgressBar compactProgressBar;
    private long durationMs;
    private boolean userSeeking;
    private boolean playingState;
    private String lastTrackValue;
    private String lastTrackKey;
    private String pendingTrackValue;
    private boolean trackTransitionRunning;
    private int trackAnimationToken;
    private boolean callActive;
    private long callStartedAt;
    private String callNumber = "";
    private TextView callName;
    private TextView callDuration;
    private ImageView callArtwork;
    private final Runnable callTicker = new Runnable() {
        @Override public void run() {
            if (!callActive) return;
            updateCallUi();
            refreshHandler.postDelayed(this, 1000L);
        }
    };
    private long optimisticPlaybackUntil;
    private boolean expanded;
    private boolean expandedBeforeCall;
    private boolean miniPlayerHidden;
    private WindowManager.LayoutParams windowParams;
    private android.view.View playerContent;
    private int baseOverlayWidth;
    private int baseOverlayHeight;
    private boolean layoutTransitionRunning;
    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshTrack = new Runnable() {
        @Override public void run() {
            updateTrack();
            refreshHandler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        active = true;
        runningService = this;
        // Cada sessão começa como uma Dynamic Island compacta. A expansão é
        // temporária e acontece apenas por interação direta do utilizador.
        expanded = false;
        getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit()
                .putBoolean("overlay_expanded", false)
                .remove("overlay_x")
                .remove("overlay_y")
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
        if (intent != null && ACTION_CALL_STATE.equals(intent.getAction())) {
            boolean activeCall = intent.getBooleanExtra("call_active", false);
            boolean callWasActive = callActive;
            if (activeCall && !callWasActive) {
                callStartedAt = android.os.SystemClock.elapsedRealtime();
                expandedBeforeCall = expanded;
                expanded = false;
            }
            if (!activeCall && callWasActive) expanded = expandedBeforeCall;
            if (activeCall) callNumber = intent.getStringExtra("call_number");
            callActive = activeCall;
            if (!callActive) {
                callStartedAt = 0L;
                callNumber = "";
                refreshHandler.removeCallbacks(callTicker);
            } else {
                refreshHandler.removeCallbacks(callTicker);
                refreshHandler.post(callTicker);
            }
            if (overlay != null) removeOverlay();
            if (PermissionManager.canDrawOverlay(this)) addOverlay();
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

    static void rebuildIfActive(android.content.Context context) {
        if (!active) return;
        Intent rebuild = new Intent(context, OverlayService.class).setAction(ACTION_REBUILD_LAYOUT);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(rebuild);
            else context.startService(rebuild);
        } catch (RuntimeException ignored) { }
    }

    static void requestMediaRefresh() {
        OverlayService service = runningService;
        if (service == null) return;
        service.refreshHandler.removeCallbacks(service.refreshTrack);
        service.refreshHandler.post(service.refreshTrack);
        // Algumas apps publicam a notificação antes de atualizarem a
        // MediaSession. Estes refreshes pontuais capturam a nova faixa sem
        // duplicar o ciclo periódico de 1 segundo.
        service.refreshHandler.postDelayed(service::updateTrack, 180L);
        service.refreshHandler.postDelayed(service::updateTrack, 600L);
    }

    private void addOverlay() {
        WindowManager accessibilityManager = DriveDeckAccessibilityService.overlayWindowManager();
        manager = accessibilityManager != null ? accessibilityManager : (WindowManager) getSystemService(WINDOW_SERVICE);
        baseOverlayWidth = 0;
        baseOverlayHeight = 0;
        overlay = new FrameLayout(this);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setOrientation(!expanded ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        content.setGravity(expanded ? Gravity.CENTER : (Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        content.setClickable(true);
        content.setOnClickListener(v -> {
            if (!expanded) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                toggleExpanded();
            }
        });
        content.setPadding(dp(expanded ? 14 : 4), dp(expanded ? 9 : 3), dp(expanded ? 14 : 4), dp(expanded ? 9 : 3));
        // The outer container is transparent in compact mode; only the pill
        // itself should draw a rounded background.
        content.setBackground(expanded ? panelBackground() : null);
        FrameLayout mediaContainer = new FrameLayout(this);
        mediaContainer.setClipChildren(false);
        mediaContainer.setClipToPadding(false);
        mediaContainer.setClipToOutline(true);
        mediaContainer.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(android.view.View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
            }
        });
        musicInfoContainer = mediaContainer;
        LinearLayout mediaInfo = new LinearLayout(this);
        mediaInfo.setOrientation(LinearLayout.HORIZONTAL);
        mediaInfo.setGravity(Gravity.CENTER_VERTICAL);
        mediaInfo.setClipChildren(false);
        mediaInfo.setClipToPadding(false);
        mediaInfo.setPadding(dp(expanded ? 12 : 4), dp(expanded ? 8 : 3), dp(expanded ? 12 : 4), dp(expanded ? 8 : 3));
        mediaInfo.setBackground(expanded
                ? rippleBackground(mediaBackground(), Color.rgb(90, 90, 110))
                : new GradientDrawable());
        if (!expanded) {
            mediaInfo.setClipToOutline(true);
            mediaInfo.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(android.view.View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
                }
            });
        }
        mediaInfo.setOnClickListener(v -> {
            if (!expanded) toggleExpanded();
            else openConfigured("music_app");
        });
        mediaInfo.setClickable(true);
        mediaInfo.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                view.setPressed(true);
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                view.setPressed(false);
                if (!expanded) toggleExpanded(); else openConfigured("music_app");
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                view.setPressed(false);
                return true;
            }
            return true;
        });
        artwork = new ImageView(this);
        artwork.setClickable(true);
        artwork.setOnClickListener(v -> mediaInfo.performClick());
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setBackgroundColor(Color.rgb(65, 27, 40));
        artwork.setClipToOutline(true);
        artwork.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(android.view.View view, android.graphics.Outline outline) {
                if (expanded) outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
                else outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        int artworkSize = controlDp(expanded ? 64 : 28);
        mediaInfo.addView(artwork, new LinearLayout.LayoutParams(artworkSize, artworkSize));
        if (!expanded && !callActive) {
            // Reserva física para o punch-hole. A capa e as ondas ficam em
            // lados opostos do recorte, em vez de encobrirem a câmara.
            android.widget.Space spacer = new android.widget.Space(this);
            mediaInfo.addView(spacer, new LinearLayout.LayoutParams(cutoutGapWidth(), 1));
            compactWave = new ImageView(this);
            compactWave.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            compactWave.setPadding(dp(2), dp(2), dp(2), dp(2));
            mediaInfo.addView(compactWave, new LinearLayout.LayoutParams(dp(32), dp(32)));
            updateCompactWave(playingState);
        }
        LinearLayout labels = new LinearLayout(this);
        musicLabelsContainer = labels;
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(8), 0, 0, 0);
        labels.setClickable(true);
        labels.setOnClickListener(v -> mediaInfo.performClick());
        track = new TextView(this);
        track.setTextColor(Color.WHITE); track.setTextSize(expanded ? 15 : 12); track.setMaxLines(1);
        track.setClickable(true);
        track.setOnClickListener(v -> mediaInfo.performClick());
        track.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artist = new TextView(this);
        artist.setTextColor(Color.rgb(166, 166, 178)); artist.setTextSize(expanded ? 12 : 10); artist.setMaxLines(1);
        artist.setClickable(true);
        artist.setOnClickListener(v -> mediaInfo.performClick());
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
            if (!callActive) {
                compactWave = new ImageView(this);
                compactWave.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                compactWave.setPadding(dp(4), dp(4), dp(4), dp(4));
                mediaInfo.addView(compactWave, new LinearLayout.LayoutParams(dp(38), dp(38)));
                updateCompactWave(playingState);
            }
        }
        // In compact mode the island behaves like an iPhone Dynamic Island:
        // artwork and the expand affordance remain visible, while metadata is
        // revealed only after the user opens the player.
        labels.setVisibility(expanded ? android.view.View.VISIBLE : android.view.View.GONE);
        // O texto fica entre a capa e o indicador de reprodução. Em compacto
        // começa oculto, mas mantém a posição correta quando a animação o
        // revela temporariamente.
        mediaInfo.addView(labels, 1, new LinearLayout.LayoutParams(0, -1, 1f));
        if (callActive) {
            mediaInfo.removeAllViews();
            addCallInfo(mediaInfo, expanded);
        }
        mediaContainer.setContentDescription(expanded
                ? "Player expandido"
                : "Dynamic Island do DriveDeck. Toque para expandir");
        mediaContainer.setOnClickListener(v -> {
            if (!expanded) toggleExpanded();
        });
        mediaContainer.setBackground(rippleBackground(mediaBackground(), Color.rgb(90, 90, 110)));
        mediaContainer.addView(mediaInfo, new FrameLayout.LayoutParams(
                expanded ? -1 : compactIslandWidth(), expanded ? -1 : controlDp(42)));
        if (!expanded && !callActive) {
            compactTrackDetails = new TextView(this);
            compactTrackDetails.setTextColor(Color.WHITE);
            compactTrackDetails.setTextSize(11);
            compactTrackDetails.setGravity(Gravity.CENTER);
            compactTrackDetails.setIncludeFontPadding(false);
            compactTrackDetails.setSingleLine(true);
            compactTrackDetails.setEllipsize(android.text.TextUtils.TruncateAt.END);
            compactTrackDetails.setPadding(dp(10), 0, dp(10), 0);
            compactTrackDetails.setVisibility(android.view.View.GONE);
            FrameLayout.LayoutParams detailsParams = new FrameLayout.LayoutParams(
                    compactIslandWidth(), controlDp(30), Gravity.TOP | Gravity.START);
            detailsParams.topMargin = controlDp(42);
            mediaContainer.addView(compactTrackDetails, detailsParams);
        }
        int availableWidth = Math.max(dp(1), getResources().getDisplayMetrics().widthPixels - dp(16));
        int compactAvailableWidth = availableWidth;
        // A pill compacta acompanha o recorte, sem duplicar o indicador de música.
        // 112dp deixa espaço equilibrado para a capa e para as ondas nos ecrãs
        // com punch-hole, mantendo a janela tocável e sem ocupar o topo todo.
        int compactMediaWidth = callActive ? dp(180) : compactIslandWidth();
        int expandedMaxWidth = Math.min(dp(390), availableWidth);
        int mediaWidth = !expanded ? Math.min(compactMediaWidth, compactAvailableWidth) : expandedMaxWidth;
        int minimumMediaWidth = Math.min(dp(expanded ? 320 : 42), expanded ? availableWidth : compactAvailableWidth);
        content.addView(mediaContainer, new LinearLayout.LayoutParams(Math.max(minimumMediaWidth, mediaWidth), controlDp(expanded ? 112 : 42)));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        if (!expanded || callActive) controls.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(expanded ? -1 : -2, controlDp(expanded ? 70 : 46));
        if (!expanded && isCenterIslandPosition()) {
            controlsParams.setMargins(cutoutGapWidth(), 0, 0, 0);
        }
        content.addView(controls, controlsParams);
        int[] icons = new int[]{R.drawable.ic_skip_previous, R.drawable.ic_play, R.drawable.ic_skip_next};
        String[] descriptions = {"Faixa anterior", "Reproduzir ou pausar", "Faixa seguinte"};
        for (int i = 0; i < icons.length; i++) {
            if (!expanded || callActive) continue;
            ImageButton b = expanded
                    ? expandedControlButton(icons[i], descriptions[i], i == 1)
                    : actionButton(icons[i], descriptions[i], i == 1 || i == icons.length - 1);
            if (i == 1) {
                playButton = b;
                setPlayButtonState(playingState, false);
            }
            final int actionIndex = i;
            b.setOnClickListener(v -> handleAction(actionIndex));
            controls.addView(b, buttonParams());
        }
        if (callActive) controls.setVisibility(android.view.View.GONE);
        if (expanded && !callActive) {
            addExpandedActions(controls);
        }
        // O content fica num FrameLayout independente para que o tamanho da janela
        // possa acompanhar a escala sem esticar novamente os botões por dentro.
        // Reserve real outer space so the horizontal reveal never clips at
        // the window edge while the compact pill remains right-aligned.
        int compactWindowWidth = Math.min(compactIslandWidth(), compactAvailableWidth);
        overlay.addView(content, new FrameLayout.LayoutParams(
                expanded ? -2 : compactWindowWidth, -2, Gravity.TOP | Gravity.START));
        overlay.setOnTouchListener((view, event) -> {
            if (expanded && event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                toggleExpanded();
                return true;
            }
            return false;
        });
        if (!expanded) {
            // O alvo transparente torna toda a pill minimizada tocável, mesmo
            // quando a capa/estado do player muda. Em modo expandido não existe
            // este alvo, por isso os controlos mantêm os seus próprios toques.
            android.view.View compactTapTarget = new android.view.View(this);
            compactTapTarget.setClickable(true);
            compactTapTarget.setBackgroundColor(Color.argb(1, 0, 0, 0));
            compactTapTarget.setContentDescription("Expandir player");
            compactTapTarget.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    v.performClick();
                    return true;
                }
                return true;
            });
            compactTapTarget.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                android.util.Log.d("DriveDeckIsland", "compact tap -> expand");
                toggleExpanded();
            });
            FrameLayout.LayoutParams compactTapParams = new FrameLayout.LayoutParams(
                    compactIslandWidth(), dp(42), Gravity.TOP | Gravity.START);
            compactTapParams.leftMargin = Math.max(0, compactWindowWidth - compactIslandWidth());
            overlay.addView(compactTapTarget, compactTapParams);
        }
        playerContent = content;
        content.addOnLayoutChangeListener((view, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;
            if (width <= 0 || height <= 0 || windowParams == null || manager == null || overlay == null) return;
            float scaleX = Math.abs(view.getScaleX());
            float scaleY = Math.abs(view.getScaleY());
            int outerWidth = width;
            int outerHeight = height;
            int windowWidth = Math.max(1, Math.round(outerWidth * (scaleX <= 0f ? 1f : scaleX)));
            int windowHeight = Math.max(1, Math.round(outerHeight * (scaleY <= 0f ? 1f : scaleY)));
            // O conteúdo é a fonte de verdade: qualquer mudança provocada por
            // expansão, recolha ou animação atualiza imediatamente a caixa da
            // janela para impedir clipping dos controlos.
            if (windowParams.width == windowWidth && windowParams.height == windowHeight) return;
            windowParams.width = windowWidth;
            windowParams.height = windowHeight;
            clampWindowBoundsToDisplay();
            try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
        });
        // A Dynamic Island has one stable size. Valores antigos de resize são
        // ignorados para que a atualização não herde uma escala incompatível.
        final float requestedScale = 1f;
        content.setScaleX(requestedScale);
        content.setScaleY(requestedScale);
        int overlayFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (expanded) overlayFlags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        if (expanded && android.os.Build.VERSION.SDK_INT >= 31) {
            // O blur atrás da janela é aplicado pelo compositor do Android;
            // abaixo do Android 12 o fundo translúcido continua a funcionar.
            overlayFlags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        }
        if (isCenterIslandPosition()) {
            // A Dynamic Island must occupy the display area behind the status
            // bar/cutout. Without these flags Android repositions overlays
            // below the safe inset, which makes the island visibly detached.
            overlayFlags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        }
        int overlayType = DriveDeckAccessibilityService.isConnected()
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-2, -2, overlayType, overlayFlags, PixelFormat.TRANSLUCENT);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        if (expanded && android.os.Build.VERSION.SDK_INT >= 31) {
            params.setBlurBehindRadius(dp(26));
        }
        // Dynamic Island: posição fixa e centrada no topo. Não há resize nem
        // drag para impedir que o gesto de condução roube toques aos botões.
        applyIslandPosition(params);
        windowParams = params;
        try {
            manager.addView(overlay, params);
            FrameLayout createdOverlay = overlay;
            overlay.setOnApplyWindowInsetsListener((view, insets) -> {
                if (isCenterIslandPosition()) scheduleIslandAlignment();
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
                syncOuterBoundsToContent(content);
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
                        syncOuterBoundsToContent(content);
                        overlay.requestLayout();
                    }
                    float effectiveScale = 1f;
                    // A janela do overlay deve manter os mesmos limites durante
                    // a entrada. Escalar o conteúdo antes de o alinhar fazia o
                    // WindowManager recalcular x/y e podia esconder a direita
                    // da ilha atrás do recorte da câmara.
                    content.setScaleX(1f);
                    // A expansão é vertical: nasce comprimida no topo e
                    // cresce para baixo sem mover a capa lateralmente.
                    content.setScaleY(expanded ? .72f : .90f);
                    content.setTranslationY(expanded ? -dp(20) : dp(3));
                    content.setAlpha(1f);
                    if (!expanded) {
                        // A pill está no lado direito da caixa fixa. O pivô
                        // mantém a animação centrada nela, e não no espaço
                        // transparente usado para revelar o título.
                        content.setPivotX(Math.max(0f, baseOverlayWidth - compactIslandWidth() / 2f));
                        content.setPivotY(baseOverlayHeight / 2f);
                    } else {
                        content.setPivotX(baseOverlayWidth / 2f);
                        content.setPivotY(0f);
                    }
                    overlay.setPivotX(baseOverlayWidth / 2f);
                    overlay.setPivotY(0f);
                    overlay.setScaleX(1f);
                    overlay.setScaleY(1f);
                    overlay.setTranslationY(0f);
                    scheduleIslandAlignment();
                    clampOverlayPosition();
                    content.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .alpha(1f)
                            .setInterpolator(expanded ? new SpringInterpolator(8f, 14f)
                                    : new SpringInterpolator(10f, 12f))
                            .setDuration(expanded ? 720 : 420)
                            .start();
                    overlay.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .setInterpolator(new DecelerateInterpolator(1.4f))
                            .setDuration(expanded ? 180 : 180)
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

    /** Mantém os bounds da janela exatamente alinhados com o layout lógico. */
    private void syncOuterBoundsToContent(android.view.View content) {
        if (content == null || overlay == null) return;
        int width = content.getMeasuredWidth();
        int height = content.getMeasuredHeight();
        if (width <= 0 || height <= 0) return;
        baseOverlayWidth = width;
        baseOverlayHeight = height;
        android.view.ViewGroup.LayoutParams rawParams = content.getLayoutParams();
        if (rawParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) rawParams;
            if (params.width != width || params.height != height) {
                params.width = width;
                params.height = height;
                content.setLayoutParams(params);
            }
        }
        if (windowParams == null || manager == null) return;
        float scaleX = Math.abs(content.getScaleX());
        float scaleY = Math.abs(content.getScaleY());
        windowParams.width = Math.max(1, Math.round(width * (scaleX <= 0f ? 1f : scaleX)));
        int outerHeight = height;
        windowParams.height = Math.max(1, Math.round(outerHeight * (scaleY <= 0f ? 1f : scaleY)));
        clampWindowBoundsToDisplay();
        try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
    }

    /** Reapplies the cutout anchor after the WindowManager measures the new
     * compact/expanded window across separate frames. */
    private void scheduleIslandAlignment() {
        if (!isCenterIslandPosition() || overlay == null) return;
        overlay.post(this::alignIslandToCutout);
        overlay.postDelayed(this::alignIslandToCutout, 90L);
        overlay.postDelayed(this::alignIslandToCutout, 240L);
    }

    /** Mantém a janela inteira visível quando o conteúdo muda de tamanho. */
    private void clampWindowBoundsToDisplay() {
        if (windowParams == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxX = Math.max(0, screenWidth - Math.max(1, windowParams.width));
        int maxY = Math.max(0, screenHeight - Math.max(1, windowParams.height));
        boolean leftAnchored = (windowParams.gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT;
        boolean rightAnchored = (windowParams.gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT;
        if (leftAnchored || rightAnchored) {
            windowParams.x = Math.max(0, Math.min(windowParams.x, maxX));
        }
        windowParams.y = Math.max(0, Math.min(windowParams.y, maxY));
    }

    private GradientDrawable panelBackground() {
        GradientDrawable background = expanded
                ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
                    Color.argb(246, 4, 4, 6),
                    Color.argb(242, 0, 0, 0),
                    Color.argb(246, 7, 4, 8)})
                : new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{
                    Color.rgb(27, 29, 39),
                    Color.rgb(7, 8, 13),
                    Color.rgb(22, 24, 34)});
        background.setCornerRadius(dp(expanded ? 34 : 100));
        background.setStroke(expanded ? dp(1) : dp(1), expanded
                ? Color.argb(55, 255, 255, 255)
                : Color.argb(150, Color.red(accentColor()), Color.green(accentColor()), Color.blue(accentColor())));
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
        GradientDrawable background = expanded
                ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
                    Color.TRANSPARENT, Color.TRANSPARENT})
                : new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{
                    Color.BLACK, Color.rgb(3, 4, 7)});
        background.setCornerRadius(dp(expanded ? 14 : 100));
        return background;
    }

    private ImageButton expandedControlButton(int icon, String description, boolean primary) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(Color.WHITE);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.TRANSPARENT);
        background.setShape(GradientDrawable.OVAL);
        button.setBackground(rippleBackground(background, Color.argb(70, 255, 255, 255)));
        button.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                view.animate().scaleX(.9f).scaleY(.9f).setDuration(70).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
                if (event.getAction() == MotionEvent.ACTION_UP) view.performClick();
            }
            return true;
        });
        return button;
    }

    private RippleDrawable rippleBackground(GradientDrawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    private void updateTrack() {
        if (track == null) return;
        String value = MusicController.currentTrack(this);
        String trackKey = MusicController.currentTrackKey(this);
        boolean textChanged = lastTrackValue != null && !lastTrackValue.equals(value);
        boolean keyChanged = lastTrackKey != null && !lastTrackKey.isEmpty()
                && !lastTrackKey.equals(trackKey);
        boolean trackChanged = textChanged || keyChanged;
        lastTrackValue = value;
        lastTrackKey = trackKey;
        if (trackChanged) animateTrackChange(value); else renderTrack(value, true);
        MusicController.PlaybackInfo playback = MusicController.playbackInfo(this);
        if (android.os.SystemClock.uptimeMillis() >= optimisticPlaybackUntil) playingState = playback.playing;
        setPlayButtonState(playingState, false);
        updateCompactWave(playingState);
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
        updateCallUi();
    }

    private void updateCompactWave(boolean playing) {
        if (compactWave == null) return;
        if (!playing) {
            if (compactWaveDrawable != null) compactWaveDrawable.stop();
            compactWaveDrawable = null;
            compactWave.setImageDrawable(null);
            compactWave.setVisibility(android.view.View.INVISIBLE);
            return;
        }
        compactWave.setVisibility(android.view.View.VISIBLE);
        compactWave.setColorFilter(null);
        if (compactWaveDrawable == null) compactWaveDrawable = new AnimatedWaveDrawable(Color.rgb(65, 225, 90));
        compactWave.setImageDrawable(compactWaveDrawable);
    }

    private void updateCallUi() {
        if (!callActive || callDuration == null) return;
        long elapsed = Math.max(0L, android.os.SystemClock.elapsedRealtime() - callStartedAt) / 1000L;
        callDuration.setText(String.format(java.util.Locale.US, "%02d:%02d", elapsed / 60L, elapsed % 60L));
    }

    private void renderTrack(String value) {
        renderTrack(value, true);
    }

    private void renderTrack(String value, boolean refreshArtwork) {
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
        if (artwork != null && (refreshArtwork || artwork.getDrawable() == null)) {
            Bitmap bitmap = MusicController.currentArtwork(this);
            long signature = artworkSignature(bitmap);
            if (signature != renderedArtworkSignature || artwork.getDrawable() == null) {
                renderedArtwork = bitmap;
                renderedArtworkSignature = signature;
                artwork.animate().cancel();
                artwork.setAlpha(0f);
                if (bitmap != null) artwork.setImageBitmap(bitmap); else artwork.setImageResource(R.drawable.ic_music);
                artwork.animate().alpha(1f).setDuration(180).start();
            }
        }
    }

    /**
     * Identifica a imagem pelo conteúdo, não pela referência Bitmap. Algumas
     * apps recriam o Bitmap a cada consulta mesmo sem mudar a capa, o que
     * provocava o fade contínuo da imagem na pill compacta.
     */
    private long artworkSignature(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return 0L;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        long signature = 17L * width + height;
        int[][] samples = {
                {0, 0}, {width - 1, 0}, {0, height - 1},
                {width - 1, height - 1}, {width / 2, height / 2}
        };
        for (int[] sample : samples) {
            int pixel = bitmap.getPixel(Math.max(0, sample[0]), Math.max(0, sample[1]));
            signature = signature * 31L + pixel;
        }
        return signature;
    }

    private void animateTrackChange(String value) {
        if (overlay == null || track == null || musicInfoContainer == null) return;
        if (trackTransitionRunning) {
            pendingTrackValue = value;
            return;
        }
        if (!expanded) {
            animateCompactTrackChange(value);
            return;
        }
        trackTransitionRunning = true;
        pendingTrackValue = null;
        FrameLayout transitionOverlay = overlay;
        android.view.View transitionContainer = musicInfoContainer;
        transitionContainer.animate()
                // A troca de faixa permanece no eixo vertical para não
                // arrastar a capa/media para fora da ilha.
                .translationX(0f)
                .translationY(-dp(8))
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
                    transitionContainer.setTranslationX(0f);
                    transitionContainer.setTranslationY(dp(8));
                    transitionContainer.setScaleX(.96f);
                    transitionContainer.setScaleY(.96f);
                    transitionContainer.animate()
                            .translationX(0f)
                            .translationY(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(460)
                            .setInterpolator(new SpringInterpolator(9f, 13f))
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

    private void animateCompactTrackChange(String value) {
        if (musicInfoContainer == null || track == null || artist == null || compactTrackDetails == null
                || playerContent == null) return;
        trackTransitionRunning = true;
        pendingTrackValue = null;
        final int animationToken = ++trackAnimationToken;
        renderTrack(value);
        final TextView details = compactTrackDetails;
        final android.view.ViewGroup.LayoutParams containerParams = musicInfoContainer.getLayoutParams();
        final android.view.ViewGroup.LayoutParams contentParams = playerContent.getLayoutParams();
        final int compactHeight = controlDp(42);
        final int revealHeight = controlDp(72);
        details.setText(value.split("\\n", 2)[0]);
        details.setVisibility(android.view.View.VISIBLE);
        details.setAlpha(0f);
        details.setPivotY(0f);
        details.setTranslationY(-dp(6));
        details.setScaleY(.86f);
        containerParams.height = compactHeight;
        contentParams.height = compactHeight;
        playerContent.setLayoutParams(contentParams);
        android.animation.ValueAnimator opening = android.animation.ValueAnimator.ofInt(compactHeight, revealHeight);
        opening.setDuration(520L);
        opening.setInterpolator(new SpringInterpolator(8f, 14f));
        opening.addUpdateListener(animation -> {
            int height = (Integer) animation.getAnimatedValue();
            containerParams.height = height;
            contentParams.height = height;
            musicInfoContainer.setLayoutParams(containerParams);
            playerContent.setLayoutParams(contentParams);
            updateCompactWindowHeight(height);
        });
        opening.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (animationToken != trackAnimationToken || compactTrackDetails != details) return;
                details.animate().alpha(1f).translationY(0f).scaleY(1f)
                        .setDuration(300L)
                        .setInterpolator(new SpringInterpolator(10f, 13f))
                        .start();
                refreshHandler.postDelayed(() -> {
                    if (animationToken != trackAnimationToken || compactTrackDetails != details) return;
                    details.animate().alpha(0f).translationY(-dp(3)).scaleY(.94f)
                            .setDuration(180L).start();
                    android.animation.ValueAnimator closing = android.animation.ValueAnimator.ofInt(revealHeight, compactHeight);
                    closing.setDuration(420L);
                    closing.setInterpolator(new SpringInterpolator(10f, 12f));
                    closing.addUpdateListener(close -> {
                        int height = (Integer) close.getAnimatedValue();
                        containerParams.height = height;
                        contentParams.height = height;
                        musicInfoContainer.setLayoutParams(containerParams);
                        playerContent.setLayoutParams(contentParams);
                        updateCompactWindowHeight(height);
                    });
                    closing.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator animation) {
                            if (compactTrackDetails != details) return;
                            details.setVisibility(android.view.View.GONE);
                            details.setAlpha(1f);
                            details.setTranslationY(0f);
                            details.setScaleY(1f);
                            containerParams.height = compactHeight;
                            contentParams.height = compactHeight;
                            finishTrackAnimation(animationToken, value);
                        }
                    });
                    closing.start();
                }, 900L);
            }
        });
        opening.start();
    }

    private void finishTrackAnimation(int animationToken, String value) {
        if (animationToken != trackAnimationToken) return;
        trackTransitionRunning = false;
        if (pendingTrackValue != null && !pendingTrackValue.equals(value)) {
            String next = pendingTrackValue;
            pendingTrackValue = null;
            animateCompactTrackChange(next);
        }
    }

    private void updateCompactWindowHeight(int height) {
        if (windowParams == null || manager == null || overlay == null) return;
        windowParams.height = Math.max(1, height);
        clampWindowBoundsToDisplay();
        try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
    }

    private ImageButton actionButton(int icon, String description, boolean accent) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(accent && !expanded ? accentColor() : Color.WHITE);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setPadding(controlDp(expanded ? 14 : 10), controlDp(expanded ? 14 : 10), controlDp(expanded ? 14 : 10), controlDp(expanded ? 14 : 10));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(expanded ? Color.TRANSPARENT
                : (accent ? Color.rgb(69, 27, 42) : Color.rgb(36, 36, 45)));
        background.setCornerRadius(dp(expanded ? 100 : 14));
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
                if (playingDrawable == null) {
                    playingDrawable = new AnimatedWaveDrawable(accentColor());
                    playButton.setImageDrawable(playingDrawable);
                }
            } else {
                playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            }
            return;
        }
        // Atualiza o estado antes da animação para que toques rápidos nunca
        // deixem uma animação anterior restaurar o ícone errado.
        if (showPlayingWaves) {
            if (playingDrawable == null) {
                playingDrawable = new AnimatedWaveDrawable(accentColor());
                playButton.setImageDrawable(playingDrawable);
            }
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

    /** Indicador leve de reprodução: barras grandes o suficiente para uso em condução. */
    private static final class AnimatedWaveDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.animation.ValueAnimator animator;
        private final float[] phases = new float[]{0f, 1.7f, 3.2f, 4.8f};
        private int color;

        AnimatedWaveDrawable(int color) {
            this.color = color;
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

    private void addCallInfo(LinearLayout parent, boolean expandedLayout) {
        parent.setPadding(dp(expandedLayout ? 10 : 8), dp(expandedLayout ? 8 : 6), dp(expandedLayout ? 12 : 8), dp(expandedLayout ? 8 : 6));
        callArtwork = new ImageView(this);
        callArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        callArtwork.setBackground(mediaBackground());
        callArtwork.setClipToOutline(true);
        callArtwork.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(android.view.View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        callArtwork.setImageResource(android.R.drawable.sym_action_call);
        android.graphics.Bitmap contactPhoto = loadContactPhoto(callNumber);
        if (contactPhoto != null) callArtwork.setImageBitmap(contactPhoto);
        int avatarSize = controlDp(expandedLayout ? 58 : 40);
        parent.addView(callArtwork, new LinearLayout.LayoutParams(avatarSize, avatarSize));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setPadding(dp(10), 0, dp(8), 0);
        callName = new TextView(this);
        callName.setTextColor(Color.WHITE);
        callName.setTextSize(expandedLayout ? 15 : 12);
        callName.setMaxLines(1);
        callName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        callName.setText(resolveContactName(callNumber));
        TextView state = new TextView(this);
        state.setText("Chamada ativa");
        state.setTextColor(Color.rgb(166, 166, 178));
        state.setTextSize(expandedLayout ? 12 : 10);
        callDuration = new TextView(this);
        callDuration.setTextColor(accentColor());
        callDuration.setTextSize(expandedLayout ? 13 : 11);
        callDuration.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        details.addView(callName, new LinearLayout.LayoutParams(-1, -2));
        details.addView(state, new LinearLayout.LayoutParams(-1, -2));
        details.addView(callDuration, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(details, new LinearLayout.LayoutParams(0, -1, 1f));
        updateCallUi();
    }

    private String resolveContactName(String number) {
        if (number == null || number.trim().isEmpty()) return "Chamada ativa";
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission("android.permission.READ_CONTACTS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return number;
        android.database.Cursor cursor = null;
        try {
            android.net.Uri lookup = android.net.Uri.withAppendedPath(
                    android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(number));
            cursor = getContentResolver().query(lookup,
                    new String[]{android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        finally { if (cursor != null) cursor.close(); }
        return number;
    }

    private android.graphics.Bitmap loadContactPhoto(String number) {
        if (number == null || number.trim().isEmpty()) return null;
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission("android.permission.READ_CONTACTS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return null;
        android.database.Cursor cursor = null;
        try {
            android.net.Uri lookup = android.net.Uri.withAppendedPath(
                    android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(number));
            cursor = getContentResolver().query(lookup,
                    new String[]{android.provider.ContactsContract.PhoneLookup._ID}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                android.net.Uri photoUri = android.content.ContentUris.withAppendedId(
                        android.provider.ContactsContract.Contacts.CONTENT_URI, id);
                try (java.io.InputStream input = android.provider.ContactsContract.Contacts.openContactPhotoInputStream(
                        getContentResolver(), photoUri, true)) {
                    return input == null ? null : android.graphics.BitmapFactory.decodeStream(input);
                }
            }
        } catch (Exception ignored) { }
        finally { if (cursor != null) cursor.close(); }
        return null;
    }

    private void endCurrentCall() {
        try {
            android.telecom.TelecomManager telecom = (android.telecom.TelecomManager) getSystemService(TELECOM_SERVICE);
            if (telecom != null && android.os.Build.VERSION.SDK_INT >= 28) telecom.endCall();
        } catch (SecurityException ignored) {
            android.widget.Toast.makeText(this, "O Android não permitiu desligar a chamada", android.widget.Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) { }
    }

    private void addExpandedActions(LinearLayout parent) {
        if (callActive) {
            ImageButton endCall = actionButton(R.drawable.ic_close, "Desligar chamada", true);
            endCall.setOnClickListener(v -> endCurrentCall());
            parent.addView(endCall, secondaryButtonParams());
            return;
        }
        ImageButton navigation = actionButton(R.drawable.ic_map, "Abrir navegação", false);
        navigation.setOnClickListener(v -> openConfigured("navigation_app"));
        parent.addView(navigation, secondaryButtonParams());
        ImageButton close = actionButton(R.drawable.ic_close, "Fechar tudo", true);
        close.setOnClickListener(v -> closeEverything());
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
        int size = controlDp(expanded ? 68 : 42);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        int margin = controlDp(expanded ? 10 : 2);
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    private int visualOverlayWidth() {
        if (windowParams != null && windowParams.width > 0) return windowParams.width;
        if (overlay == null) return 0;
        int measured = baseOverlayWidth > 0 ? baseOverlayWidth : overlay.getWidth();
        return Math.max(1, measured);
    }

    private int visualOverlayHeight() {
        if (windowParams != null && windowParams.height > 0) return windowParams.height;
        if (overlay == null) return 0;
        int measured = baseOverlayHeight > 0 ? baseOverlayHeight : overlay.getHeight();
        return Math.max(1, measured);
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
        int statusBarId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusBarHeight = statusBarId == 0 ? dp(24) : getResources().getDimensionPixelSize(statusBarId);
        int islandHeight = expanded
                ? (baseOverlayHeight > 0 ? baseOverlayHeight : dp(180))
                : dp(42);
        if (!expanded) {
            // TYPE_APPLICATION_OVERLAY is below the status bar. Keep the
            // compact island clear of notification icons when accessibility
            // is not providing the cutout-safe coordinate space.
            if (!DriveDeckAccessibilityService.isConnected()) {
                return Math.max(statusBarHeight + dp(4), selected.bottom + dp(4));
            }
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

    private int compactIslandWidth() {
        // Espaço simétrico: capa, recorte real da câmara e ondas. A largura
        // adapta-se ao dispositivo, mas mantém controlos suficientemente grandes.
        return controlDp(28) + cutoutGapWidth() + dp(32) + dp(14);
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
        int targetX;
        if (!expanded) {
            // A caixa compacta tem largura fixa para a animação do título. A
            // pill visível está alinhada à direita dessa caixa, centrada no
            // recorte, para que nenhum frame tenha de mover a janela.
            int visibleWidth = compactIslandWidth();
            targetX = cutout.centerX() - visibleWidth / 2 - (islandWidth - visibleWidth);
        } else {
            targetX = cutout.centerX() - islandWidth / 2;
        }
        targetX = Math.max(0, Math.min(maxX, targetX));
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
                .apply();
        if (overlay == null || windowParams == null || manager == null) return;
        applyIslandPosition(windowParams);
        try { manager.updateViewLayout(overlay, windowParams); } catch (IllegalArgumentException ignored) { }
        clampOverlayPosition();
    }

    private int controlDp(int value) {
        String size = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getString("overlay_control_size", "normal");
        float factor = "compact".equals(size) ? .90f : ("large".equals(size) ? 1.12f : 1f);
        return Math.max(dp(2), Math.round(dp(value) * factor));
    }

    /** Mola amortecida curta: tensão visível, mas sem oscilações longas. */
    private static final class SpringInterpolator implements android.view.animation.Interpolator {
        private final float damping;
        private final float frequency;

        SpringInterpolator(float damping, float frequency) {
            this.damping = damping;
            this.frequency = frequency;
        }

        @Override public float getInterpolation(float input) {
            if (input <= 0f) return 0f;
            if (input >= 1f) return 1f;
            return (float) (1d - Math.exp(-damping * input) * Math.cos(frequency * input));
        }
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private void removeOverlay() {
        refreshHandler.removeCallbacks(refreshTrack);
        trackAnimationToken++;
        layoutTransitionRunning = false;
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
        if (artwork != null) {
            artwork.animate().cancel();
            artwork.setTranslationX(0f);
            artwork.setTranslationY(0f);
            artwork.setScaleX(1f);
            artwork.setScaleY(1f);
            artwork.setRotation(0f);
            artwork.setRotationY(0f);
            artwork.setAlpha(1f);
        }
        if (overlay != null && manager != null) {
            try { manager.removeView(overlay); } catch (IllegalArgumentException ignored) { }
        }
        overlay = null;
        track = null;
        artist = null;
        musicLabelsContainer = null;
        timeLabel = null;
        progressBar = null;
        compactProgressBar = null;
        durationMs = 0L;
        artwork = null;
        musicInfoContainer = null;
        compactTrackDetails = null;
        renderedArtwork = null;
        renderedArtworkSignature = 0L;
        if (playingDrawable != null) playingDrawable.stop();
        playingDrawable = null;
        if (compactWaveDrawable != null) compactWaveDrawable.stop();
        compactWaveDrawable = null;
        compactWave = null;
        playButton = null;
        windowParams = null;
        lastTrackValue = null;
        lastTrackKey = null;
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
        if (overlay == null || layoutTransitionRunning) {
            android.util.Log.w("DriveDeckIsland", "toggle ignored overlay=" + (overlay != null) + " transition=" + layoutTransitionRunning);
            return;
        }
        android.util.Log.d("DriveDeckIsland", "toggle expanded=" + expanded + " next=" + nextState);
        layoutTransitionRunning = true;
        // A pill compacta ocupa apenas o lado direito da janela estável. Usar
        // o centro da janela como pivô fazia a saída parecer deslocar-se
        // lateralmente antes da expansão. A transição parte agora do centro
        // visual da ilha e a expansão continua presa ao recorte.
        if (!expanded) {
            overlay.setPivotX(Math.max(0f, overlay.getWidth() - compactIslandWidth() / 2f));
            overlay.setPivotY(overlay.getHeight() / 2f);
        } else {
            overlay.setPivotX(overlay.getWidth() / 2f);
            overlay.setPivotY(0f);
        }
        overlay.animate()
                .alpha(0f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(nextState ? -dp(8) : -dp(12))
                .setInterpolator(new OvershootInterpolator(nextState ? 1.1f : 1.25f))
                .setDuration(nextState ? 180 : 210)
                .withEndAction(() -> {
            expanded = nextState;
            getSharedPreferences("dashboard_auto", MODE_PRIVATE).edit().putBoolean("overlay_expanded", expanded).apply();
            removeOverlay();
            addOverlay();
            layoutTransitionRunning = false;
        }).start();
    }

    private void closeEverything() {
        // Pausa antes de fechar o overlay/apps para não deixar áudio a tocar
        // depois de o utilizador sair do Car Mode.
        MusicController.pause(this);
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

    private int accentColor() {
        String key = getSharedPreferences("dashboard_auto", MODE_PRIVATE).getString("accent_color", "blue");
        if ("pink".equals(key)) return Color.rgb(255, 55, 95);
        if ("green".equals(key)) return Color.rgb(48, 209, 88);
        if ("purple".equals(key)) return Color.rgb(191, 90, 242);
        if ("amber".equals(key)) return Color.rgb(255, 179, 64);
        return Color.rgb(10, 132, 255);
    }

    private LinearLayout.LayoutParams mediaButtonParams() {
        int size = controlDp(expanded ? 56 : 36);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
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
        if (runningService == this) runningService = null;
        super.onDestroy();
    }
    public static boolean isActive() { return active; }
    @Override public IBinder onBind(Intent intent) { return null; }
}
