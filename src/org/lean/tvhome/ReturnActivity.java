package org.lean.tvhome;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * The "Lean TV Home" tile in Google TV's app list. Turns this launcher's Home entry back on,
 * asks Android to make it the Home app, explains how to turn Google TV home off if it is on, then
 * opens it.
 */
public class ReturnActivity extends Activity {
    private static final int REQUEST_ROLE = 1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Apps.setHomeEntry(this, true);
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_ROLE);
                    return;
                } catch (RuntimeException ignored) {
                }
            }
        }
        goHome();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        goHome();
    }

    private void goHome() {
        if (Apps.enabled(this, Apps.GOOGLE_HOME)) {
            // While Google TV home is on, Google TV keeps sending the Home button to it.
            Apps.googleOffHelp(this, this::openHome);
            return;
        }
        openHome();
    }

    private void openHome() {
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
