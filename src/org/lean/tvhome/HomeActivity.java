package org.lean.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The home screen, laid out like tvOS: a Top Shelf filling the upper screen for the focused app
 * in the first row, and a grid of wide icons below it. Hold OK on an icon for its menu.
 */
public class HomeActivity extends Activity implements Grid.Listener {
    /** Keys of the built-in tiles after the apps. */
    static final String APPS = "#apps", CUSTOMIZE = "#customize", SETTINGS = "#settings";

    private FrameLayout root;
    /** The Classic layout, when chosen in Customize; null for the Apple TV layout. */
    private Classic classic;
    private Shelf shelf;
    private Grid grid;
    private Object lastKey;
    private int builtGeneration = -1;
    private int lookVersion;
    private boolean launched;
    /** An order shown without saving it (the "preview" extra), until Home is pressed. */
    private List<String> preview;
    /** Hidden behind an app (onStop until onResume). */
    private boolean stopped;
    /** Set by Customize > Rearrange Apps: pick up the first app when the Home Screen shows. */
    static boolean rearrange;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Apps.forget();
            Art.forget();
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
        if (state == null) applyExtras(getIntent());
        else if (state.getString("preview") != null) preview = split(state.getString("preview"));
        WakeService.sync(this);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);

        if (Look.layout == 1) {
            classic = new Classic(this);
            setContentView(classic.view());
            return;
        }

        root = new FrameLayout(this);
        // One flat colour: the shelf bitmap carries the gradient, and a full-screen gradient
        // drawable costs several milliseconds a frame on a TV box GPU.
        root.setBackgroundColor(Look.bgBottom);
        getWindow().setBackgroundDrawable(null);
        root.setClipChildren(false);
        int sh = getResources().getDisplayMetrics().heightPixels;
        int sw = getResources().getDisplayMetrics().widthPixels;

        shelf = new Shelf(this, sw, sh);
        if (Look.shelf == 2) shelf.hideArt();
        root.addView(shelf, new FrameLayout.LayoutParams(-1, -1));

        grid = new Grid(this);
        grid.setListener(this);
        // tvOS 26 geometry: six icons across with wide margins, the first row in a glass Dock
        // near the bottom and the top of the next row peeking in under it.
        grid.measure(sw, dp(50), Look.columns(), 0.029f);
        grid.quietTopRow = Look.shelf != 2;
        grid.top = Look.shelf != 2 ? sh - dp(14) - grid.pitch : dp(96);
        grid.anchor = Math.round(sh * 0.4f);
        int pad = Math.round(grid.tileH * 0.28f);
        shelf.setDock(dp(25), grid.top - pad, sw - dp(25), grid.top + grid.tileH + pad);
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        int probe = getIntent().getIntExtra("perf", 0);
        if (probe != 0) shelf.probe(probe, root);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (shelf != null) {
            shelf.setRunning(false);
            root.removeCallbacks(idle);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        if (shelf == null) return;
        shelf.release();
        // The pictures' native and GPU memory is only returned when the Java objects are
        // collected; collect once now rather than whenever the heap next fills up.
        root.postDelayed(() -> Runtime.getRuntime().gc(), 1500);
    }

    /** After a while without a key press on the Dock, the artwork takes the screen, as on tvOS. */
    private final Runnable idle = () -> {
        if (shelf != null && hasWindowFocus() && grid.row(grid.focusIndex()) == 0 && !grid.isMoving()
            && Look.motion == 0) {
            shelf.expand(true, grid);
        }
    };

    private void poke() {
        if (shelf == null) return;
        root.removeCallbacks(idle);
        if (Look.shelf == 0) root.postDelayed(idle, 15000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lookVersion != Look.version) {
            // Customize changed something: redraw everything with the new look.
            recreate();
            return;
        }
        if (classic != null) {
            if (builtGeneration != Apps.generation || classic.empty()) build();
            return;
        }
        stopped = false;
        if (builtGeneration != Apps.generation || grid.count() == 0) build();
        shelf.restore();
        shelf.setRunning(true);
        poke();
        if (rearrange) {
            rearrange = false;
            startRearrange();
        }
        if (launched) {
            launched = false;
            // Back from an app: the home screen settles into place, as tvOS zooms back to it.
            if (Look.motion == 0) {
                root.setScaleX(1.05f);
                root.setScaleY(1.05f);
                root.setAlpha(0.5f);
                root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(480)
                    .setInterpolator(Motion.EASE_OUT).start();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // The Home key's own intent carries Android's extras too, so look for ours by name.
        boolean fromAdb = false;
        for (String k : new String[] {"order", "preview", "title", "wol", "wol_mac"}) {
            if (intent != null && intent.hasExtra(k)) fromAdb = true;
        }
        if (!fromAdb && preview != null) {
            preview = null;
            builtGeneration = -1;
        }
        boolean changed = applyExtras(intent);
        if (lookVersion != Look.version) {
            recreate();
            return;
        }
        if (classic != null) {
            if (changed || builtGeneration != Apps.generation) build();
            classic.home();
            return;
        }
        if (grid.isMoving()) grid.endMove(true);
        shelf.collapse(grid);
        if (changed || builtGeneration != Apps.generation) build();
        // Home pressed while already home: back to the first icon and the Top Shelf. Coming
        // back from an app, the icon that opened it keeps the focus, as on tvOS.
        if (!stopped) grid.focusAt(0, false);
    }

    /**
     * Setup from a computer, for example
     * {@code adb shell am start -n org.lean.tvhome/.HomeEntry --es order "pkg1,pkg2"}.
     * {@code order} replaces the icon list; {@code preview} shows a list without saving it (until
     * Home is pressed); {@code wol} (boolean, {@code --ez}) turns Wake-on-LAN on or off;
     * {@code wol_mac} sets the box's network address for it; {@code title} sets the small title at
     * the top left ("" hides it). Returns true if the icon list changed.
     */
    private boolean applyExtras(Intent i) {
        if (i == null) return false;
        String mac = i.getStringExtra("wol_mac");
        if (mac != null) WakeService.setMac(this, mac);
        if (i.hasExtra("wol")) WakeService.setEnabled(this, i.getBooleanExtra("wol", false));
        String title = i.getStringExtra("title");
        if (title != null) Look.setTitle(this, title);
        String p = i.getStringExtra("preview");
        if (p != null) {
            preview = split(p);
            lastKey = null;
            if (classic != null) classic.forgetFocus();
            return true;
        }
        String order = i.getStringExtra("order");
        if (order == null) return false;
        Apps.save(this, split(order));
        preview = null;
        lastKey = null;
        return true;
    }

    private static List<String> split(String s) {
        List<String> list = new ArrayList<>();
        for (String p : s.split(",")) {
            p = p.trim();
            if (!p.isEmpty() && !list.contains(p)) list.add(p);
        }
        return list;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // A look change recreates the screen; keep a preview shown across it.
        if (preview != null) out.putString("preview", String.join(",", preview));
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (classic != null) return classic.key(e) || super.dispatchKeyEvent(e);
        poke();
        if (shelfKey(e)) return true;
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_UP && grid.focusIndex() >= 0 && !grid.isMoving()) {
                longPressed(grid.tile(grid.focusIndex()), grid.focusIndex());
            }
            return true;
        }
        if (grid.key(e)) return true;
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // Home has nothing behind it; Back returns to the first row, like tvOS.
            if (e.getAction() == KeyEvent.ACTION_UP && grid.row(grid.focusIndex()) > 0) grid.focusAt(0, false);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** The key that woke an idle full-screen shelf; its release is swallowed too. */
    private int swallow = -1;

    /** Keys while the artwork has the screen: left and right page, OK opens, anything else returns to the Dock. */
    private boolean shelfKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (swallow == code && !down) {
            swallow = -1;
            return true;
        }
        if (!shelf.expanded()) return false;
        if (shelf.passive()) {
            if (down) {
                swallow = code;
                shelf.collapse(grid);
            }
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shelf.next(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, true);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down && e.getRepeatCount() == 0) openTitle();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
            case KeyEvent.KEYCODE_HOME:
                return false;
            default:
                if (down) swallow = code;
                shelf.collapse(grid);
                return true;
        }
    }

    /** OK on a title: Stremio opens the title itself; other apps just open. */
    private void openTitle() {
        Feed.Item it = shelf.current();
        Object key = shelf.key();
        if (!(key instanceof String) || ((String) key).startsWith("#")) return;
        String pkg = (String) key;
        if (it != null && pkg.equals("com.stremio.one") && it.id.startsWith("tt")) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("stremio:///detail/" + it.type + "/" + it.id)).setPackage(pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                launched = true;
                return;
            } catch (RuntimeException ignored) {
                // Older Stremio: open it normally.
            }
        }
        if (Apps.launch(this, pkg, null)) launched = true;
    }

    // ---- grid ---------------------------------------------------------------------------------

    private void build() {
        builtGeneration = Apps.generation;
        List<String> home = preview != null ? preview : Apps.home(this);
        if (classic != null) {
            classic.build(home, preview != null);
            return;
        }
        List<Tile> tiles = new ArrayList<>();
        int w = grid.tileW, h = grid.tileH;
        // Pictures are drawn at the zoomed size so a focused icon stays sharp.
        int pw = Math.round(w * Tile.lift()), ph = Math.round(h * Tile.lift());
        for (String pkg : home) {
            if (!Apps.launchable(this, pkg)) continue;
            Apps.App app = Apps.get(this, pkg);
            tiles.add(new Tile(this, pkg, app.label, w, h, Art.tile(this, app, pw, ph)));
        }
        int apps = tiles.size();
        if (Look.buttons <= 1) {
            tiles.add(new Tile(this, APPS, "Apps", w, h, Art.folder(this, Apps.all(this), pw, ph)));
        }
        tiles.add(new Tile(this, CUSTOMIZE, "Customize", w, h, Art.glyph(false, pw, ph)));
        if (Look.buttons == 0) tiles.add(new Tile(this, SETTINGS, "Settings", w, h, Art.glyph(true, pw, ph)));
        grid.setTiles(tiles, apps);
        if (Look.shelf == 0) {
            // Start fetching titles for the Dock's apps so the first look is already filled.
            for (int i = 0; i < Math.min(grid.cols, apps); i++) {
                String src = Feed.source(this, (String) tiles.get(i).key);
                if (src != null) Feed.prefetch(this, Feed.list(this, src, null), 2);
            }
        }

        int at = 0;
        for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).key.equals(lastKey)) at = i;
        grid.focusAt(at, true);
        if (apps == 0) Apps.toast(this, "No apps on the home screen yet. Open Apps to add some.", true);
    }

    @Override
    public void focused(Tile t, int index) {
        lastKey = t.key;
        if (Look.shelf == 2 || grid.row(index) != 0) return;
        String key = (String) t.key;
        Bitmap wash = washes.get(key);
        if (wash == null) {
            wash = Art.wash(t.picture());
            washes.put(key, wash);
        }
        String src = Look.shelf == 0 && !key.startsWith("#") ? Feed.source(this, key) : null;
        List<Feed.Item> items = null;
        if (src != null) {
            // Apps sharing a catalog start at different titles.
            int offset = index * 3;
            items = rotate(Feed.list(this, src, fresh -> shelf.itemsUpdated(key, rotate(fresh, offset))), offset);
        }
        shelf.showApp(key, t.name, items, wash);
    }

    private final java.util.Map<String, Bitmap> washes = new java.util.HashMap<>();

    private static List<Feed.Item> rotate(List<Feed.Item> l, int by) {
        List<Feed.Item> out = new ArrayList<>();
        int n = l.size();
        for (int i = 0; i < Math.min(n, 8); i++) out.add(l.get((i + by) % n));
        return out;
    }

    @Override
    public void clicked(Tile t, int index) {
        String key = (String) t.key;
        switch (key) {
            case APPS:
                open(AppsActivity.intent(this, false));
                return;
            case CUSTOMIZE:
                open(new Intent(this, CustomizeActivity.class));
                return;
            case SETTINGS:
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS), zoomFrom(t));
                    launched = true;
                } catch (RuntimeException e) {
                    Apps.toast(this, "Settings could not open", false);
                }
                return;
            default:
                if (Apps.launch(this, key, zoomFrom(t))) launched = true;
                else Apps.toast(this, t.name + " could not open", false);
        }
    }

    private void open(Intent i) {
        startActivity(i);
        overridePendingTransition(R.anim.screen_in, R.anim.home_out);
    }

    /** The app's window grows out of its icon, as on tvOS. */
    private Bundle zoomFrom(View v) {
        if (Look.motion != 0) return null;
        return ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.getWidth(), v.getHeight()).toBundle();
    }

    @Override
    public void longPressed(Tile t, int index) {
        String key = (String) t.key;
        if (key.startsWith("#")) {
            Overlay.menu(this, t, t.name, new String[] {"Open", "Customize Home Screen"}, which -> {
                if (which == 0) clicked(t, index);
                else if (which == 1) open(new Intent(this, CustomizeActivity.class));
            });
            return;
        }
        Overlay.menu(this, t, t.name,
            new String[] {"Move", "Remove from Home Screen", "App Info", "Customize Home Screen"}, which -> {
                if (which >= 0 && which <= 1 && preview != null) {
                    Apps.toast(this, "This is a preview. Press Home to leave it first.", true);
                    return;
                }
                switch (which) {
                    case 0:
                        grid.startMove();
                        Apps.toast(this, "Move " + t.name + " with the arrows, then press OK", false);
                        return;
                    case 1:
                        Apps.toggle(this, key);
                        int next = index + 1 < grid.count() ? index + 1 : index - 1;
                        lastKey = next >= 0 ? grid.tile(next).key : null;
                        Apps.toast(this, t.name + " removed. Add it back from Apps.", true);
                        build();
                        return;
                    case 2:
                        Apps.appInfo(this, key);
                        return;
                    case 3:
                        open(new Intent(this, CustomizeActivity.class));
                        return;
                    default:
                }
            });
    }

    private void startRearrange() {
        if (preview != null || grid.count() == 0 || Apps.home(this).isEmpty()) return;
        grid.focusAt(grid.focusIndex() >= 0 && grid.focusIndex() < grid.count()
            && !((String) grid.tile(grid.focusIndex()).key).startsWith("#") ? grid.focusIndex() : 0, true);
        if (((String) grid.tile(grid.focusIndex()).key).startsWith("#")) return;
        grid.startMove();
        Apps.toast(this, "Move " + grid.tile(grid.focusIndex()).name
            + " with the arrows, then press OK. To move another app, hold OK on it and choose Move.", true);
    }

    @Override
    public boolean edge(int dx, int dy) {
        // Up from the Dock: the Dock slides away and the titles get the screen.
        if (dy < 0 && grid.row(grid.focusIndex()) == 0 && Look.shelf == 0 && shelf.hasItems()) {
            shelf.expand(false, grid);
            return true;
        }
        return false;
    }

    @Override
    public void scrolled(float y) {
        shelf.scrolled(y);
    }

    @Override
    public void moved(List<Object> keys) {
        List<String> order = new ArrayList<>();
        for (Object k : keys) order.add((String) k);
        Apps.save(this, order);
        builtGeneration = Apps.generation;
    }
}
