package org.lean.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/** The list screens (All apps, Add / remove apps, Customize) share one layout and row style. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Title, hint and a focusable list filling the rest of the screen. */
    static ListView listScreen(Activity a, String title, String hint) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(a, 56), dp(a, 36), dp(a, 56), dp(a, 16));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {Look.bgTop, Look.bgBottom}));

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Look.ink);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setTextSize(32);
        root.addView(t);

        TextView h = new TextView(a);
        h.setText(hint);
        h.setTextColor(Look.inkSoft);
        h.setTextSize(15);
        h.setPadding(0, dp(a, 4), 0, dp(a, 14));
        root.addView(h);

        ListView list = new ListView(a);
        list.setDivider(null);
        list.setDividerHeight(dp(a, 4));
        list.setVerticalScrollBarEnabled(false);
        list.setItemsCanFocus(false);
        list.setDefaultFocusHighlightEnabled(false);
        GradientDrawable sel = new GradientDrawable();
        sel.setCornerRadius(dp(a, 14));
        sel.setColor(Look.cardFocus);
        sel.setStroke(dp(a, 2), Look.ink);
        list.setSelector(sel);
        list.setDrawSelectorOnTop(false);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        a.setContentView(root);
        return list;
    }

    /** One list row: optional icon, a label and a value on the right. */
    static final class Row {
        final LinearLayout view;
        final ImageView icon;
        final TextView label;
        final TextView value;

        Row(Context c) {
            ColorStateList ink = new ColorStateList(
                new int[][] {{android.R.attr.state_selected}, {android.R.attr.state_pressed}, {}},
                new int[] {Look.ink, Look.ink, Look.inkSoft});
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(c, 18), dp(c, 10), dp(c, 22), dp(c, 10));
            view.setMinimumHeight(dp(c, 60));
            view.setTag(this);
            icon = new ImageView(c);
            view.addView(icon, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
            label = new TextView(c);
            label.setTextSize(18);
            label.setTextColor(ink);
            label.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            label.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = dp(c, 18);
            view.addView(label, lp);
            value = new TextView(c);
            value.setTextSize(15);
            value.setTextColor(ink);
            view.addView(value);
        }
    }
}
