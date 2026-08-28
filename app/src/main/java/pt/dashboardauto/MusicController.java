package pt.dashboardauto;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.text.TextUtils;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.util.List;

public final class MusicController {
    private MusicController() { }

    public static void playPause(Context context) { withSelected(context, controller -> {
        if (controller.getPlaybackState() != null && controller.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }); }

    public static void play(Context context) {
        withSelected(context, controller -> controller.getTransportControls().play());
    }

    /**
     * Aguarda a MediaSession da app de áudio escolhida. Apps como o YouTube
     * Music precisam de algum tempo para arrancar e publicar a sessão depois
     * de serem abertos pelo Bluetooth.
     */
    public static void playWhenReady(Context context) {
        playWhenReady(context, 0);
    }

    private static void playWhenReady(Context context, int attempt) {
        MediaController selected = selectedController(context, true);
        if (selected != null) {
            selected.getTransportControls().play();
            return;
        }
        if (attempt >= 5) return;
        long delay = attempt == 0 ? 700L : 900L;
        new Handler(Looper.getMainLooper()).postDelayed(() -> playWhenReady(context, attempt + 1), delay);
    }

    public static void pause(Context context) {
        withSelected(context, controller -> controller.getTransportControls().pause());
    }

    public static void next(Context context) { withSelected(context, controller -> controller.getTransportControls().skipToNext()); }
    public static void previous(Context context) { withSelected(context, controller -> controller.getTransportControls().skipToPrevious()); }

    public static boolean isPlaying(Context context) {
        final boolean[] result = {false};
        withSelected(context, controller -> result[0] = controller.getPlaybackState() != null && controller.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PLAYING);
        return result[0];
    }

    public static String currentTrack(Context context) {
        final String[] result = {"Sem música ativa"};
        withSelected(context, controller -> {
            if (controller.getMetadata() == null) return;
            CharSequence title = trackTitle(controller.getMetadata());
            CharSequence artist = controller.getMetadata().getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(artist)) artist = controller.getMetadata().getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (TextUtils.isEmpty(artist)) artist = controller.getMetadata().getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
            if (!TextUtils.isEmpty(title)) result[0] = title + (TextUtils.isEmpty(artist) ? "" : "\n" + artist);
        });
        return result[0];
    }

    /** Identidade estável para detetar mudanças de faixa mesmo quando o texto
     * apresentado pelo player ainda não foi atualizado. */
    public static String currentTrackKey(Context context) {
        final String[] result = {""};
        withSelected(context, controller -> {
            android.media.MediaMetadata metadata = controller.getMetadata();
            if (metadata == null) return;
            CharSequence title = trackTitle(metadata);
            CharSequence artist = metadata.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(artist)) artist = metadata.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (TextUtils.isEmpty(artist)) artist = metadata.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
            CharSequence album = metadata.getText(android.media.MediaMetadata.METADATA_KEY_ALBUM);
            long duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
            result[0] = controller.getPackageName() + "|"
                    + (title == null ? "" : title) + "|"
                    + (artist == null ? "" : artist) + "|"
                    + (album == null ? "" : album) + "|" + duration;
        });
        return result[0];
    }

    public static Bitmap currentArtwork(Context context) {
        final Bitmap[] result = {null};
        withSelected(context, controller -> {
            if (controller.getMetadata() == null) return;
            result[0] = controller.getMetadata().getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
            if (result[0] == null) result[0] = controller.getMetadata().getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
            // YouTube Music e alguns players modernos expõem a capa apenas
            // como DISPLAY_ICON, não como ART/ALBUM_ART.
            if (result[0] == null) result[0] = controller.getMetadata().getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        });
        return result[0];
    }

    public static String currentAlbum(Context context) {
        final String[] result = {""};
        withSelected(context, controller -> {
            if (controller.getMetadata() == null) return;
            CharSequence album = controller.getMetadata().getText(android.media.MediaMetadata.METADATA_KEY_ALBUM);
            if (!TextUtils.isEmpty(album)) result[0] = album.toString();
        });
        return result[0];
    }

    public static PlaybackInfo playbackInfo(Context context) {
        final PlaybackInfo[] result = {new PlaybackInfo(0L, 0L, false)};
        withSelected(context, controller -> {
            android.media.session.PlaybackState state = controller.getPlaybackState();
            long duration = 0L;
            if (controller.getMetadata() != null) duration = controller.getMetadata().getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
            result[0] = new PlaybackInfo(state == null ? 0L : Math.max(0L, state.getPosition()), Math.max(0L, duration), state != null && state.getState() == android.media.session.PlaybackState.STATE_PLAYING);
        });
        return result[0];
    }

    public static void seekTo(Context context, long positionMs) { withSelected(context, controller -> controller.getTransportControls().seekTo(Math.max(0L, positionMs))); }

    public static final class PlaybackInfo {
        public final long positionMs;
        public final long durationMs;
        public final boolean playing;

        PlaybackInfo(long positionMs, long durationMs, boolean playing) {
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.playing = playing;
        }
    }

    private interface ControllerAction { void apply(MediaController controller); }

    private static MediaController selectedController(Context context, boolean requirePreferred) {
        if (Build.VERSION.SDK_INT < 21) return null;
        try {
            MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return null;
            ComponentName listener = new ComponentName(context, MusicNotificationListener.class);
            String preferred = context.getSharedPreferences("dashboard_auto", Context.MODE_PRIVATE).getString("music_app", "");
            MediaController mediaKeySession = null;
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    android.media.session.MediaSession.Token token = manager.getMediaKeyEventSession();
                    if (token != null) mediaKeySession = new MediaController(context, token);
                } catch (RuntimeException ignored) { }
            }
            if (mediaKeySession != null && !preferred.isEmpty() && preferred.equals(mediaKeySession.getPackageName())) {
                if (!requirePreferred || !preferred.isEmpty()) return mediaKeySession;
            }
            List<MediaController> sessions;
            try {
                sessions = manager.getActiveSessions(listener);
            } catch (SecurityException denied) {
                return mediaKeySession != null && !requirePreferred ? mediaKeySession : null;
            }
            MediaController fallback = null;
            MediaController metadataFallback = hasTrackMetadata(mediaKeySession) ? mediaKeySession : null;
            MediaController playingFallback = isPlayingWithMetadata(mediaKeySession) ? mediaKeySession : null;
            for (MediaController controller : sessions) {
                if (preferred.equals(controller.getPackageName())) return controller;
                if (fallback == null) fallback = controller;
                if (hasTrackMetadata(controller)) metadataFallback = controller;
                if (isPlayingWithMetadata(controller)) playingFallback = controller;
            }
            if (requirePreferred && !preferred.isEmpty()) return null;
            if (playingFallback != null) return playingFallback;
            if (metadataFallback != null) return metadataFallback;
            return fallback != null ? fallback : mediaKeySession;
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static boolean hasTrackMetadata(MediaController controller) {
        return controller != null && !TextUtils.isEmpty(trackTitle(controller.getMetadata()));
    }

    private static boolean isPlayingWithMetadata(MediaController controller) {
        android.media.session.PlaybackState state = controller == null ? null : controller.getPlaybackState();
        return hasTrackMetadata(controller) && state != null && state.getState() == android.media.session.PlaybackState.STATE_PLAYING;
    }

    private static CharSequence trackTitle(android.media.MediaMetadata metadata) {
        if (metadata == null) return null;
        CharSequence title = metadata.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title)) title = metadata.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (TextUtils.isEmpty(title)) title = metadata.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
        return title;
    }

    private static void withSelected(Context context, ControllerAction action) {
        MediaController controller = selectedController(context, false);
        if (controller != null) action.apply(controller);
    }
}
