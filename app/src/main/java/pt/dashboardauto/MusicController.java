package pt.dashboardauto;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.text.TextUtils;
import android.graphics.Bitmap;
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
            CharSequence title = controller.getMetadata().getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
            CharSequence artist = controller.getMetadata().getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(title)) title = controller.getMetadata().getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (!TextUtils.isEmpty(title)) result[0] = title + (TextUtils.isEmpty(artist) ? "" : "\n" + artist);
        });
        return result[0];
    }

    public static Bitmap currentArtwork(Context context) {
        final Bitmap[] result = {null};
        withSelected(context, controller -> {
            if (controller.getMetadata() == null) return;
            result[0] = controller.getMetadata().getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
            if (result[0] == null) result[0] = controller.getMetadata().getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
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

    private static void withSelected(Context context, ControllerAction action) {
        if (Build.VERSION.SDK_INT < 21) return;
        try {
            MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName listener = new ComponentName(context, MusicNotificationListener.class);
            List<MediaController> sessions = manager.getActiveSessions(listener);
            String preferred = context.getSharedPreferences("dashboard_auto", Context.MODE_PRIVATE).getString("music_app", "");
            MediaController fallback = null;
            for (MediaController controller : sessions) {
                if (fallback == null) fallback = controller;
                if (!preferred.isEmpty() && preferred.equals(controller.getPackageName())) { action.apply(controller); return; }
            }
            if (fallback != null) action.apply(fallback);
        } catch (SecurityException ignored) {
            // The user has not enabled the notification-listener access yet.
        }
    }
}
