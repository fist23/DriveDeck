package pt.dashboardauto;

import android.accessibilityservice.AccessibilityService;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/** Provides a reliable touch surface over the display cutout. */
public final class DriveDeckAccessibilityService extends AccessibilityService {
    private static volatile DriveDeckAccessibilityService instance;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        OverlayService.rebuildIfActive(this);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    static WindowManager overlayWindowManager() {
        DriveDeckAccessibilityService service = instance;
        return service == null ? null : service.getSystemService(WindowManager.class);
    }

    static boolean isConnected() { return instance != null; }
}
