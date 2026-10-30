package org.lean.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Icon artwork in the tvOS shape: a wide picture filling the whole rounded tile. An app's TV
 * banner is used as it is; without one, the launcher icon is placed on a matching background so
 * the tile still reads as one full-bleed image.
 */
final class Art {
    private Art() {}

    /** Tile width to height, as on tvOS (400 x 240). */
    static final float ASPECT = 5f / 3f;

    private static final Map<String, Bitmap> tiles = new HashMap<>();

    static void forget() {
        tiles.clear();
    }

    /** The tile picture for an app, rendered at w x h pixels and cached. */
    static Bitmap tile(Context c, Apps.App app, int w, int h) {
        String key = app.pkg + "@" + w + "x" + h;
        Bitmap b = tiles.get(key);
        if (b != null) return b;
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Drawable banner = banner(c, app.pkg);
        if (banner != null) {
            cover(cv, banner, w, h);
        } else {
            iconOnBackground(cv, Apps.icon(c, app), w, h);
        }
        tiles.put(key, b);
        return b;
    }

    /**
     * A full-screen backdrop in an app's own colours, for apps without titles to show: its
     * picture shrunk, blurred into soft colour fields, kept dark enough for white text, and
     * darker toward the Dock. Tiny; the view scales it up with filtering.
     */
    static Bitmap wash(Bitmap picture) {
        Bitmap small = Bitmap.createScaledBitmap(picture, 64, 36, true);
        Bitmap b = blur(small.copy(Bitmap.Config.ARGB_8888, true), 4);
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        float[] hsv = new float[3];
        for (int y = 0; y < h; y++) {
            float f = y / (float) (h - 1);
            float shade = f < 0.4f ? 1f : 1f - (f - 0.4f) / 0.6f * 0.55f;
            for (int x = 0; x < w; x++) {
                Color.colorToHSV(px[y * w + x], hsv);
                hsv[1] = Math.min(1f, hsv[1] * 1.3f);
                hsv[2] = Math.min(hsv[2], 0.7f) * 0.8f * shade;
                px[y * w + x] = Color.HSVToColor(hsv);
            }
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    private static int mix(int a, int b, float t) {
        float u = 1 - t;
        return 0xff000000
            | Math.round(Color.red(a) * u + Color.red(b) * t) << 16
            | Math.round(Color.green(a) * u + Color.green(b) * t) << 8
            | Math.round(Color.blue(a) * u + Color.blue(b) * t);
    }

    /** The leanback banner of the app's TV entry, else the app's own banner, else null. */
    static Drawable banner(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
            List<ResolveInfo> found = pm.queryIntentActivities(i, 0);
            for (ResolveInfo ri : found) {
                Drawable d = ri.activityInfo.loadBanner(pm);
                if (d != null) return d;
            }
            return pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** Draws d to fill w x h, cropping the overflow evenly (centre crop). */
    private static void cover(Canvas cv, Drawable d, int w, int h) {
        int iw = Math.max(1, d.getIntrinsicWidth()), ih = Math.max(1, d.getIntrinsicHeight());
        if (d.getIntrinsicWidth() <= 0) {
            d.setBounds(0, 0, w, h);
            d.draw(cv);
            return;
        }
        float s = Math.max(w / (float) iw, h / (float) ih);
        int dw = Math.round(iw * s), dh = Math.round(ih * s);
        int x = (w - dw) / 2, y = (h - dh) / 2;
        if (d instanceof BitmapDrawable) ((BitmapDrawable) d).setFilterBitmap(true);
        d.setBounds(x, y, x + dw, y + dh);
        d.draw(cv);
    }

    private static void iconOnBackground(Canvas cv, Drawable icon, int w, int h) {
        if (icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            // The background layer fills the tile; the foreground keeps its 108dp canvas so the
            // logo in its 66dp safe zone ends up at about 60% of the tile height.
            Drawable bg = a.getBackground(), fg = a.getForeground();
            if (bg != null) {
                bg.setBounds(0, -(w - h) / 2, w, h + (w - h) / 2);
                bg.draw(cv);
            } else {
                gradient(cv, w, h, 0xff3a3a3c, 0xff232325);
            }
            if (fg != null) {
                int s = h;
                fg.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
                fg.draw(cv);
            }
            return;
        }
        // Legacy square icon: if its edge is a solid colour, extend that colour so the icon
        // blends into a full tile; otherwise sit it on a neutral dark gradient.
        int s = Math.round(h * 0.62f);
        Bitmap ib = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas ic = new Canvas(ib);
        icon.setBounds(0, 0, s, s);
        icon.draw(ic);
        int edge = edgeColour(ib);
        if (edge != 0) {
            cv.drawColor(edge);
            // Draw the icon bigger when it is a full-bleed square: its own background then
            // merges into the tile and only the logo stands out.
            int big = Math.round(h * 0.8f);
            icon.setBounds((w - big) / 2, (h - big) / 2, (w + big) / 2, (h + big) / 2);
            icon.draw(cv);
        } else {
            gradient(cv, w, h, 0xff3a3a3c, 0xff232325);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            cv.drawBitmap(ib, (w - s) / 2f, (h - s) / 2f, p);
        }
        ib.recycle();
    }

    /** The average edge colour if the icon's border is opaque and nearly one colour, else 0. */
    private static int edgeColour(Bitmap b) {
        int n = b.getWidth(), count = 0;
        long r = 0, g = 0, bl = 0;
        int inset = Math.max(1, n / 24);
        int[] px = new int[4 * (n - 2 * inset)];
        for (int i = inset; i < n - inset; i++) {
            px[count++] = b.getPixel(i, inset);
            px[count++] = b.getPixel(i, n - 1 - inset);
            px[count++] = b.getPixel(inset, i);
            px[count++] = b.getPixel(n - 1 - inset, i);
        }
        for (int p : px) {
            if (Color.alpha(p) < 250) return 0;
            r += Color.red(p);
            g += Color.green(p);
            bl += Color.blue(p);
        }
        int avg = Color.rgb((int) (r / count), (int) (g / count), (int) (bl / count));
        long spread = 0;
        for (int p : px) {
            spread += Math.abs(Color.red(p) - Color.red(avg)) + Math.abs(Color.green(p) - Color.green(avg))
                + Math.abs(Color.blue(p) - Color.blue(avg));
        }
        return spread / count < 24 ? avg : 0;
    }

    private static void gradient(Canvas cv, int w, int h, int top, int bottom) {
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
    }

    // ---- built-in tiles -----------------------------------------------------------------------

    /** Light grey Apple-style system tile with a gear (Settings) or sliders (Customize). */
    static Bitmap glyph(boolean gear, int w, int h) {
        String key = (gear ? "#gear@" : "#sliders@") + w + "x" + h;
        Bitmap b = tiles.get(key);
        if (b != null) return b;
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        gradient(cv, w, h, 0xffe9e9ee, 0xffb9b9c0);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xff5a5a62);
        float cx = w / 2f, cy = h / 2f, r = h * 0.3f;
        if (gear) {
            Path path = new Path();
            int teeth = 12;
            for (int i = 0; i < teeth * 4; i++) {
                double a = Math.PI * 2 * i / (teeth * 4);
                float rr = (i % 4 == 0 || i % 4 == 1) ? r : r * 0.8f;
                float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();
            path.addCircle(cx, cy, r * 0.36f, Path.Direction.CCW);
            path.setFillType(Path.FillType.EVEN_ODD);
            cv.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.07f);
            cv.drawCircle(cx, cy, r * 0.62f, p);
        } else {
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(h * 0.045f);
            float[] knob = {0.3f, 0.68f, 0.45f};
            for (int i = 0; i < 3; i++) {
                float y = cy + (i - 1) * r * 0.75f, x0 = cx - r * 1.25f, x1 = cx + r * 1.25f;
                p.setStyle(Paint.Style.STROKE);
                cv.drawLine(x0, y, x1, y, p);
                p.setStyle(Paint.Style.FILL);
                float kx = x0 + (x1 - x0) * knob[i];
                p.setColor(0xfff7f7f9);
                cv.drawCircle(kx, y, h * 0.07f, p);
                p.setColor(0xff5a5a62);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(h * 0.03f);
                cv.drawCircle(kx, y, h * 0.07f, p);
                p.setStrokeWidth(h * 0.045f);
            }
        }
        tiles.put(key, b);
        return b;
    }

    /** A tvOS folder: frosted tile with up to six app pictures in a 3 x 2 grid. */
    static Bitmap folder(Context c, List<Apps.App> apps, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        gradient(cv, w, h, Look.light() ? 0xffd6d6db : 0xff48484c, Look.light() ? 0xffc4c4ca : 0xff38383c);
        float pad = h * 0.13f, gap = h * 0.06f;
        float cw = (w - 2 * pad - 2 * gap) / 3f, ch = cw / ASPECT;
        float top = (h - (2 * ch + gap)) / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF r = new RectF();
        for (int i = 0; i < Math.min(6, apps.size()); i++) {
            float x = pad + (i % 3) * (cw + gap), y = top + (i / 3) * (ch + gap);
            r.set(x, y, x + cw, y + ch);
            Bitmap t = tile(c, apps.get(i), Math.round(cw), Math.round(ch));
            Path clip = new Path();
            clip.addRoundRect(r, ch * 0.12f, ch * 0.12f, Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            cv.drawBitmap(t, null, r, p);
            cv.restore();
        }
        return b;
    }

    // ---- blur ---------------------------------------------------------------------------------

    /** Box blur, a few passes, in place. Only for tiny bitmaps. */
    static Bitmap blurSmall(Bitmap b, int passes) {
        return blur(b, passes);
    }

    private static Bitmap blur(Bitmap b, int passes) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < passes; pass++) {
            boxPass(px, tmp, w, h, true);
            boxPass(tmp, px, w, h, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    private static void boxPass(int[] in, int[] out, int w, int h, boolean horizontal) {
        int rad = 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 0, g = 0, bl = 0, n = 0;
                for (int k = -rad; k <= rad; k++) {
                    int xx = horizontal ? Math.max(0, Math.min(w - 1, x + k)) : x;
                    int yy = horizontal ? y : Math.max(0, Math.min(h - 1, y + k));
                    int p = in[yy * w + xx];
                    r += (p >> 16) & 0xff;
                    g += (p >> 8) & 0xff;
                    bl += p & 0xff;
                    n++;
                }
                out[y * w + x] = 0xff000000 | (r / n << 16) | (g / n << 8) | (bl / n);
            }
        }
    }
}
