package org.lean.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every installed app as a tvOS folder grid. "Apps": OK opens an app, hold OK for its menu.
 * "Add or Remove Apps": OK puts an app on the Home Screen or takes it off; a tick shows which.
 */
public class AppsActivity extends Activity implements Grid.Listener {
    private static final String EXTRA_EDIT = "edit";

    private boolean edit;
    private Grid grid;
    private TextView title;
    private final Set<String> onHome = new HashSet<>();
    private String lastPkg;
    private boolean launched;

    static Intent intent(Context c, boolean edit) {
        return new Intent(c, AppsActivity.class).putExtra(EXTRA_EDIT, edit);
    }

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Look.ensure(this);
        edit = getIntent().getBooleanExtra(EXTRA_EDIT, false);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {Look.bgTop, Look.bgBottom}));
        root.setClipChildren(false);

        grid = new Grid(this);
        grid.setListener(this);
        grid.measure(getResources().getDisplayMetrics().widthPixels, dp(48), Look.columns(), 0.028f);
        grid.top = dp(edit ? 118 : 100);
        grid.anchor = grid.top + grid.pitch;
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        // The title floats over the grid and leaves with the first row, as on tvOS.
        title = Ui.title(this, edit ? "Add or Remove Apps" : "Apps");
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(34);
        root.addView(title, tp);
        if (edit) {
            TextView hint = new TextView(this);
            hint.setText("OK puts an app on the Home Screen or takes it off.");
            hint.setTypeface(Look.font(Look.REGULAR));
            hint.setTextSize(14);
            hint.setTextColor(Look.inkSoft);
            hint.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2);
            hp.topMargin = dp(72);
            root.addView(hint, hp);
            title.setTag(hint);
        }
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        if (launched) {
            launched = false;
            View root = findViewById(android.R.id.content);
            if (root != null && Look.motion == 0) {
                root.setScaleX(1.05f);
                root.setScaleY(1.05f);
                root.setAlpha(0.5f);
                root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(480).setInterpolator(Motion.EASE_OUT).start();
            }
        }
    }

    private void load() {
        onHome.clear();
        onHome.addAll(Apps.home(this));
        List<Tile> tiles = new ArrayList<>();
        int w = grid.tileW, h = grid.tileH;
        int pw = Math.round(w * Tile.lift()), ph = Math.round(h * Tile.lift());
        int at = 0;
        for (Apps.App a : Apps.all(this)) {
            Tile t = new Tile(this, a.pkg, a.label, w, h, Art.tile(this, a, pw, ph));
            if (edit) t.setBadge(onHome.contains(a.pkg));
            if (a.pkg.equals(lastPkg)) at = tiles.size();
            tiles.add(t);
        }
        grid.setTiles(tiles, 0);
        grid.focusAt(at, true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_UP && grid.focusIndex() >= 0) {
                longPressed(grid.tile(grid.focusIndex()), grid.focusIndex());
            }
            return true;
        }
        if (grid.key(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void focused(Tile t, int index) {
        lastPkg = (String) t.key;
    }

    @Override
    public void clicked(Tile t, int index) {
        String pkg = (String) t.key;
        if (edit) {
            toggle(t);
            return;
        }
        Bundle zoom = Look.motion == 0
            ? ActivityOptions.makeScaleUpAnimation(t, 0, 0, t.getWidth(), t.getHeight()).toBundle() : null;
        if (Apps.launch(this, pkg, zoom)) launched = true;
        else Apps.toast(this, t.name + " could not open", false);
    }

    private void toggle(Tile t) {
        String pkg = (String) t.key;
        boolean on = Apps.toggle(this, pkg);
        if (on) onHome.add(pkg);
        else onHome.remove(pkg);
        if (edit) t.setBadge(on);
        Apps.toast(this, t.name + (on ? " added to the Home Screen" : " taken off the Home Screen"), false);
    }

    @Override
    public void longPressed(Tile t, int index) {
        String pkg = (String) t.key;
        boolean on = onHome.contains(pkg);
        Overlay.menu(this, t, t.name, new String[] {
            edit ? (on ? "Take Off Home Screen" : "Put on Home Screen") : "Open",
            edit ? "Open" : (on ? "Remove from Home Screen" : "Add to Home Screen"),
            "App Info"}, which -> {
                boolean openIt = edit ? which == 1 : which == 0;
                if (which == 2) Apps.appInfo(this, pkg);
                else if (openIt) {
                    if (Apps.launch(this, pkg)) launched = true;
                } else if (which >= 0) toggle(t);
            });
    }

    @Override
    public boolean edge(int dx, int dy) {
        return false;
    }

    @Override
    public void scrolled(float y) {
        title.setTranslationY(-y);
        title.setAlpha(Math.max(0f, 1f - y / dp(80)));
        if (title.getTag() instanceof TextView) {
            TextView hint = (TextView) title.getTag();
            hint.setTranslationY(-y);
            hint.setAlpha(title.getAlpha());
        }
    }

    @Override
    public void moved(List<Object> keys) {
    }
}
