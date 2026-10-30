package org.lean.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Screens in the style of tvOS Settings: the title centred at the top, a large picture on the
 * left half and a list on the right whose focused row turns white and lifts.
 */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable background() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {Look.bgTop, Look.bgBottom});
    }

    /** Centred page title, as at the top of every tvOS screen. */
    static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTypeface(Look.font(Look.BOLD));
        t.setTextSize(26);
        t.setTextColor(Look.ink);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    /** The settings layout. Returns the list; the hint sits under the picture. */
    static ListPage settingsScreen(Activity a, String title, String hint, Bitmap art) {
        FrameLayout root = new FrameLayout(a);
        root.setBackground(background());
        root.setClipChildren(false);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(a, 34);
        root.addView(title(a, title), tp);

        int sw = a.getResources().getDisplayMetrics().widthPixels;
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        int artW = sw * 26 / 100, artH = Math.round(artW / Art.ASPECT);
        ImageView pic = new ImageView(a);
        pic.setImageBitmap(art);
        pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getHeight() * 0.105f);
            }
        });
        pic.setClipToOutline(true);
        pic.setElevation(dp(a, 18));
        left.addView(pic, new LinearLayout.LayoutParams(artW, artH));
        if (hint != null) {
            TextView h = new TextView(a);
            h.setText(hint);
            h.setTypeface(Look.font(Look.REGULAR));
            h.setTextSize(14);
            h.setLineSpacing(0, 1.15f);
            h.setTextColor(Look.inkSoft);
            h.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(artW + dp(a, 40), -2);
            hp.topMargin = dp(a, 26);
            left.addView(h, hp);
        }
        root.addView(left, new FrameLayout.LayoutParams(sw / 2, -1));

        ListPage list = new ListPage(a);
        // The list is wider than its rows by ListPage.SIDE on each side, so a focused row can zoom
        // without its ends being cut off.
        int side = dp(a, ListPage.SIDE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sw / 2 - dp(a, 60) + 2 * side, -1, Gravity.END);
        lp.rightMargin = dp(a, 60) - side;
        lp.topMargin = dp(a, 96);
        root.addView(list, lp);
        a.setContentView(root);
        return list;
    }

    /** One list row: optional icon, a label and a value on the right. */
    static final class Row {
        final LinearLayout view;
        final ImageView icon;
        final TextView label;
        final TextView value;
        private final GradientDrawable bg;

        Row(Context c) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(c, 18), 0, dp(c, 20), 0);
            view.setTag(this);
            bg = new GradientDrawable();
            bg.setCornerRadius(dp(c, 12));
            bg.setColor(0);
            view.setBackground(bg);
            icon = new ImageView(c);
            icon.setVisibility(View.GONE);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 50), dp(c, 30));
            ip.rightMargin = dp(c, 16);
            view.addView(icon, ip);
            label = new TextView(c);
            label.setTextSize(17);
            label.setTypeface(Look.font(Look.MEDIUM));
            label.setTextColor(Look.ink);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            view.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            value = new TextView(c);
            value.setTextSize(16);
            value.setTypeface(Look.font(Look.REGULAR));
            value.setTextColor(Look.inkSoft);
            value.setSingleLine(true);
            value.setEllipsize(TextUtils.TruncateAt.END);
            value.setMaxWidth(dp(c, 200));
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
            vp.leftMargin = dp(c, 16);
            view.addView(value, vp);
        }

        void focus(boolean on) {
            bg.setColor(on ? 0xffffffff : 0);
            label.setTextColor(on ? 0xff000000 : Look.ink);
            value.setTextColor(on ? 0x99000000 : Look.inkSoft);
            float s = on ? 1.035f : 1f;
            view.animate().cancel();
            view.animate().scaleX(s).scaleY(s).translationZ(on ? dp(view.getContext(), 12) : 0)
                .setDuration(on ? Motion.FOCUS_MS : Motion.UNFOCUS_MS)
                .setInterpolator(on ? Motion.FOCUS : Motion.SETTLE).start();
        }
    }

    /**
     * A vertical list with the same focus engine as the grid: the D-pad moves focus, the list
     * glides on a spring to keep the focused row near the middle, and the ends bump.
     */
    static final class ListPage extends FrameLayout {
        /** Room in dp left of and right of the rows for the focused row's zoom and shadow. */
        static final int SIDE = 40;
        private final LinearLayout column;
        private final List<Row> rows = new ArrayList<>();
        private final Motion.Follower scroll;
        private int focus;
        private IntConsumer click, longClick;
        private long downAt;
        private boolean pressed, longFired;

        ListPage(Context c) {
            super(c);
            setClipChildren(false);
            column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setClipChildren(false);
            column.setClipToPadding(false);
            column.setPadding(dp(c, SIDE), dp(c, 8), dp(c, SIDE), dp(c, 40));
            addView(column, new LayoutParams(-1, -2));
            scroll = new Motion.Follower(0.38f, y -> column.setTranslationY(-y));
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, h);
            // The column is as tall as all its rows, so the rows below the screen can scroll into view.
            column.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            // Rows scrolled up end at the top edge instead of running over the screen's title. The
            // sides are padded (SIDE) for the focused row's zoom and shadow.
            setClipBounds(new Rect(0, -dp(getContext(), 4), w, h + dp(getContext(), 60)));
        }

        void onClick(IntConsumer c) {
            click = c;
        }

        void onLongClick(IntConsumer c) {
            longClick = c;
        }

        Row add() {
            Row r = new Row(getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(getContext(), 50));
            if (!rows.isEmpty()) lp.topMargin = dp(getContext(), 4);
            column.addView(r.view, lp);
            rows.add(r);
            return r;
        }

        void clear() {
            column.removeAllViews();
            rows.clear();
        }

        Row row(int i) {
            return rows.get(i);
        }

        int size() {
            return rows.size();
        }

        int focusIndex() {
            return focus;
        }

        void focusAt(int i, boolean jump) {
            if (rows.isEmpty()) return;
            i = Math.max(0, Math.min(rows.size() - 1, i));
            if (focus < rows.size()) rows.get(focus).focus(false);
            focus = i;
            rows.get(i).focus(true);
            Runnable s = () -> {
                float y = Math.max(0, rowCenter(focus) - getHeight() * 0.42f);
                float max = Math.max(0, column.getHeight() - getHeight());
                y = Math.min(y, max);
                if (jump) scroll.jump(y);
                else scroll.to(y);
            };
            if (getHeight() == 0 || column.getHeight() == 0) post(s);
            else s.run();
        }

        private float rowCenter(int i) {
            View v = rows.get(i).view;
            return v.getTop() + v.getHeight() / 2f;
        }

        boolean key(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!down || rows.isEmpty()) return true;
                    pressed = false;
                    int d = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    int n = focus + d;
                    if (n < 0 || n >= rows.size()) Motion.bump(rows.get(focus).view, 0, d);
                    else focusAt(n, false);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && !rows.isEmpty()) {
                        Motion.bump(rows.get(focus).view, e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, 0);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (rows.isEmpty()) return true;
                    if (down) {
                        if (e.getRepeatCount() == 0) {
                            pressed = true;
                            longFired = false;
                            downAt = e.getDownTime();
                            View v = rows.get(focus).view;
                            v.animate().scaleX(1f).scaleY(1f).setDuration(110).setInterpolator(Motion.EASE_OUT).start();
                        } else if (pressed && !longFired && longClick != null && e.getEventTime() - downAt >= 550) {
                            longFired = true;
                            rows.get(focus).focus(true);
                            longClick.accept(focus);
                        }
                    } else if (pressed) {
                        pressed = false;
                        rows.get(focus).focus(true);
                        if (!longFired && click != null) click.accept(focus);
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
