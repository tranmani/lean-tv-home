package org.lean.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A tvOS icon grid with its own focus engine. Tiles sit at fixed slots on a page that scrolls on
 * a spring; the D-pad moves focus by row and column, a key with nowhere to go bumps the tile, and
 * in edit mode the focused icon wiggles and travels through the grid while the others make room.
 */
final class Grid extends FrameLayout {
    interface Listener {
        void focused(Tile t, int index);

        void clicked(Tile t, int index);

        void longPressed(Tile t, int index);

        /** Key pressed at the grid's edge; return true if something else took the focus. */
        boolean edge(int dx, int dy);

        void scrolled(float y);

        void moved(List<Object> keys);
    }

    private final FrameLayout page;
    private final List<Tile> tiles = new ArrayList<>();
    private final Motion.Follower scroll;
    private Listener listener;

    int cols = 5, tileW, tileH, gapX, pitch, left, top, anchor;
    /** The first row sits in the Dock under the Top Shelf, where tvOS shows no names. */
    boolean quietTopRow;
    /** Tiles from this index on (built-in tiles) stay put in edit mode. */
    private int movable;
    private int focus = -1;
    private boolean active = true;
    private int moving = -1;
    private ValueAnimator wiggle;
    private long downAt;
    private boolean longFired, pressed;

    Grid(Context c) {
        super(c);
        setClipChildren(false);
        page = new FrameLayout(c);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        addView(page, new LayoutParams(-1, -2));
        scroll = new Motion.Follower(0.42f, y -> {
            page.setTranslationY(-y);
            if (listener != null) listener.scrolled(y);
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Column count and geometry for a given screen width and side padding. */
    void measure(int width, int side, int columns, float gapFactor) {
        cols = columns;
        left = side;
        int avail = width - 2 * side;
        gapX = Math.round(avail * gapFactor);
        tileW = (avail - gapX * (cols - 1)) / cols;
        tileH = Math.round(tileW / Art.ASPECT);
        // The name of a focused icon sits in the gap below it, as on tvOS.
        pitch = tileH + dp(40);
    }

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    int count() {
        return tiles.size();
    }

    Tile tile(int i) {
        return tiles.get(i);
    }

    int focusIndex() {
        return focus;
    }

    int row(int i) {
        return i / cols;
    }

    int rowTop(int r) {
        return top + r * pitch;
    }

    /** Replaces every tile. {@code movable} counts the leading tiles that edit mode may move. */
    void setTiles(List<Tile> list, int movable) {
        endMove(false);
        page.removeAllViews();
        tiles.clear();
        this.movable = movable;
        for (Tile t : list) {
            tiles.add(t);
            page.addView(t, new LayoutParams(t.w, t.h));
            if (Look.labels != 2) {
                TextView l = new TextView(getContext());
                l.setText(t.name);
                l.setTypeface(Look.font(Look.MEDIUM));
                l.setTextSize(14.5f);
                l.setTextColor(Look.ink);
                l.setGravity(Gravity.CENTER_HORIZONTAL);
                l.setSingleLine(true);
                l.setEllipsize(TextUtils.TruncateAt.END);
                l.setShadowLayer(dp(6), 0, dp(1), Look.light() ? 0x00000000 : 0x99000000);
                page.addView(l, new LayoutParams(t.w + gapX - dp(4), -2));
                t.setLabel(l);
            }
        }
        place(false);
        int h = rowTop((tiles.size() + cols - 1) / cols) + dp(60);
        // A wrap-content page is capped at the screen height, which squeezes the names of the
        // lower rows to zero height; give the page its full height instead.
        page.getLayoutParams().height = h;
        page.requestLayout();
        if (focus >= tiles.size()) focus = tiles.size() - 1;
    }

    /** Puts every tile at its slot; with {@code animate}, tiles glide there (edit mode). */
    private void place(boolean animate) {
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            int x = left + (i % cols) * (tileW + gapX), y = rowTop(i / cols);
            t.setQuiet(quietTopRow && i < cols);
            LayoutParams lp = (LayoutParams) t.getLayoutParams();
            int oldX = lp.leftMargin, oldY = lp.topMargin;
            lp.leftMargin = x;
            lp.topMargin = y;
            t.setLayoutParams(lp);
            TextView l = t.label();
            if (l != null) {
                LayoutParams ll = (LayoutParams) l.getLayoutParams();
                ll.leftMargin = x - (gapX - dp(4)) / 2;
                ll.topMargin = y + tileH + dp(9);
                l.setLayoutParams(ll);
            }
            if (animate && (oldX != x || oldY != y) && i != moving) {
                glide(t, oldX - x, oldY - y);
                if (l != null) glide(l, oldX - x, oldY - y);
            }
        }
    }

    private static void glide(View v, float fromX, float fromY) {
        v.setTranslationX(fromX);
        v.setTranslationY(v instanceof TextView ? v.getTranslationY() + fromY : fromY);
        float endY = v instanceof TextView ? v.getTranslationY() - fromY : 0;
        v.animate().translationX(0).translationY(endY).setDuration(380).setInterpolator(Motion.SETTLE).start();
    }

    /** Focuses tile i without a direction (first build, Home pressed, back from a screen). */
    void focusAt(int i, boolean jump) {
        if (tiles.isEmpty()) return;
        i = Math.max(0, Math.min(tiles.size() - 1, i));
        if (focus >= 0 && focus < tiles.size() && focus != i) tiles.get(focus).focus(false, 0, 0);
        focus = i;
        if (active) tiles.get(i).focus(true, 0, 0);
        scrollTo(i, jump);
        if (listener != null) listener.focused(tiles.get(i), i);
    }

    /** Hands focus to something else on screen (the shelf), or takes it back. */
    void setActive(boolean on) {
        if (active == on) return;
        active = on;
        if (focus >= 0 && focus < tiles.size()) tiles.get(focus).focus(on, 0, on ? 1 : -1);
    }

    boolean isActive() {
        return active;
    }

    private void scrollTo(int i, boolean jump) {
        int r = row(i);
        float y = r == 0 ? 0 : Math.max(0, rowTop(r) - anchor);
        if (jump) scroll.jump(y);
        else scroll.to(y);
    }

    float scrollY() {
        return scroll.value();
    }

    // ---- keys ---------------------------------------------------------------------------------

    boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (down) step(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) step(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: if (down) step(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) step(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                select(e);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (moving >= 0) {
                    if (!down) endMove(true);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void select(KeyEvent e) {
        if (focus < 0 || focus >= tiles.size()) return;
        Tile t = tiles.get(focus);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                downAt = e.getDownTime();
                longFired = false;
                pressed = true;
                if (moving < 0) t.press(true);
            } else if (!longFired && pressed && e.getEventTime() - downAt >= 550) {
                longFired = true;
                t.press(false);
                if (moving < 0 && listener != null) listener.longPressed(t, focus);
            }
            return;
        }
        if (!pressed) return;
        pressed = false;
        if (longFired) return;
        if (moving >= 0) {
            endMove(true);
            return;
        }
        t.press(false);
        if (listener != null) listener.clicked(t, focus);
    }

    private void step(int dx, int dy) {
        if (tiles.isEmpty()) return;
        if (pressed) {
            // A direction while OK is held cancels the click, as on tvOS.
            pressed = false;
            tiles.get(focus).press(false);
        }
        if (moving >= 0) {
            moveBy(dx, dy);
            return;
        }
        int r = row(focus), c = focus % cols, rows = (tiles.size() + cols - 1) / cols;
        int next = -1;
        if (dx != 0) {
            int n = focus + dx;
            if (c + dx >= 0 && c + dx < cols && n >= 0 && n < tiles.size()) next = n;
        } else {
            int nr = r + dy;
            if (nr >= 0 && nr < rows) next = Math.min(nr * cols + c, tiles.size() - 1);
        }
        if (next < 0) {
            if (listener != null && listener.edge(dx, dy)) return;
            Motion.bump(tiles.get(focus), dx, dy);
            return;
        }
        tiles.get(focus).focus(false, dx, dy);
        focus = next;
        tiles.get(focus).focus(true, dx, dy);
        scrollTo(focus, false);
        if (listener != null) listener.focused(tiles.get(focus), focus);
    }

    // ---- edit mode ----------------------------------------------------------------------------

    boolean isMoving() {
        return moving >= 0;
    }

    void startMove() {
        if (focus < 0 || focus >= movable) return;
        moving = focus;
        if (Look.motion == 0) wiggle = Motion.jiggle(tiles.get(focus));
    }

    private void moveBy(int dx, int dy) {
        int to = moving + dx + dy * cols;
        if (dx != 0 && (moving % cols + dx < 0 || moving % cols + dx >= cols)) to = -1;
        if (to < 0 || to >= movable) {
            Motion.bump(tiles.get(moving), dx, dy);
            return;
        }
        Tile t = tiles.remove(moving);
        tiles.add(to, t);
        int oldX = ((LayoutParams) t.getLayoutParams()).leftMargin;
        int oldY = ((LayoutParams) t.getLayoutParams()).topMargin;
        moving = focus = to;
        place(true);
        LayoutParams lp = (LayoutParams) t.getLayoutParams();
        glide(t, oldX - lp.leftMargin, oldY - lp.topMargin);
        if (t.label() != null) glide(t.label(), oldX - lp.leftMargin, oldY - lp.topMargin);
        scrollTo(focus, false);
        // Keep the moved tile drawn above its neighbours.
        t.bringToFront();
        if (t.label() != null) t.label().bringToFront();
    }

    /** Leaves edit mode; with {@code save}, reports the new order. */
    void endMove(boolean save) {
        if (moving < 0) return;
        Tile t = tiles.get(moving);
        moving = -1;
        if (wiggle != null) {
            wiggle.cancel();
            wiggle = null;
        }
        t.animate().rotation(0).setDuration(160).start();
        if (save && listener != null) {
            List<Object> keys = new ArrayList<>();
            for (int i = 0; i < movable; i++) keys.add(tiles.get(i).key);
            listener.moved(keys);
        }
    }
}
