package org.lean.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installed apps, the saved home order, icons and launching. Shared by every screen. */
final class Apps {
    static final String GOOGLE_HOME = "com.google.android.tvlauncher";

    /**
     * First-run order: common TV apps, skipping any that are not installed. If none are, the first
     * apps by name are used. Change it on the box, or from a computer with the "order" extra
     * (see HomeActivity.applyExtras).
     */
    private static final String[] DEFAULT_ORDER = {
        "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus", "com.wbd.stream", "com.apple.atve.androidtv.appletv",
        "com.plexapp.android", "org.jellyfin.androidtv", "org.xbmc.kodi", "com.stremio.one",
        "org.smarttube.stable", "com.spotify.tv.android", "org.videolan.vlc",
    };
    private static final int FALLBACK_COUNT = 10;

    static final class App {
        final String pkg;
        final String label;
        Drawable icon;
        int tint;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    /** Bumped whenever the home list or the installed apps change; screens compare it on resume. */
    static int generation;

    private static final Map<String, App> cache = new HashMap<>();

    private Apps() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** Every launchable app except this launcher, sorted by name. */
    static List<App> all(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> found = new LinkedHashMap<>();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || found.containsKey(pkg)) continue;
                found.put(pkg, get(c, pkg));
            }
        }
        List<App> list = new ArrayList<>(found.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    /** Cached app entry with its label; the icon is loaded lazily by {@link #icon}. */
    static App get(Context c, String pkg) {
        App a = cache.get(pkg);
        if (a != null) return a;
        PackageManager pm = c.getPackageManager();
        String label = pkg;
        try {
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        a = new App(pkg, label);
        cache.put(pkg, a);
        return a;
    }

    static Drawable icon(Context c, App a) {
        if (a.icon == null) {
            PackageManager pm = c.getPackageManager();
            try {
                a.icon = pm.getApplicationIcon(a.pkg);
            } catch (PackageManager.NameNotFoundException e) {
                a.icon = pm.getDefaultActivityIcon();
            }
            a.tint = tintOf(a.icon);
        }
        return a.icon;
    }

    static void forget() {
        cache.clear();
        generation++;
    }

    static boolean launchable(Context c, String pkg) {
        return intentFor(c, pkg) != null;
    }

    private static Intent intentFor(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    static boolean launch(Context c, String pkg) {
        return launch(c, pkg, null);
    }

    /** Opens the app; {@code options} carries the launch animation (zoom out of the icon). */
    static boolean launch(Context c, String pkg, android.os.Bundle options) {
        Intent i = intentFor(c, pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            c.startActivity(i, options);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void appInfo(Context c, String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        // From a screen, open it on top of that screen so Back returns there. A new task could
        // join an old Settings task, and Back would then walk through Settings instead.
        if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (RuntimeException e) {
            c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /**
     * Google TV's own settings offer no Disable button for its home app, and while it is on, the
     * Home button keeps opening it. Turning it off takes one adb command; this shows it.
     */
    static void googleOffHelp(android.app.Activity a) {
        googleOffHelp(a, null);
    }

    static void googleOffHelp(android.app.Activity a, Runnable after) {
        Overlay.alert(a, "Turn Google TV Home Off",
            "Google TV has no button for this, so it takes one command from a computer "
                + "or phone with adb, on the same network:\n\n"
                + "adb connect " + ipAddress(a) + "\n"
                + "adb shell pm disable-user --user 0 " + GOOGLE_HOME + "\n\n"
                + "After that the Home button opens Lean TV Home and Google TV home stops using memory. "
                + "To undo: adb shell pm enable " + GOOGLE_HOME,
            null, new String[] {"OK"}, w -> {
                if (after != null) after.run();
            });
    }

    /** The box's IPv4 address on the active network, or a placeholder. */
    static String ipAddress(Context c) {
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            android.net.LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp != null) {
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    if (la.getAddress() instanceof java.net.Inet4Address) return la.getAddress().getHostAddress();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "<box address>";
    }

    private static Toast toast;

    /** Shows a message, replacing the previous one instead of queueing behind it. */
    static void toast(Context c, String text, boolean longer) {
        if (toast != null) toast.cancel();
        toast = Toast.makeText(c.getApplicationContext(), text, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        toast.show();
    }

    // ---- home order -------------------------------------------------------------------------

    /** Packages on the home screen, in order, limited to apps that are still launchable. */
    static List<String> home(Context c) {
        String saved = prefs(c).getString("order", null);
        List<String> out = new ArrayList<>();
        if (saved == null) {
            for (String p : DEFAULT_ORDER) if (launchable(c, p)) out.add(p);
            if (out.isEmpty()) {
                for (App a : all(c)) {
                    if (out.size() == FALLBACK_COUNT) break;
                    if (!a.pkg.equals(GOOGLE_HOME)) out.add(a.pkg);
                }
            }
            save(c, out);
            return out;
        }
        for (String p : saved.split(",")) if (!p.isEmpty() && launchable(c, p)) out.add(p);
        return out;
    }

    static void save(Context c, List<String> order) {
        prefs(c).edit().putString("order", String.join(",", order)).apply();
        generation++;
    }

    /** Adds the app at the end, or removes it. Returns true if it is on home afterwards. */
    static boolean toggle(Context c, String pkg) {
        List<String> order = home(c);
        boolean add = !order.remove(pkg);
        if (add) order.add(pkg);
        save(c, order);
        return add;
    }

    static void move(Context c, String pkg, int to) {
        List<String> order = home(c);
        if (!order.remove(pkg)) return;
        to = Math.max(0, Math.min(order.size(), to));
        order.add(to, pkg);
        save(c, order);
    }

    // ---- Google TV home -----------------------------------------------------------------------

    static boolean enabled(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager()
                .getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static ComponentName homeEntry(Context c) {
        return new ComponentName(c, "org.lean.tvhome.HomeEntry");
    }

    /** Turns this launcher's Home entry on or off. Off hands the Home button back to Google TV. */
    static void setHomeEntry(Context c, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(homeEntry(c),
            on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
               : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
    }

    // ---- colour -------------------------------------------------------------------------------

    /** The icon's most saturated colour family, softened, for the focus glow. */
    private static int tintOf(Drawable d) {
        try {
            Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, 16, 16);
            d.draw(cv);
            float[] hsv = new float[3];
            float r = 0, g = 0, bl = 0, weight = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int px = b.getPixel(x, y);
                    if (Color.alpha(px) < 128) continue;
                    Color.colorToHSV(px, hsv);
                    float w = hsv[1] * hsv[2];
                    w *= w;
                    r += Color.red(px) * w;
                    g += Color.green(px) * w;
                    bl += Color.blue(px) * w;
                    weight += w;
                }
            }
            b.recycle();
            if (weight < 0.5f) return 0xff8e877d;
            int c = Color.rgb((int) (r / weight), (int) (g / weight), (int) (bl / weight));
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(hsv[1], 0.75f);
            hsv[2] = Math.max(hsv[2], 0.75f);
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0xff8e877d;
        }
    }
}
