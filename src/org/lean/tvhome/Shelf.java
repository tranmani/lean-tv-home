package org.lean.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The tvOS 26 Top Shelf: the focused app's artwork fills the whole screen behind a frosted glass
 * Dock, with the title's logo at the top left and the clock in a glass pill at the top right.
 * Titles cross-fade every few seconds. Up from the Dock (or a while without a key press) slides
 * the Dock away so the artwork has the screen; left and right then page through the titles.
 * Scrolling down into the grid turns the artwork into a dim, blurred page background.
 */
final class Shelf extends FrameLayout {
    private static final int ADVANCE_MS = 8000;
    private static final int FADE_MS = 650;

    private final int sw, sh;
    private final float dp;
    private final ImageView soft;
    private final ImageView[] art = new ImageView[2];
    private final Title[] title = new Title[2];
    private final FrameLayout platter;
    private final ImageView[] glass = new ImageView[2];
    private final View chevron, pill;
    private final LinearLayout dots;
    private final TextView button;
    private int front, titleFront;
    private int dockTop;

    private Object key;
    private String appName;
    private Bitmap wash;
    private List<Feed.Item> items = new ArrayList<>();
    private int index;
    private boolean released;
    /** Last paging direction, and how many titles in a row had no picture. */
    private int dir = 1, skips;
    /** Bumped whenever what should be on screen changes, so late picture loads are dropped. */
    private int gen;
    private final Map<Object, Integer> lastIndex = new HashMap<>();
    private float scrollY;
    private boolean expanded, passive, running;

    /** The title block: a logo picture or the name as text, and the details shown when expanded. */
    private static final class Title extends LinearLayout {
        final ImageView logo;
        final TextView name, line;

        // The logo, name and line never overlap, so a fade can apply alpha to each child
        // instead of rendering the whole title into an offscreen layer every frame (~6 ms).
        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        Title(Context c, int maxW, int maxH) {
            super(c);
            setOrientation(VERTICAL);
            logo = new ImageView(c);
            logo.setAdjustViewBounds(true);
            logo.setMaxWidth(maxW);
            logo.setMaxHeight(maxH);
            logo.setScaleType(ImageView.ScaleType.FIT_START);
            addView(logo, new LayoutParams(-2, -2));
            name = new TextView(c);
            name.setTypeface(Look.font(Look.BOLD));
            name.setTextSize(38);
            name.setTextColor(0xffffffff);
            name.setMaxWidth(maxW * 3 / 2);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setLetterSpacing(-0.01f);
            name.setShadowLayer(18, 0, 2, 0x88000000);
            addView(name, new LayoutParams(-2, -2));
            line = new TextView(c);
            line.setTypeface(Look.font(Look.MEDIUM));
            line.setTextSize(15);
            line.setTextColor(0xccffffff);
            line.setShadowLayer(10, 0, 1, 0x88000000);
            line.setAlpha(0f);
            LayoutParams lp = new LayoutParams(-2, -2);
            lp.topMargin = Math.round(maxH * 0.1f);
            addView(line, lp);
        }

        void set(Bitmap pic, String text, String details) {
            logo.setImageBitmap(pic);
            logo.setVisibility(pic != null ? VISIBLE : GONE);
            name.setText(text);
            name.setVisibility(pic == null ? VISIBLE : GONE);
            line.setText(details == null ? "" : details);
        }
    }

    Shelf(Context c, int sw, int sh) {
        super(c);
        this.sw = sw;
        this.sh = sh;
        dp = getResources().getDisplayMetrics().density;
        setClipChildren(false);

        soft = image(c, ImageView.ScaleType.FIT_XY);
        addView(soft, new LayoutParams(-1, -1));
        for (int i = 0; i < 2; i++) {
            art[i] = image(c, ImageView.ScaleType.CENTER_CROP);
            addView(art[i], new LayoutParams(-1, -1));
        }
        for (int i = 0; i < 2; i++) {
            title[i] = new Title(c, Math.round(sw * 0.31f), Math.round(sh * 0.17f));
            title[i].setAlpha(0f);
            LayoutParams lp = new LayoutParams(-2, -2);
            lp.leftMargin = d(44);
            lp.topMargin = d(40);
            addView(title[i], lp);
        }
        chevron = new Chevron(c);
        chevron.setAlpha(0f);
        addView(chevron, new LayoutParams(d(36), d(14), Gravity.CENTER_HORIZONTAL));

        platter = new FrameLayout(c);
        platter.setClipChildren(true);
        float r = d(22);
        platter.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        platter.setClipToOutline(true);
        for (int i = 0; i < 2; i++) {
            glass[i] = image(c, ImageView.ScaleType.FIT_XY);
            platter.addView(glass[i], new LayoutParams(sw, sh));
        }
        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(r);
        rim.setColor(0x14ffffff);
        rim.setStroke(Math.max(1, Math.round(dp * 0.75f)), 0x30ffffff);
        platter.setForeground(rim);
        addView(platter, new LayoutParams(0, 0));

        dots = new LinearLayout(c);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setAlpha(0f);
        LayoutParams dl = new LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        dl.bottomMargin = d(30);
        addView(dots, dl);

        button = new TextView(c);
        button.setTypeface(Look.font(Look.SEMIBOLD));
        button.setTextSize(16);
        button.setTextColor(0xff000000);
        button.setGravity(Gravity.CENTER);
        button.setPadding(d(26), 0, d(26), 0);
        GradientDrawable bd = new GradientDrawable();
        bd.setCornerRadius(d(23));
        bd.setColor(0xf2ffffff);
        button.setBackground(bd);
        button.setElevation(d(8));
        button.setAlpha(0f);
        LayoutParams bl = new LayoutParams(-2, d(46));
        bl.leftMargin = d(44);
        addView(button, bl);

        pill = pill(c);
    }

    private ImageView image(Context c, ImageView.ScaleType type) {
        ImageView v = new ImageView(c);
        v.setScaleType(type);
        v.setAlpha(0f);
        return v;
    }

    private int d(float v) {
        return Math.round(v * dp);
    }

    /** Clock, date and the optional title in a glass capsule at the top right, as in tvOS 26. */
    private View pill(Context c) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(d(18), 0, d(18), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(d(14));
        g.setColor(0x5c2c2c30);
        g.setStroke(Math.max(1, Math.round(dp * 0.75f)), 0x2affffff);
        bar.setBackground(g);
        boolean any = false;
        if (!Look.title.trim().isEmpty()) {
            TextView t = new TextView(c);
            t.setText(Look.title);
            t.setTypeface(Look.font(Look.SEMIBOLD));
            t.setTextSize(12.5f);
            t.setLetterSpacing(0.1f);
            t.setTextColor(0xb3ffffff);
            bar.addView(t);
            any = true;
        }
        if (Look.clock != 2) {
            if (Look.date == 1) {
                TextClock d = new TextClock(c);
                d.setFormat12Hour("EEE d MMM");
                d.setFormat24Hour("EEE d MMM");
                d.setTypeface(Look.font(Look.MEDIUM));
                d.setTextSize(14.5f);
                d.setTextColor(0xccffffff);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (any) lp.leftMargin = d(14);
                bar.addView(d, lp);
                any = true;
            }
            TextClock clock = new TextClock(c);
            String f = Look.clock == 1 ? "h:mm a" : "H:mm";
            clock.setFormat12Hour(f);
            clock.setFormat24Hour(f);
            clock.setTypeface(Look.font(Look.SEMIBOLD));
            clock.setTextSize(15.5f);
            clock.setTextColor(0xffffffff);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (any) lp.leftMargin = d(14);
            bar.addView(clock, lp);
            any = true;
        }
        LayoutParams lp = new LayoutParams(-2, d(28), Gravity.END);
        lp.topMargin = d(30);
        lp.rightMargin = d(32);
        bar.setVisibility(any ? VISIBLE : GONE);
        addView(bar, lp);
        return bar;
    }

    /** Where the Dock's glass sits behind the first row, in screen pixels at scroll 0. */
    void setDock(int left, int top, int right, int bottom) {
        dockTop = top;
        LayoutParams lp = (LayoutParams) platter.getLayoutParams();
        lp.width = right - left;
        lp.height = bottom - top;
        lp.leftMargin = left;
        lp.topMargin = top;
        platter.setLayoutParams(lp);
        for (ImageView g : glass) {
            g.setTranslationX(-left);
            g.setTranslationY(-top);
        }
        LayoutParams cl = (LayoutParams) chevron.getLayoutParams();
        cl.topMargin = top - d(26);
        chevron.setLayoutParams(cl);
        LayoutParams bl = (LayoutParams) button.getLayoutParams();
        bl.topMargin = Math.round(sh * 0.80f);
        button.setLayoutParams(bl);
    }

    /** Top Shelf turned off in Customize: only the clock pill stays. */
    void hideArt() {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v != pill) v.setVisibility(GONE);
        }
    }

    // ---- what is shown ------------------------------------------------------------------------

    /**
     * Shows an app's shelf: its titles if it has any (may be empty), else {@code wash}, the app's
     * own colours, with its name. {@code key} avoids redoing the same app.
     */
    /**
     * The home screen is hidden (an app opened): drop the large pictures so the app gets that
     * memory. {@link #restore} shows the current title again from the disk cache.
     */
    void release() {
        if (key == null || released) return;
        released = true;
        gen++;
        for (ImageView v : art) v.setImageDrawable(null);
        for (ImageView v : glass) v.setImageDrawable(null);
        soft.setImageDrawable(null);
        for (Title t : title) t.logo.setImageDrawable(null);
        Feed.trim();
    }

    void restore() {
        if (!released) return;
        released = false;
        if (items.isEmpty()) showWash();
        else showItem(false);
    }

    void showApp(Object key, String name, List<Feed.Item> list, Bitmap wash) {
        if (key.equals(this.key)) return;
        if (this.key != null) lastIndex.put(this.key, index);
        this.key = key;
        appName = name;
        this.wash = wash;
        items = list == null ? new ArrayList<>() : list;
        Integer at = lastIndex.get(key);
        index = at == null || at >= items.size() ? 0 : at;
        skips = 0;
        gen++;
        button.setText("Open " + name);
        buildDots();
        if (items.isEmpty()) {
            showWash();
        } else {
            showItem(false);
        }
        schedule();
    }

    /** A fresher list arrived for the app on screen. */
    void itemsUpdated(Object key, List<Feed.Item> list) {
        if (!key.equals(this.key) || list.isEmpty()) return;
        boolean wasEmpty = items.isEmpty();
        items = list;
        if (index >= items.size()) index = 0;
        buildDots();
        if (wasEmpty) {
            gen++;
            showItem(false);
            schedule();
        }
    }

    Object key() {
        return key;
    }

    Feed.Item current() {
        return items.isEmpty() ? null : items.get(index);
    }

    boolean hasItems() {
        return !items.isEmpty();
    }

    private void showWash() {
        setPicture(wash, null, null);
        setTitle(null, appName, null);
        chevron.animate().alpha(0f).setDuration(Motion.FADE_MS).start();
    }

    /**
     * Shows items[index]. While its pictures load, the current artwork stays; if that takes long,
     * the app's colours come in so the screen never shows the previous app for long.
     */
    private void showItem(boolean paging) {
        Feed.Item it = items.get(index);
        int my = gen;
        Feed.Pics p = Feed.cached(it);
        if (p != null) {
            present(it, p);
        } else {
            if (!paging) postDelayed(() -> {
                if (gen == my && Feed.cached(it) == null) {
                    setPicture(wash, null, null);
                    setTitle(null, appName, null);
                }
            }, 380);
            Feed.load(getContext(), it, sw, sh, pics -> {
                if (gen != my) return;
                if (pics != null) {
                    skips = 0;
                    present(it, pics);
                } else if (skips++ < items.size()) {
                    // No picture for this title (download failed): move on in the same direction.
                    next(paging ? dir : 1, false);
                }
            });
        }
        updateDots();
    }

    private void present(Feed.Item it, Feed.Pics p) {
        setPicture(p.bg, p.soft, it);
        setTitle(p.logo, it.name, it.line);
        chevron.animate().alpha(expanded || scrollY > 1 ? 0f : 0.75f).setDuration(Motion.FADE_MS).start();
    }

    private void setPicture(Bitmap pic, Bitmap blurred, Feed.Item it) {
        int back = front;
        front = 1 - front;
        art[front].setImageBitmap(pic);
        art[front].setScaleType(it == null ? ImageView.ScaleType.FIT_XY : ImageView.ScaleType.CENTER_CROP);
        Bitmap b = blurred != null ? blurred : pic;
        glass[front].setImageBitmap(tint(b, 0.58f, 0xff000000));
        soft.setImageBitmap(Look.light() ? tint(b, 0.62f, Look.bgTop) : tint(b, 0.58f, Look.bgBottom));
        // The new picture fades in on top; the old one leaves after, so the screen never dips.
        art[front].bringToFront();
        crossfade(art[front], art[back], visibleArt());
        crossfade(glass[front], glass[back], 1f);
        reorder();
    }

    /** Keeps the overlays above the two pictures after bringToFront. */
    private void reorder() {
        for (Title t : title) t.bringToFront();
        chevron.bringToFront();
        platter.bringToFront();
        dots.bringToFront();
        button.bringToFront();
        pill.bringToFront();
    }

    private void setTitle(Bitmap logo, String name, String line) {
        Title in = title[1 - titleFront], out = title[titleFront];
        titleFront = 1 - titleFront;
        in.set(logo, name, line);
        in.line.setAlpha(expanded ? 1f : 0f);
        in.setTranslationY(titleY(in));
        in.animate().cancel();
        in.setTranslationX(d(10));
        in.animate().alpha(titleAlpha()).translationX(0).setDuration(FADE_MS).setStartDelay(120)
            .setInterpolator(Motion.EASE_OUT).start();
        out.animate().cancel();
        out.animate().alpha(0f).translationX(-d(10)).setDuration(FADE_MS / 2).setStartDelay(0)
            .setInterpolator(Motion.EASE_OUT).start();
    }

    private static void crossfade(View in, View out, float to) {
        in.animate().cancel();
        in.setAlpha(0f);
        in.animate().alpha(to).setDuration(FADE_MS).setStartDelay(0).setInterpolator(Motion.EASE_OUT).start();
        out.animate().cancel();
        out.animate().alpha(0f).setDuration(FADE_MS / 2).setStartDelay(FADE_MS).start();
    }

    /** A small copy of {@code b} mixed toward {@code to}, for glass and the dimmed page. */
    private static Bitmap tint(Bitmap b, float keep, int to) {
        Bitmap s = Bitmap.createScaledBitmap(b, 64, 36, true);
        Bitmap o = s.copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[64 * 36];
        o.getPixels(px, 0, 64, 0, 0, 64, 36);
        int tr = Color.red(to), tg = Color.green(to), tb = Color.blue(to);
        float u = 1 - keep;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            px[i] = 0xff000000 | Math.round(((p >> 16) & 0xff) * keep + tr * u) << 16
                | Math.round(((p >> 8) & 0xff) * keep + tg * u) << 8 | Math.round((p & 0xff) * keep + tb * u);
        }
        o.setPixels(px, 0, 64, 0, 0, 64, 36);
        return o;
    }

    // ---- carousel -----------------------------------------------------------------------------

    private final Runnable advance = () -> {
        if (running && items.size() > 1 && scrollY < 1) next(1, false);
        schedule();
    };

    private void schedule() {
        removeCallbacks(advance);
        if (running && items.size() > 1) postDelayed(advance, ADVANCE_MS);
    }

    /** Pages to the next or previous title. */
    void next(int d, boolean user) {
        if (items.size() < 2) return;
        dir = d;
        index = (index + d + items.size()) % items.size();
        gen++;
        showItem(true);
        if (user) schedule();
        // Warm the one after, so paging stays instant.
        Feed.Item ahead = items.get((index + d + items.size()) % items.size());
        if (Feed.cached(ahead) == null) Feed.load(getContext(), ahead, sw, sh, p -> { });
    }

    void setRunning(boolean on) {
        running = on;
        schedule();
    }

    private void buildDots() {
        dots.removeAllViews();
        if (items.size() < 2) return;
        for (int i = 0; i < items.size(); i++) {
            View v = new View(getContext());
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xffffffff);
            v.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(6), d(6));
            lp.leftMargin = lp.rightMargin = d(4);
            dots.addView(v, lp);
        }
        updateDots();
    }

    private void updateDots() {
        for (int i = 0; i < dots.getChildCount(); i++) dots.getChildAt(i).setAlpha(i == index ? 1f : 0.35f);
    }

    // ---- full screen --------------------------------------------------------------------------

    boolean expanded() {
        return expanded;
    }

    boolean passive() {
        return passive;
    }

    /**
     * The Dock slides away and the artwork has the screen: the title moves down to the lower left
     * with its details and an Open button. {@code passive} is the idle version, which any key ends.
     */
    void expand(boolean passive, View grid) {
        if (expanded || items.isEmpty()) return;
        expanded = true;
        this.passive = passive;
        int ms = passive ? 1100 : 560;
        float down = sh - dockTop + d(20);
        platter.animate().translationY(down).alpha(0f).setDuration(ms).setInterpolator(Motion.EASE_OUT).start();
        grid.animate().translationY(down).alpha(0f).setDuration(ms).setInterpolator(Motion.EASE_OUT).start();
        chevron.animate().alpha(0f).setDuration(Motion.FADE_MS).start();
        for (Title t : title) {
            t.animate().translationY(titleY(t)).setDuration(ms).setInterpolator(Motion.EASE_OUT).start();
            t.line.animate().alpha(1f).setDuration(ms).start();
        }
        dots.animate().alpha(1f).setDuration(ms).start();
        button.setTranslationY(d(16));
        button.animate().alpha(passive ? 0f : 1f).translationY(0).setDuration(ms).setInterpolator(Motion.EASE_OUT).start();
    }

    void collapse(View grid) {
        if (!expanded) return;
        expanded = false;
        passive = false;
        int ms = 480;
        platter.animate().translationY(-scrollY).alpha(platterAlpha()).setDuration(ms).setInterpolator(Motion.EASE_OUT).start();
        grid.animate().translationY(0).alpha(1f).setDuration(ms).setInterpolator(Motion.EASE_OUT).start();
        if (hasItems()) chevron.animate().alpha(0.75f).setDuration(ms).start();
        for (Title t : title) {
            t.animate().translationY(titleY(t)).setDuration(ms).setInterpolator(Motion.EASE_OUT).start();
            t.line.animate().alpha(0f).setDuration(ms / 2).start();
        }
        dots.animate().alpha(0f).setDuration(ms / 2).start();
        button.animate().alpha(0f).setDuration(ms / 2).start();
    }

    /** Title position: top left normally, above the Open button when expanded. */
    private float titleY(Title t) {
        if (!expanded) return -scrollY * 0.5f;
        t.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(sh, MeasureSpec.AT_MOST));
        return sh * 0.80f - d(24) - t.getMeasuredHeight() - d(40);
    }

    private float titleAlpha() {
        return expanded ? 1f : Math.max(0f, 1f - progress() * 1.8f);
    }

    private float platterAlpha() {
        return Math.max(0f, 1f - progress() * 1.5f);
    }

    private float visibleArt() {
        return 1f - progress();
    }

    private float progress() {
        return Math.min(1f, scrollY / (sh * 0.32f));
    }

    /** The page scrolled: the Dock rides with the first row, the artwork dims into a blur. */
    void scrolled(float y) {
        scrollY = y;
        float p = progress();
        art[front].animate().cancel();
        art[front].setAlpha(1f - p);
        art[1 - front].setAlpha(0f);
        soft.setAlpha(p);
        platter.setTranslationY(-y);
        platter.setAlpha(platterAlpha());
        chevron.setAlpha(hasItems() ? Math.max(0f, 0.75f - p * 3f) : 0f);
        // The icons scroll up under the clock: fade it out before they reach it.
        pill.setAlpha(Math.max(0f, 1f - y / (sh * 0.12f)));
        for (int i = 0; i < 2; i++) {
            Title t = title[i];
            t.setTranslationY(-y * 0.5f);
            if (i == titleFront) {
                t.animate().cancel();
                t.setTranslationX(0);
                t.setAlpha(titleAlpha());
            }
        }
    }

    /** The little chevron above the Dock that says "there is more up here". */
    private static final class Chevron extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        Chevron(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(0xffffffff);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), s = h * 0.18f;
            p.setStrokeWidth(s);
            path.reset();
            path.moveTo(s, h - s);
            path.lineTo(w / 2, s);
            path.lineTo(w - s, h - s);
            cv.drawPath(path, p);
        }
    }

    /**
     * Perf probe ({@code perf} extra, see perf.sh): hides layers by bit mask so their GPU cost
     * can be measured. 1 artwork, 2 Dock glass, 4 titles, 8 page colour, 16 clock/chevron/dots.
     */
    void probe(int m, View root) {
        if ((m & 1) != 0) {
            for (View v : art) v.setVisibility(GONE);
            soft.setVisibility(GONE);
        }
        if ((m & 2) != 0) platter.setVisibility(GONE);
        if ((m & 4) != 0) for (View v : title) v.setVisibility(GONE);
        if ((m & 8) != 0) root.setBackground(null);
        if ((m & 16) != 0) {
            pill.setVisibility(GONE);
            chevron.setVisibility(GONE);
            dots.setVisibility(GONE);
        }
    }
}
