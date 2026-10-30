package org.lean.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

/**
 * Everything on the home screen the owner can change in Customize: colours, icon size, names,
 * clock, title, the Top Shelf and motion. Screens read the static fields; {@link #load} refreshes
 * them and {@link #version} tells a screen that it must redraw.
 */
final class Look {
    /** One colour set. Order matches {@link #THEMES}. */
    private static final int[][] PALETTES = {
        // bgTop,     bgBottom,   ink,        inkSoft,    card,       cardFocus
        {0xff1c1c1e, 0xff0c0c0d, 0xffffffff, 0x99ebebf5, 0xff2c2c2e, 0xff3a3a3c}, // Dark (tvOS)
        {0xff121a2b, 0xff070b14, 0xffffffff, 0x99d8e2f2, 0xff18223a, 0xff24304d}, // Midnight
        {0xff000000, 0xff000000, 0xffffffff, 0x99ebebf5, 0xff1c1c1e, 0xff2c2c2e}, // Black
        {0xff142019, 0xff09100c, 0xffffffff, 0x99dceadf, 0xff1a2a20, 0xff26392d}, // Forest
        {0xff241820, 0xff120c10, 0xffffffff, 0x99f0dde6, 0xff2c1f27, 0xff3b2a34}, // Plum
        {0xffeeeef2, 0xffd7d7de, 0xff000000, 0x993c3c43, 0xffffffff, 0xffffffff}, // Light (tvOS)
    };
    static final String[] THEMES = {"Dark", "Midnight", "Black", "Forest", "Plum", "Light"};
    static final String[] TILE_SIZES = {"Small", "Medium", "Large"};
    /** Icons per row for each size; tvOS 26 shows six by default. */
    private static final int[] COLUMNS = {6, 5, 4};
    static final String[] LABELS = {"Selected app only", "Always", "Hidden"};
    static final String[] CLOCKS = {"24-hour", "12-hour", "Hidden"};
    /** Top Shelf: trending titles from the internet, the app's own colours, or nothing. */
    static final String[] SHELF = {"Trending Titles", "App Colours", "Off"};
    static final String[] SHOWN = {"Hidden", "Shown"};
    static final String[] MOTION = {"Full", "Reduced"};
    /** Home screen layouts: the tvOS one, or the lighter 1.3 one for slow boxes. */
    static final String[] LAYOUTS = {"Apple TV", "Classic"};
    static final String[] BUTTONS = {"Apps, Customize, Settings", "Apps and Customize", "Customize only"};
    static final String DEFAULT_TITLE = "";

    /** Saved-settings format. Version 2 was the first tvOS look, 3 the tvOS 26 one;
     *  older looks are reset once. */
    private static final int FORMAT = 3;

    static final int REGULAR = 0, MEDIUM = 1, SEMIBOLD = 2, BOLD = 3;
    private static final Typeface[] fonts = new Typeface[4];

    static int bgTop, bgBottom, ink, inkSoft, card, cardFocus;
    static int theme, tileSize, labels, clock, date, shelf, motion, buttons, layout;
    static String title;
    static int version;

    private Look() {}

    private static boolean loaded;

    /** Loads the saved look and the fonts once per process. */
    static void ensure(Context c) {
        if (!loaded) load(c);
        if (fonts[0] == null) {
            int[] ids = {R.font.inter_regular, R.font.inter_medium, R.font.inter_semibold, R.font.inter_bold};
            for (int i = 0; i < 4; i++) {
                try {
                    fonts[i] = c.getResources().getFont(ids[i]);
                } catch (RuntimeException e) {
                    fonts[i] = Typeface.create("sans-serif-medium", Typeface.NORMAL);
                }
            }
        }
    }

    /** Inter, the closest open font to Apple's SF Pro, in one of four weights. */
    static Typeface font(int weight) {
        return fonts[weight] != null ? fonts[weight] : Typeface.DEFAULT;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("look", Context.MODE_PRIVATE);
    }

    static void load(Context c) {
        loaded = true;
        SharedPreferences p = prefs(c);
        if (p.getInt("format", 1) == 2) {
            // Format 3: icon size and Top Shelf choices changed meaning.
            p.edit().remove("tileSize").remove("shelf").putInt("format", FORMAT).commit();
        }
        if (p.getInt("format", 1) < FORMAT) {
            // The old look's settings mean different things now. Keep only the title.
            String keep = p.getString("title", null);
            SharedPreferences.Editor e = p.edit().clear().putInt("format", FORMAT);
            if (keep != null && !keep.equals("LEAN TV  ·  HOME")) e.putString("title", keep);
            e.commit();
        }
        theme = clamp(p.getInt("theme", 0), THEMES.length);
        tileSize = clamp(p.getInt("tileSize", 0), TILE_SIZES.length);
        labels = clamp(p.getInt("labels", 0), LABELS.length);
        clock = clamp(p.getInt("clock", 0), CLOCKS.length);
        date = clamp(p.getInt("date", 0), SHOWN.length);
        shelf = clamp(p.getInt("shelf", 0), SHELF.length);
        motion = clamp(p.getInt("motion", 0), MOTION.length);
        buttons = clamp(p.getInt("buttons", 0), BUTTONS.length);
        layout = clamp(p.getInt("layout", 0), LAYOUTS.length);
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
        prefs(c).edit().clear().putInt("format", FORMAT).apply();
        changed(c);
    }

    private static void changed(Context c) {
        load(c);
        version++;
    }

    static boolean light() {
        return theme == 5;
    }

    static int columns() {
        return COLUMNS[tileSize];
    }

    /** {@link #ink} at a given alpha, for hairline strokes. */
    static int inkAlpha(int alpha) {
        return (ink & 0x00ffffff) | (alpha << 24);
    }
}
