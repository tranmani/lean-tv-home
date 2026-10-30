package org.lean.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Handing the Home button back to Google TV's own home, from Add / remove apps or Customize. */
final class GoogleHome {
    /** Set while the owner is on Google TV home's App info page to press Enable. */
    private static boolean waitingForEnable;

    private GoogleHome() {}

    /** Hands the Home button to Google TV's home, enabling that app first if needed. */
    static void switchTo(Activity a) {
        try {
            a.getPackageManager().getApplicationInfo(Apps.GOOGLE_HOME, PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (PackageManager.NameNotFoundException e) {
            Apps.toast(a, "Google TV home is not installed on this box", true);
            return;
        }
        if (!Apps.enabled(a, Apps.GOOGLE_HOME)) {
            waitingForEnable = true;
            Apps.toast(a, "Select “Enable”, then press Back", true);
            Apps.appInfo(a, Apps.GOOGLE_HOME);
            return;
        }
        Overlay.alert(a, "Use Google TV Home?", "The Home button will open Google TV's home. To come back, "
                + "open “Lean TV Home” from Google TV's apps.", null, new String[] {"Switch", "Cancel"}, w -> {
                if (w != 0) return;
                Apps.setHomeEntry(a, false);
                a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                a.finishAffinity();
            });
    }

    /** Call from onResume: continues the switch once the owner has pressed Enable. */
    static void resume(Activity a) {
        if (waitingForEnable && Apps.enabled(a, Apps.GOOGLE_HOME)) {
            waitingForEnable = false;
            switchTo(a);
        }
    }
}
