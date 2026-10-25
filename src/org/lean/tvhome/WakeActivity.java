package org.lean.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Turns the screen on after a Wake-on-LAN packet, then shows the home screen. */
public class WakeActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
