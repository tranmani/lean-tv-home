package org.lean.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Everything on the home screen the owner can change in Customize: colours, tile size, labels,
 * clock, date, title and focus effects. Screens read the static fields; {@link #load} refreshes
 * them and {@link #version} tells a screen that it must redraw.
 */
final class Look {
    /** One colour set. Order matches {@link #THEMES}. */
    private static final int[][] PALETTES = {
        // bgTop,     bgBottom,   ink,        inkSoft,    card,       cardFocus
        {0xff17151a, 0xff0d0c0f, 0xffece6dc, 0xff8e877d, 0xff1c1b20, 0xff29272d}, // Warm dark
        {0xff121a2b, 0xff070b14, 0xffe3ebf7, 0xff8391a8, 0xff18223a, 0xff24304d}, // Midnight
        {0xff000000, 0xff000000, 0xfff2f2f2, 0xff8a8a8a, 0xff141414, 0xff242424}, // Pure black
        {0xff142019, 0xff09100c, 0xffe4eee3, 0xff83968a, 0xff1a2a20, 0xff26392d}, // Forest
        {0xff241820, 0xff120c10, 0xfff3e4ea, 0xff9c8590, 0xff2c1f27, 0xff3b2a34}, // Plum
        {0xffeeebe5, 0xffdcd8d0, 0xff1d1b18, 0xff6d675e, 0xfffaf8f4, 0xffffffff}, // Paper (light)
    };
    static final String[] THEMES = {"Warm dark", "Midnight", "Pure black", "Forest", "Plum", "Paper (light)"};
    static final String[] TILE_SIZES = {"Small", "Medium", "Large"};
    private static final int[] TILE_MIN_DP = {112, 132, 172};
    static final String[] LABELS = {"Always", "Selected tile only", "Hidden"};
    static final String[] CLOCKS = {"24-hour", "12-hour", "Hidden"};
    static final String[] ON_OFF = {"On", "Off"};
    static final String[] SHOWN = {"Shown", "Hidden"};
    static final String[] BUTTONS = {"All three", "Without Settings", "Customize only"};
    static final String DEFAULT_TITLE = "LEAN TV  ·  HOME";

    static int bgTop, bgBottom, ink, inkSoft, card, cardFocus;
    static int theme, tileSize, labels, clock, date, glow, zoom, wash, buttons;
    static String title;
    static int version;

    private Look() {}

    private static boolean loaded;

    /** Loads the saved look once per process. */
    static void ensure(Context c) {
        if (!loaded) load(c);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("look", Context.MODE_PRIVATE);
    }

    static void load(Context c) {
        loaded = true;
        SharedPreferences p = prefs(c);
        theme = clamp(p.getInt("theme", 0), THEMES.length);
        tileSize = clamp(p.getInt("tileSize", 1), TILE_SIZES.length);
        labels = clamp(p.getInt("labels", 0), LABELS.length);
        clock = clamp(p.getInt("clock", 0), CLOCKS.length);
        date = clamp(p.getInt("date", 0), SHOWN.length);
        glow = clamp(p.getInt("glow", 0), ON_OFF.length);
        zoom = clamp(p.getInt("zoom", 0), ON_OFF.length);
        wash = clamp(p.getInt("wash", 0), ON_OFF.length);
        buttons = clamp(p.getInt("buttons", 0), BUTTONS.length);
        title = p.getString("title", DEFAULT_TITLE);
        int[] pal = PALETTES[theme];
        bgTop = pal[0];
        bgBottom = pal[1];
        ink = pal[2];
        inkSoft = pal[3];
        card = pal[4];
        cardFocus = pal[5];
    }

    private static int clamp(int v, int n) {
        return v < 0 || v >= n ? 0 : v;
    }

    static void set(Context c, String key, int value) {
        prefs(c).edit().putInt(key, value).apply();
        changed(c);
    }

    static void setTitle(Context c, String value) {
        prefs(c).edit().putString("title", value).apply();
        changed(c);
    }

    static void reset(Context c) {
        prefs(c).edit().clear().apply();
        changed(c);
    }

    private static void changed(Context c) {
        load(c);
        version++;
    }

    static boolean light() {
        return theme == 5;
    }

    static int tileMinDp() {
        return TILE_MIN_DP[tileSize];
    }

    /** {@link #ink} at a given alpha, for hairline strokes. */
    static int inkAlpha(int alpha) {
        return (ink & 0x00ffffff) | (alpha << 24);
    }
}
