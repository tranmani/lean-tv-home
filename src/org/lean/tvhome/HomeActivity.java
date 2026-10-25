package org.lean.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.List;

/** The home screen: clock, the owner's app tiles, and a row of utility buttons. */
public class HomeActivity extends Activity {
    static final int EASE = 160;

    private ScrollView scroll;
    private LinearLayout grid;
    /** The chip row, fixed under the scrolling grid so it never sits on the screen edge. */
    private LinearLayout chips;
    private View glow;
    private String lastPkg;
    private int builtGeneration = -1;
    private int lookVersion;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Apps.forget();
            if (hasWindowFocus()) build();
        }
    };

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Look.ensure(this);
        lookVersion = Look.version;
        applyExtras(getIntent());
        WakeService.sync(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {Look.bgTop, Look.bgBottom}));

        // Soft tinted light that follows the focused tile.
        glow = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(210));
        g.setColors(new int[] {0x33ffffff, 0x00ffffff});
        glow.setBackground(g);
        glow.setAlpha(0f);
        root.addView(glow, new FrameLayout.LayoutParams(dp(420), dp(420)));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(56), dp(36), dp(56), 0);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        column.addView(header(), new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(this) {
            // With no tile further down, let focus leave for the chip row at once instead of
            // spending a key press scrolling to the bottom first.
            @Override
            public boolean arrowScroll(int direction) {
                View f = findFocus();
                if (f != null && FocusFinder.getInstance().findNextFocus(this, f, direction) == null) {
                    return false;
                }
                return super.arrowScroll(direction);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setSmoothScrollingEnabled(true);
        // The fading edge also keeps a scrolled-to tile this far from the edge, so its zoom and
        // border are never cut off, and the row peeking in from above or below fades out.
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(40));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sp.topMargin = dp(18);
        column.addView(scroll, sp);

        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setClipChildren(false);
        chips.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(14);
        cp.bottomMargin = dp(32);
        column.addView(chips, cp);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(10), dp(14), dp(10), dp(20));
        scroll.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> placeGlow(getCurrentFocus(), false));

        setContentView(root);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        String clockFormat = Look.clock == 1 ? "h:mm" : "H:mm";
        clock.setFormat24Hour(clockFormat);
        clock.setFormat12Hour(clockFormat);
        clock.setTextColor(Look.ink);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setTextSize(64);
        clock.setIncludeFontPadding(false);
        if (Look.clock != 2) left.addView(clock);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextColor(Look.inkSoft);
        date.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date.setTextSize(17);
        if (Look.date == 0) left.addView(date);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView brand = new TextView(this);
        brand.setText(Look.title);
        brand.setTextColor(Look.inkSoft);
        brand.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        brand.setTextSize(14);
        brand.setLetterSpacing(0.32f);
        brand.setPadding(0, 0, 0, dp(6));
        if (!Look.title.trim().isEmpty()) row.addView(brand);
        return row;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lookVersion != Look.version) {
            // Customize changed something: redraw everything with the new look.
            recreate();
            return;
        }
        if (builtGeneration != Apps.generation || grid.getChildCount() == 0) build();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean changed = applyExtras(intent);
        if (lookVersion != Look.version) {
            // A new title from adb: redraw like after Customize.
            recreate();
            return;
        }
        if (changed) build();
        // Home pressed while already home: jump back to the first tile.
        lastPkg = null;
        scroll.smoothScrollTo(0, 0);
        View first = grid.findViewWithTag("tile0");
        if (first != null) first.requestFocus();
    }

    /**
     * Setup from a computer, for example
     * {@code adb shell am start -n org.lean.tvhome/.HomeEntry --es order "pkg1,pkg2"}.
     * {@code order} replaces the tile list; {@code wol} (boolean, {@code --ez}) turns Wake-on-LAN on
     * or off; {@code wol_mac} sets the box's network address for it; {@code title} sets the title at
     * the top right ("" hides it). Returns true if the tile list changed.
     */
    private boolean applyExtras(Intent i) {
        if (i == null) return false;
        String mac = i.getStringExtra("wol_mac");
        if (mac != null) WakeService.setMac(this, mac);
        if (i.hasExtra("wol")) WakeService.setEnabled(this, i.getBooleanExtra("wol", false));
        String title = i.getStringExtra("title");
        if (title != null) Look.setTitle(this, title);
        String order = i.getStringExtra("order");
        if (order == null) return false;
        List<String> list = new java.util.ArrayList<>();
        for (String p : order.split(",")) {
            p = p.trim();
            if (!p.isEmpty() && !list.contains(p)) list.add(p);
        }
        Apps.save(this, list);
        lastPkg = null;
        return true;
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Home has nothing behind it.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag(R_PKG) instanceof String) {
                options((String) f.getTag(R_PKG));
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- grid -----------------------------------------------------------------------------------

    private static final int R_PKG = 0x7f0f0001;

    private void build() {
        builtGeneration = Apps.generation;
        grid.removeAllViews();
        List<String> home = Apps.home(this);

        // As many columns as fit at the chosen tile size, then stretch the tiles to fill the row.
        int gap = dp(20);
        int avail = getResources().getDisplayMetrics().widthPixels - dp(56 * 2 + 20);
        int cols = Math.max(3, (avail + gap) / (dp(Look.tileMinDp()) + gap));
        int tileW = (avail - gap * (cols - 1)) / cols, tileH = tileW * 96 / 150;

        View focus = null;
        LinearLayout row = null;
        for (int i = 0; i < home.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) rp.topMargin = dp(18);
                grid.addView(row, rp);
            }
            String pkg = home.get(i);
            View t = tile(pkg, tileW, tileH);
            t.setTag("tile" + i);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(tileW, -2);
            if (i % cols > 0) tp.leftMargin = gap;
            row.addView(t, tp);
            if (pkg.equals(lastPkg) || focus == null) focus = t;
        }

        if (home.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No apps on this screen yet. Open “Add / remove apps” below.");
            empty.setTextColor(Look.inkSoft);
            empty.setTextSize(18);
            grid.addView(empty);
        }

        chips.removeAllViews();
        if (Look.buttons != 2) {
            addChip(chips, chip("All apps", v -> startActivity(AppsActivity.intent(this, false))));
        }
        addChip(chips, chip("Add / remove apps", v -> startActivity(AppsActivity.intent(this, true))));
        addChip(chips, chip("Customize", v -> startActivity(new Intent(this, CustomizeActivity.class))));
        if (Look.buttons == 0) {
            addChip(chips, chip("Settings", v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (RuntimeException e) {
                    Apps.toast(this, "Settings could not open", false);
                }
            }));
        }

        if (focus == null) focus = chips.getChildAt(0);
        View target = focus;
        target.requestFocus();
        target.post(() -> placeGlow(target, true));
    }

    private void addChip(LinearLayout chips, View chip) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (chips.getChildCount() > 0) lp.leftMargin = dp(16);
        chips.addView(chip, lp);
    }

    private View tile(String pkg, int w, int h) {
        Apps.App app = Apps.get(this, pkg);

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setFocusable(true);
        tile.setDefaultFocusHighlightEnabled(false);
        tile.setClipChildren(false);
        tile.setTag(R_PKG, pkg);

        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(Look.card);
        card.setBackground(bg);
        card.setClipToOutline(true);
        tile.addView(card, new LinearLayout.LayoutParams(w, h));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(Apps.icon(this, app));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconSize = w * 54 / 150;
        card.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));

        View tint = new View(this);
        tint.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0x00000000, (app.tint & 0x00ffffff) | 0x66000000}));
        tint.setAlpha(0f);
        card.addView(tint, new FrameLayout.LayoutParams(-1, h * 2 / 3, Gravity.BOTTOM));

        View border = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(18));
        ring.setColor(0);
        ring.setStroke(dp(2), Look.ink);
        border.setBackground(ring);
        border.setAlpha(0f);
        card.addView(border, new FrameLayout.LayoutParams(-1, -1));

        TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextColor(Look.inkSoft);
        label.setTextSize(15);
        if (Look.labels == 1) label.setVisibility(View.INVISIBLE);
        label.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        if (Look.labels != 2) tile.addView(label, lp);

        tile.setOnFocusChangeListener((v, has) -> {
            float s = has && Look.zoom == 0 ? 1.07f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(EASE).start();
            if (Look.wash == 0) tint.animate().alpha(has ? 1f : 0f).setDuration(EASE).start();
            border.animate().alpha(has ? 1f : 0f).setDuration(EASE).start();
            bg.setColor(has ? Look.cardFocus : Look.card);
            label.setTextColor(has ? Look.ink : Look.inkSoft);
            if (Look.labels == 1) label.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            if (has) {
                lastPkg = pkg;
                ((GradientDrawable) glow.getBackground()).setColors(
                    new int[] {(app.tint & 0x00ffffff) | 0x30000000, 0x00000000});
                placeGlow(v, false);
            }
        });
        tile.setOnClickListener(v -> {
            if (!Apps.launch(this, pkg)) {
                Apps.toast(this, app.label + " could not open", false);
            }
        });
        tile.setOnLongClickListener(v -> {
            options(pkg);
            return true;
        });
        return tile;
    }

    private TextView chip(String text, View.OnClickListener click) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextSize(15);
        c.setTextColor(Look.inkSoft);
        c.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        c.setPadding(dp(22), dp(11), dp(22), dp(11));
        c.setFocusable(true);
        c.setDefaultFocusHighlightEnabled(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(30));
        bg.setColor(Look.card);
        bg.setStroke(dp(2), Look.inkAlpha(0x38));
        c.setBackground(bg);
        c.setOnFocusChangeListener((v, has) -> {
            c.setTextColor(has ? Look.ink : Look.inkSoft);
            bg.setColor(has ? Look.cardFocus : Look.card);
            bg.setStroke(dp(2), has ? Look.ink : Look.inkAlpha(0x38));
            if (has) {
                ((GradientDrawable) glow.getBackground()).setColors(new int[] {Look.inkAlpha(0x22), 0x00000000});
                placeGlow(v, false);
            }
        });
        c.setOnClickListener(click);
        return c;
    }

    /** Centres the soft glow on a view, in root coordinates. */
    private void placeGlow(View v, boolean jump) {
        if (v == null || glow == null || Look.glow != 0) return;
        if (v.getWidth() == 0) {
            // Not laid out yet (fresh grid): try again after layout.
            v.post(() -> placeGlow(v, jump));
            return;
        }
        int[] at = new int[2], root = new int[2];
        v.getLocationInWindow(at);
        ((View) glow.getParent()).getLocationInWindow(root);
        float x = at[0] - root[0] + v.getWidth() / 2f - glow.getWidth() / 2f;
        float y = at[1] - root[1] + v.getHeight() / 2f - glow.getHeight() / 2f;
        if (jump || glow.getAlpha() == 0f) {
            glow.animate().cancel();
            glow.setTranslationX(x);
            glow.setTranslationY(y);
            glow.animate().alpha(1f).setDuration(300).start();
        } else {
            glow.animate().translationX(x).translationY(y).setDuration(280).start();
        }
    }

    // ---- tile menu ------------------------------------------------------------------------------

    private void options(String pkg) {
        List<String> home = Apps.home(this);
        int at = home.indexOf(pkg);
        if (at < 0) return;
        String label = Apps.get(this, pkg).label;
        String[] items = {"Move left", "Move right", "Move to first place", "Remove from home", "App info"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(label)
            .setItems(items, (d, which) -> {
                lastPkg = pkg;
                switch (which) {
                    case 0: Apps.move(this, pkg, at - 1); break;
                    case 1: Apps.move(this, pkg, at + 1); break;
                    case 2: Apps.move(this, pkg, 0); break;
                    case 3:
                        Apps.toggle(this, pkg);
                        lastPkg = at + 1 < home.size() ? home.get(at + 1) : (at > 0 ? home.get(at - 1) : null);
                        Apps.toast(this, label + " removed. Add it back in “Add / remove apps”.", true);
                        break;
                    default: Apps.appInfo(this, pkg); return;
                }
                build();
            })
            .show();
    }
}
