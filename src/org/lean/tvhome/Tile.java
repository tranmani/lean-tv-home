package org.lean.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * One tvOS-style icon: a rounded full-bleed picture that lifts toward the viewer when focused,
 * with a soft shadow, a specular sheen and its name fading in underneath.
 */
final class Tile extends FrameLayout {
    /** Size of a focused tile relative to its resting size (tvOS grows icons by about 12%). */
    static float lift() {
        return Look.motion == 1 ? 1.1f : 1.2f;
    }

    final Object key;
    final String name;
    final int w, h;
    private final ImageView art;
    private final View sheen;
    private TextView label, badge;
    private Bitmap picture;
    private boolean focused, quiet;

    Tile(Context c, Object key, String name, int w, int h, Bitmap picture) {
        super(c);
        this.key = key;
        this.name = name;
        this.w = w;
        this.h = h;
        float r = h * 0.105f;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        setClipToOutline(true);
        setOutlineAmbientShadowColor(0xff000000);
        setOutlineSpotShadowColor(0xff000000);
        setCameraDistance(w * 12f);

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.FIT_XY);
        art.setImageBitmap(picture);
        this.picture = picture;
        addView(art, new LayoutParams(-1, -1));

        // The sheen: light falling across the top of a glossy surface, wider than the tile so
        // it can slide as the tile tilts.
        sheen = new View(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] {0x40ffffff, 0x12ffffff, 0x00ffffff});
        sheen.setBackground(g);
        sheen.setAlpha(0f);
        addView(sheen, new LayoutParams(w * 3 / 2, -1));
        sheen.setTranslationX(-w / 4f);

        // Hairline edge, as on tvOS, so dark icons do not melt into a dark background.
        View edge = new View(c);
        GradientDrawable e = new GradientDrawable();
        e.setCornerRadius(r);
        e.setColor(0);
        e.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density * 0.6f)),
            Look.light() ? 0x14000000 : 0x1affffff);
        edge.setBackground(e);
        addView(edge, new LayoutParams(-1, -1));
    }

    void setPicture(Bitmap b) {
        picture = b;
        art.setImageBitmap(b);
    }

    Bitmap picture() {
        return picture;
    }

    void setLabel(TextView t) {
        label = t;
        label.setAlpha(Look.labels == 1 && !quiet ? 1f : 0f);
        if (Look.labels == 1) label.setTextColor(Look.inkSoft);
    }

    TextView label() {
        return label;
    }

    /** No name under this icon (the Dock row); edit mode can move an icon in and out of it. */
    void setQuiet(boolean q) {
        if (quiet == q) return;
        quiet = q;
        if (label != null) label.setAlpha(!q && (focused || Look.labels == 1) ? 1f : 0f);
    }

    /** A round tick in the corner, for "on the home screen" in Add / remove apps. */
    void setBadge(Boolean on) {
        if (on == null) {
            if (badge != null) badge.setVisibility(GONE);
            return;
        }
        if (badge == null) {
            badge = new TextView(getContext());
            badge.setGravity(Gravity.CENTER);
            badge.setTypeface(Look.font(Look.BOLD));
            badge.setTextSize(13);
            int s = Math.round(h * 0.24f);
            LayoutParams lp = new LayoutParams(s, s, Gravity.TOP | Gravity.END);
            lp.topMargin = lp.rightMargin = Math.round(h * 0.07f);
            addView(badge, lp);
        }
        badge.setVisibility(VISIBLE);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(on ? 0xff30d158 : 0x99000000);
        d.setStroke(Math.round(getResources().getDisplayMetrics().density * 1.5f), 0xddffffff);
        badge.setBackground(d);
        badge.setText(on ? "✓" : "+");
        badge.setTextColor(0xffffffff);
    }

    boolean isFocusedTile() {
        return focused;
    }

    /**
     * Focus in or out. {@code dx}/{@code dy} is the direction the focus travelled, so an arriving
     * tile can tilt slightly toward it and settle, like the tvOS parallax.
     */
    void focus(boolean on, int dx, int dy) {
        focused = on;
        float s = on ? lift() : 1f;
        float z = on ? h * 0.22f : 0f;
        animate().cancel();
        animate().scaleX(s).scaleY(s).translationZ(z).rotationX(0).rotationY(0)
            .setDuration(on ? Motion.FOCUS_MS : Motion.UNFOCUS_MS)
            .setInterpolator(on ? Motion.FOCUS : Motion.SETTLE)
            .start();
        if (on && Look.motion == 0 && (dx != 0 || dy != 0)) {
            setRotationY(dx * 7f);
            setRotationX(-dy * 6f);
        }
        sheen.animate().cancel();
        if (on && Look.motion == 0) sheen.setTranslationX(-w / 4f - dx * w * 0.18f);
        sheen.animate().alpha(on ? 1f : 0f).translationX(-w / 4f)
            .setDuration(on ? 700 : Motion.UNFOCUS_MS).setInterpolator(Motion.EASE_OUT).start();
        if (label != null) {
            float grow = h * (s - 1f) / 2f;
            label.animate().cancel();
            label.setTextColor(on ? Look.ink : Look.inkSoft);
            label.animate().alpha(!quiet && (on || Look.labels == 1) ? 1f : 0f).translationY(grow)
                .setDuration(on ? Motion.FADE_MS : Motion.FADE_MS * 2 / 3)
                .setInterpolator(Motion.EASE_OUT).start();
        }
    }

    /** OK held down: the icon sinks back toward the surface, as when a tvOS icon is clicked. */
    void press(boolean down) {
        if (!focused) return;
        animate().cancel();
        float s = down ? 1.02f : lift();
        animate().scaleX(s).scaleY(s).translationZ(down ? h * 0.06f : h * 0.22f)
            .setDuration(down ? 110 : Motion.FOCUS_MS)
            .setInterpolator(down ? Motion.EASE_OUT : Motion.FOCUS).start();
    }
}
