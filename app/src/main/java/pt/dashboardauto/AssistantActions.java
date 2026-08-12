package pt.dashboardauto;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Safe entry points for hands-free phone actions. They open the system app and keep confirmation with the user. */
public final class AssistantActions {
    private AssistantActions() { }

    public static void openDialer(Context context) {
        launch(context, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:")));
    }

    public static void openMessages(Context context) {
        Intent messaging = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING);
        if (!launch(context, messaging)) launch(context, new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")));
    }

    public static void openVoiceAssistant(Context context) {
        Intent assistant = new Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!launch(context, assistant)) {
            Intent voice = new Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launch(context, voice);
        }
    }

    private static boolean launch(Context context, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) == null) return false;
        context.startActivity(intent);
        return true;
    }
}
