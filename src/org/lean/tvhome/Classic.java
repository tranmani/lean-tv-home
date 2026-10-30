package org.lean.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

/**
 * The Classic home screen from version 1.3: a big clock, card tiles with the app icon inside, a
 * row of buttons along the bottom and a soft light behind the focused tile. It draws far less than
 * the Apple TV layout, so it suits slow boxes. Focus uses Android's own D-pad navigation.
 */
final class Classic {
    private static final int EASE = 160;
    private static final int[] TILE_MIN_DP = {112, 132, 172};
    private static final int R_PKG = 0x7f0f0001;

    private final Activity a;
    private final FrameLayout root;
    private final ScrollView scroll;
    private final LinearLayout grid;
    /** The button row, fixed under the scrolling grid so it never sits on the screen edge. */
    private final LinearLayout chips;
    private final View glow;
    /** The scrolled content: the glow under the grid. */
    private final FrameLayout content;
    private String lastPkg;
    /** True while the shown list is a preview, so the tile menu must not change the saved order. */
    private boolean preview;

    Classic(Activity a) {
        this.a = a;
        root = new FrameLayout(a);
        // One flat colour: a full-screen gradient costs several milliseconds a frame on a TV box GPU.
        root.setBackgroundColor(Look.bgBottom);
        a.getWindow().setBackgroundDrawable(null);

        // Soft tinted light that follows the focused tile. It sits under the tiles inside the scroll
        // area, so it scrolls with them and the edge strips fade it with them.
        glow = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(210));
        g.setColors(new int[] {0x33ffffff, 0x00ffffff});
        glow.setBackground(g);
        glow.setAlpha(0f);

        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(56), dp(36), dp(56), 0);
        // The glow and the edge strips reach into the side margins.
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        column.addView(header(), new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(a) {
            // With no tile further down, let focus leave for the button row at once instead of
            // spending a key press scrolling to the bottom first.
            @Override
            public boolean arrowScroll(int direction) {
                View f = findFocus();
                if (f != null && FocusFinder.getInstance().findNextFocus(this, f, direction) == null) {
                    return false;
                }
                return super.arrowScroll(direction);
            }

            // The fading edge stays on for its scroll margin, but is never drawn: drawing it takes
            // an offscreen layer every frame. The strips below give the same fade for far less.
            @Override
            protected float getTopFadingEdgeStrength() {
                return 0f;
            }

            @Override
            protected float getBottomFadingEdgeStrength() {
                return 0f;
            }

            // Clip the rows to the scroll area top and bottom only, so the glow may spread sideways.
            @Override
            protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                setClipBounds(new Rect(-w, 0, 2 * w, h));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setSmoothScrollingEnabled(true);
        // The fading edge keeps a scrolled-to tile this far from the edge, so its zoom and border
        // are never cut off.
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(40));
        // Two strips over the scroll area fade out whatever peeks in at its edges. They run the
        // full screen width so the glow fades the same everywhere.
        FrameLayout holder = new FrameLayout(a);
        holder.setClipChildren(false);
        holder.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        int sw = a.getResources().getDisplayMetrics().widthPixels;
        int clear = Look.bgBottom & 0x00ffffff;
        View top = new View(a);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {Look.bgBottom, clear}));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(sw, dp(14), Gravity.TOP);
        tp.leftMargin = -dp(56);
        holder.addView(top, tp);
        View bottom = new View(a);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[] {Look.bgBottom, clear}));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(sw, dp(40), Gravity.BOTTOM);
        bp.leftMargin = -dp(56);
        holder.addView(bottom, bp);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sp.topMargin = dp(18);
        column.addView(holder, sp);

        chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setClipChildren(false);
        chips.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(14);
        cp.bottomMargin = dp(32);
        column.addView(chips, cp);

        grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(10), dp(14), dp(10), dp(20));
        content = new FrameLayout(a);
        content.setClipChildren(false);
        content.addView(glow, new FrameLayout.LayoutParams(dp(420), dp(420)));
        content.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
    }

    View view() {
        return root;
    }

    boolean empty() {
        return grid.getChildCount() == 0;
    }

    private int dp(float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private View header() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);

        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(a);
        String clockFormat = Look.clock == 1 ? "h:mm" : "H:mm";
        clock.setFormat24Hour(clockFormat);
        clock.setFormat12Hour(clockFormat);
        clock.setTextColor(Look.ink);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setTextSize(64);
        clock.setIncludeFontPadding(false);
        if (Look.clock != 2) left.addView(clock);
        TextClock date = new TextClock(a);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextColor(Look.inkSoft);
        date.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date.setTextSize(17);
        if (Look.date == 1) left.addView(date);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView brand = new TextView(a);
        brand.setText(Look.title);
        brand.setTextColor(Look.inkSoft);
        brand.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        brand.setTextSize(14);
        brand.setLetterSpacing(0.32f);
        brand.setPadding(0, 0, 0, dp(6));
        if (!Look.title.trim().isEmpty()) row.addView(brand);
        return row;
    }

    /** Home pressed while already home: back to the first tile. */
    void home() {
        lastPkg = null;
        scroll.smoothScrollTo(0, 0);
        View first = grid.findViewWithTag("tile0");
        if (first != null) first.requestFocus();
    }

    /** Forgets the focused tile, so the next build starts at the first one. */
    void forgetFocus() {
        lastPkg = null;
    }

    boolean key(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            View f = a.getCurrentFocus();
            if (e.getAction() == KeyEvent.ACTION_UP && f != null && f.getTag(R_PKG) instanceof String) {
                options(f, (String) f.getTag(R_PKG));
            }
            return true;
        }
        // Home has nothing behind it.
        return e.getKeyCode() == KeyEvent.KEYCODE_BACK;
    }

    // ---- grid -----------------------------------------------------------------------------------

    void build(List<String> home, boolean preview) {
        this.preview = preview;
        grid.removeAllViews();

        // As many columns as fit at the chosen tile size, then stretch the tiles to fill the row.
        int gap = dp(20);
        int avail = a.getResources().getDisplayMetrics().widthPixels - dp(56 * 2 + 20);
        int cols = Math.max(3, (avail + gap) / (dp(TILE_MIN_DP[Look.tileSize]) + gap));
        int tileW = (avail - gap * (cols - 1)) / cols, tileH = tileW * 96 / 150;

        View focus = null;
        LinearLayout row = null;
        int n = 0;
        for (String pkg : home) {
            if (!Apps.launchable(a, pkg)) continue;
            if (n % cols == 0) {
                row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
                if (n > 0) rp.topMargin = dp(18);
                grid.addView(row, rp);
            }
            View t = tile(pkg, tileW, tileH);
            t.setTag("tile" + n);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(tileW, -2);
            if (n % cols > 0) tp.leftMargin = gap;
            row.addView(t, tp);
            if (pkg.equals(lastPkg) || focus == null) focus = t;
            n++;
        }

        if (n == 0) {
            TextView none = new TextView(a);
            none.setText("No apps on this screen yet. Open “Add / remove apps” below.");
            none.setTextColor(Look.inkSoft);
            none.setTextSize(18);
            grid.addView(none);
        }

        chips.removeAllViews();
        if (Look.buttons <= 1) {
            addChip(chip("All apps", v -> a.startActivity(AppsActivity.intent(a, false))));
        }
        addChip(chip("Add / remove apps", v -> a.startActivity(AppsActivity.intent(a, true))));
        addChip(chip("Customize", v -> a.startActivity(new Intent(a, CustomizeActivity.class))));
        if (Look.buttons == 0) {
            addChip(chip("Settings", v -> {
                try {
                    a.startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (RuntimeException e) {
                    Apps.toast(a, "Settings could not open", false);
                }
            }));
        }

        if (focus == null) focus = chips.getChildAt(0);
        View target = focus;
        target.requestFocus();
        target.post(() -> placeGlow(target, true));
    }

    private void addChip(View chip) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (chips.getChildCount() > 0) lp.leftMargin = dp(16);
        chips.addView(chip, lp);
    }

    private View tile(String pkg, int w, int h) {
        Apps.App app = Apps.get(a, pkg);

        LinearLayout tile = new LinearLayout(a);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setFocusable(true);
        tile.setDefaultFocusHighlightEnabled(false);
        tile.setClipChildren(false);
        tile.setTag(R_PKG, pkg);

        FrameLayout card = new FrameLayout(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(Look.card);
        card.setBackground(bg);
        card.setClipToOutline(true);
        tile.addView(card, new LinearLayout.LayoutParams(w, h));

        ImageView icon = new ImageView(a);
        icon.setImageDrawable(Apps.icon(a, app));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconSize = w * 54 / 150;
        card.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));

        View tint = new View(a);
        tint.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0x00000000, (app.tint & 0x00ffffff) | 0x66000000}));
        tint.setAlpha(0f);
        card.addView(tint, new FrameLayout.LayoutParams(-1, h * 2 / 3, Gravity.BOTTOM));

        View border = new View(a);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(18));
        ring.setColor(0);
        ring.setStroke(dp(2), Look.ink);
        border.setBackground(ring);
        border.setAlpha(0f);
        card.addView(border, new FrameLayout.LayoutParams(-1, -1));

        // Look.labels: 0 selected tile only, 1 always, 2 hidden.
        TextView label = new TextView(a);
        label.setText(app.label);
        label.setTextColor(Look.inkSoft);
        label.setTextSize(15);
        if (Look.labels == 0) label.setVisibility(View.INVISIBLE);
        label.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        if (Look.labels != 2) tile.addView(label, lp);

        tile.setOnFocusChangeListener((v, has) -> {
            float s = has && Look.motion == 0 ? 1.07f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(EASE).start();
            tint.animate().alpha(has ? 1f : 0f).setDuration(EASE).start();
            border.animate().alpha(has ? 1f : 0f).setDuration(EASE).start();
            bg.setColor(has ? Look.cardFocus : Look.card);
            label.setTextColor(has ? Look.ink : Look.inkSoft);
            if (Look.labels == 0) label.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            if (has) {
                lastPkg = pkg;
                ((GradientDrawable) glow.getBackground()).setColors(
                    new int[] {(app.tint & 0x00ffffff) | 0x30000000, 0x00000000});
                placeGlow(v, false);
            }
        });
        tile.setOnClickListener(v -> {
            if (!Apps.launch(a, pkg)) Apps.toast(a, app.label + " could not open", false);
        });
        tile.setOnLongClickListener(v -> {
            options(v, pkg);
            return true;
        });
        return tile;
    }

    private TextView chip(String text, View.OnClickListener click) {
        TextView c = new TextView(a);
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
            // The glow belongs to the tiles; it fades out while a button is focused.
            if (has) glow.animate().alpha(0f).setDuration(200).start();
        });
        c.setOnClickListener(click);
        return c;
    }

    /** Centres the soft glow on a tile, in the scrolled content's coordinates. */
    private void placeGlow(View v, boolean jump) {
        if (v == null) return;
        if (v.getWidth() == 0) {
            // Not laid out yet (fresh grid): try again after layout.
            v.post(() -> placeGlow(v, jump));
            return;
        }
        int[] at = new int[2], origin = new int[2];
        v.getLocationInWindow(at);
        content.getLocationInWindow(origin);
        float x = at[0] - origin[0] + v.getWidth() / 2f - glow.getWidth() / 2f;
        float y = at[1] - origin[1] + v.getHeight() / 2f - glow.getHeight() / 2f;
        if (jump || glow.getAlpha() == 0f) {
            glow.animate().cancel();
            glow.setTranslationX(x);
            glow.setTranslationY(y);
            glow.animate().alpha(1f).setDuration(300).start();
        } else {
            glow.animate().translationX(x).translationY(y).alpha(1f).setDuration(280).start();
        }
    }

    // ---- tile menu ------------------------------------------------------------------------------

    private void options(View anchor, String pkg) {
        List<String> home = Apps.home(a);
        int at = home.indexOf(pkg);
        String label = Apps.get(a, pkg).label;
        if (preview || at < 0) {
            Overlay.menu(a, anchor, label, new String[] {"App info"}, which -> {
                if (which == 0) Apps.appInfo(a, pkg);
            });
            return;
        }
        String[] items = {"Move left", "Move right", "Move to first place", "Remove from home", "App info"};
        Overlay.menu(a, anchor, label, items, which -> {
            if (which < 0) return;
            lastPkg = pkg;
            switch (which) {
                case 0: Apps.move(a, pkg, at - 1); break;
                case 1: Apps.move(a, pkg, at + 1); break;
                case 2: Apps.move(a, pkg, 0); break;
                case 3:
                    Apps.toggle(a, pkg);
                    lastPkg = at + 1 < home.size() ? home.get(at + 1) : (at > 0 ? home.get(at - 1) : null);
                    Apps.toast(a, label + " removed. Add it back in “Add / remove apps”.", true);
                    break;
                default: Apps.appInfo(a, pkg); return;
            }
            build(Apps.home(a), false);
        });
    }
}
