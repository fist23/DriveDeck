package pt.dashboardauto;

/**
 * Declares the Android notification-listener capability required to inspect
 * active MediaSessions. The service intentionally does not read notification
 * text yet; it is only the user's explicit bridge to media controls.
 */
public class MusicNotificationListener extends android.service.notification.NotificationListenerService {
    @Override public void onListenerConnected() {
        super.onListenerConnected();
        OverlayService.requestMediaRefresh();
    }

    @Override public void onNotificationPosted(android.service.notification.StatusBarNotification notification) {
        OverlayService.requestMediaRefresh();
    }

    @Override public void onNotificationRemoved(android.service.notification.StatusBarNotification notification) {
        OverlayService.requestMediaRefresh();
    }
}
