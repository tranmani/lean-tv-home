package org.lean.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * tvOS-style pop-ups: the full-screen alert with stacked buttons, and the context menu that opens
 * beside a held icon. Focused buttons turn white with dark text and lift, as on tvOS.
 */
final class Overlay {
    private Overlay() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** A rounded button that turns white and grows when focused. */
    static TextView pill(Context c, String text, int width) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTypeface(Look.font(Look.MEDIUM));
        b.setTextSize(17);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setFocusable(true);
        b.setDefaultFocusHighlightEnabled(false);
        b.setPadding(dp(c, 24), 0, dp(c, 24), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 12));
        int rest = Look.light() ? 0x1f000000 : 0x2effffff;
        bg.setColor(rest);
        b.setBackground(bg);
        b.setTextColor(Look.ink);
        b.setElevation(0);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xffffffff : rest);
            b.setTextColor(has ? 0xff000000 : Look.ink);
            float s = has ? 1.06f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(c, 10) : 0)
                .setDuration(has ? Motion.FOCUS_MS : Motion.UNFOCUS_MS)
                .setInterpolator(has ? Motion.FOCUS : Motion.SETTLE).start();
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(width, dp(c, 54)));
        return b;
    }

    private static Dialog sheet(Activity a, View content, boolean dim) {
        Dialog d = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(dim ? (Look.light() ? 0xd9f2f2f7 : 0xd9000000) : 0x80000000);
        root.addView(content);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.setWindowAnimations(0);
        }
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(Motion.FADE_MS).setInterpolator(Motion.EASE_OUT).start();
        return d;
    }

    /**
     * Full-screen alert: title, message, an optional extra view (a text field) and stacked
     * buttons. {@code pick} gets the button index, or -1 when Back closes it.
     */
    static Dialog alert(Activity a, String title, String message, View extra, String[] buttons, IntConsumer pick) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(a, 80), dp(a, 40), dp(a, 80), dp(a, 40));

        TextView t = new TextView(a);
        t.setText(title);
        t.setTypeface(Look.font(Look.BOLD));
        t.setTextSize(28);
        t.setTextColor(Look.ink);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        if (message != null && !message.isEmpty()) {
            TextView m = new TextView(a);
            m.setText(message);
            m.setTypeface(Look.font(Look.REGULAR));
            m.setTextSize(16);
            m.setLineSpacing(0, 1.15f);
            m.setTextColor(Look.inkSoft);
            m.setGravity(Gravity.CENTER);
            m.setTextIsSelectable(false);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(a, 640), -2);
            mp.topMargin = dp(a, 14);
            col.addView(m, mp);
        }
        if (extra != null) {
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp(a, 440), -2);
            ep.topMargin = dp(a, 22);
            col.addView(extra, ep);
        }
        Dialog[] d = new Dialog[1];
        boolean[] picked = {false};
        View first = null;
        for (int i = 0; i < buttons.length; i++) {
            int which = i;
            TextView b = pill(a, buttons[i], dp(a, 440));
            LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) b.getLayoutParams();
            bp.topMargin = dp(a, i == 0 ? 30 : 14);
            b.setOnClickListener(v -> {
                picked[0] = true;
                close(d[0]);
                if (pick != null) pick.accept(which);
            });
            col.addView(b, bp);
            if (first == null) first = b;
        }
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        col.setClipChildren(false);
        FrameLayout center = new FrameLayout(a);
        center.setClipChildren(false);
        center.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        sv.addView(center, new FrameLayout.LayoutParams(-1, -1));
        d[0] = sheet(a, sv, true);
        d[0].setOnDismissListener(x -> {
            if (!picked[0] && pick != null) pick.accept(-1);
        });
        View focus = extra != null ? extra : first;
        d[0].show();
        if (focus != null) focus.requestFocus();
        col.setScaleX(0.96f);
        col.setScaleY(0.96f);
        col.animate().scaleX(1f).scaleY(1f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.FOCUS).start();
        return d[0];
    }

    /** A message with one OK button. */
    static void info(Activity a, String title, String message) {
        alert(a, title, message, null, new String[] {"OK"}, null);
    }

    private static void close(Dialog d) {
        if (d == null || !d.isShowing()) return;
        View root = d.getWindow() != null ? d.getWindow().getDecorView() : null;
        if (root == null) {
            d.dismiss();
            return;
        }
        root.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (d.isShowing()) d.dismiss();
        }).start();
    }

    /**
     * The context menu opened by holding OK on an icon: a frosted panel beside the icon. The rest
     * of the screen dims. {@code pick} gets the item index, or -1 when Back closes it.
     */
    static void menu(Activity a, View anchor, String title, String[] items, IntConsumer pick) {
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int sw = a.getResources().getDisplayMetrics().widthPixels;
        int sh = a.getResources().getDisplayMetrics().heightPixels;
        // The location already includes the focus zoom; so does the size here.
        int ax = at[0], aw = Math.round(anchor.getWidth() * anchor.getScaleX());
        int ay = at[1], ah = Math.round(anchor.getHeight() * anchor.getScaleY());

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 10);
        panel.setPadding(pad, dp(a, 14), pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 22));
        bg.setColor(Look.light() ? 0xf5f2f2f7 : 0xf52c2c2e);
        panel.setBackground(bg);
        panel.setElevation(dp(a, 24));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTypeface(Look.font(Look.SEMIBOLD));
        t.setTextSize(14);
        t.setTextColor(Look.inkSoft);
        t.setPadding(dp(a, 14), 0, dp(a, 14), dp(a, 10));
        t.setSingleLine(true);
        panel.addView(t);

        int width = dp(a, 300);
        Dialog[] d = new Dialog[1];
        boolean[] picked = {false};
        View first = null;
        for (int i = 0; i < items.length; i++) {
            int which = i;
            TextView b = pill(a, items[i], width - 2 * pad);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setTextSize(16);
            b.setPivotX(dp(a, 20));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(width - 2 * pad, dp(a, 48));
            if (i > 0) bp.topMargin = dp(a, 4);
            ((GradientDrawable) b.getBackground()).setColor(0);
            int rest = 0;
            b.setOnFocusChangeListener((v, has) -> {
                ((GradientDrawable) b.getBackground()).setColor(has ? 0xffffffff : rest);
                b.setTextColor(has ? 0xff000000 : Look.ink);
                float s = has ? 1.03f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.FOCUS).start();
            });
            b.setOnClickListener(v -> {
                picked[0] = true;
                close(d[0]);
                pick.accept(which);
            });
            panel.addView(b, bp);
            if (first == null) first = b;
        }

        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST));
        int ph = panel.getMeasuredHeight();
        int gap = dp(a, 26);
        boolean right = ax + aw + gap + width < sw - dp(a, 24);
        int x = right ? ax + aw + gap : ax - gap - width;
        int y = Math.max(dp(a, 24), Math.min(sh - ph - dp(a, 24), ay + ah / 2 - ph / 2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, -2);
        lp.leftMargin = Math.max(dp(a, 24), x);
        lp.topMargin = y;
        panel.setLayoutParams(lp);

        FrameLayout holder = new FrameLayout(a);
        holder.setClipChildren(false);
        holder.addView(panel);
        d[0] = sheet(a, holder, false);
        d[0].setOnKeyListener((dlg, code, e) -> {
            if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
                close(d[0]);
                return true;
            }
            return false;
        });
        d[0].setOnDismissListener(x2 -> {
            if (!picked[0]) pick.accept(-1);
        });
        d[0].show();
        if (first != null) first.requestFocus();
        panel.setPivotX(right ? 0 : width);
        panel.setPivotY(ph / 2f);
        panel.setScaleX(0.88f);
        panel.setScaleY(0.88f);
        panel.setTranslationX(right ? -dp(a, 18) : dp(a, 18));
        panel.animate().scaleX(1f).scaleY(1f).translationX(0).setDuration(Motion.FOCUS_MS)
            .setInterpolator(Motion.FOCUS).start();
    }
}
