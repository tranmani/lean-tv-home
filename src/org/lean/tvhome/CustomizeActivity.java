package org.lean.tvhome;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.KeyEvent;
import android.widget.EditText;

/** Customize, laid out like tvOS Settings: OK on a row steps to its next value. */
public class CustomizeActivity extends Activity {
    private interface Action {
        void run();
    }

    /** One row: a label, the current value, and what OK does. */
    private static final class Item {
        final String label;
        final java.util.function.Supplier<String> value;
        final Action ok;

        Item(String label, java.util.function.Supplier<String> value, Action ok) {
            this.label = label;
            this.value = value;
            this.ok = ok;
        }
    }

    private final java.util.List<Item> items = new java.util.ArrayList<>();
    private Ui.ListPage list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Look.ensure(this);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int w = sw * 26 / 100;
        list = Ui.settingsScreen(this, "Customize",
            "OK changes a setting. Press Back to see it on the Home Screen.",
            Art.glyph(false, w, Math.round(w / Art.ASPECT)));

        choice("Home Screen Layout", "layout", Look.LAYOUTS, () -> Look.layout, true);
        choice("Appearance", "theme", Look.THEMES, () -> Look.theme, true);
        choice("Icon Size", "tileSize", Look.TILE_SIZES, () -> Look.tileSize, false);
        choice("App Names", "labels", Look.LABELS, () -> Look.labels, false);
        // Only the Apple TV layout has a Top Shelf.
        if (Look.layout == 0) choice("Top Shelf", "shelf", Look.SHELF, () -> Look.shelf, false);
        choice("Clock", "clock", Look.CLOCKS, () -> Look.clock, false);
        choice("Date", "date", Look.SHOWN, () -> Look.date, false);
        items.add(new Item("Title",
            () -> Look.title.trim().isEmpty() ? "Hidden" : Look.title.replaceAll("\\s+", " "), this::editTitle));
        choice("Motion", "motion", Look.MOTION, () -> Look.motion, false);
        choice("Icons After Your Apps", "buttons", Look.BUTTONS, () -> Look.buttons, false);
        if (Look.layout == 0) items.add(new Item("Rearrange Apps", () -> "", () -> {
            // Back to the Home Screen with the first app picked up, ready to move.
            HomeActivity.rearrange = true;
            finish();
        }));
        items.add(new Item("Add or Remove Apps", () -> "", () -> {
            startActivity(AppsActivity.intent(this, true));
        }));
        items.add(new Item("Wake-on-LAN", this::wakeState, () -> {
            WakeService.setEnabled(this, !WakeService.enabled(this));
            list.postDelayed(this::refresh, 600);
            refresh();
        }));
        items.add(new Item("How to Wake This Box", () -> "", this::wakeHelp));
        items.add(new Item("Use Google TV Home Instead", () -> "", () -> GoogleHome.switchTo(this)));
        items.add(new Item("Turn Google TV Home Off", () -> "Saves memory", () -> Apps.googleOffHelp(this)));
        items.add(new Item("Reset the Look", () -> "", () ->
            Overlay.alert(this, "Reset the Look?", "Appearance, sizes, clock, title and motion go back to "
                + "how they started. Your apps and their order stay.", null, new String[] {"Reset", "Cancel"}, w2 -> {
                    if (w2 != 0) return;
                    Look.reset(this);
                    recreate();
                })));

        for (Item it : items) list.add().label.setText(it.label);
        list.onClick(i -> items.get(i).ok.run());
        refresh();
        list.focusAt(state != null ? state.getInt("pos", 0) : 0, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        GoogleHome.resume(this);
        refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("pos", list.focusIndex());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (list.key(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private void refresh() {
        for (int i = 0; i < items.size(); i++) list.row(i).value.setText(items.get(i).value.get());
    }

    /** A row that cycles through fixed values. Appearance changes redraw this screen too. */
    private void choice(String label, String key, String[] values, java.util.function.IntSupplier now,
            boolean redraw) {
        items.add(new Item(label, () -> values[now.getAsInt()], () -> {
            Look.set(this, key, (now.getAsInt() + 1) % values.length);
            if (redraw) recreate();
            else refresh();
        }));
    }

    private void editTitle() {
        EditText in = new EditText(this);
        in.setText(Look.title.trim().isEmpty() ? "" : Look.title);
        in.setHint("For example LIVING ROOM");
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        in.setSelectAllOnFocus(true);
        in.setTypeface(Look.font(Look.MEDIUM));
        in.setTextSize(18);
        in.setTextColor(Look.ink);
        in.setHintTextColor(Look.inkSoft);
        int pad = Ui.dp(this, 16);
        in.setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 12));
        bg.setColor(Look.light() ? 0x1f000000 : 0x2effffff);
        in.setBackground(bg);
        Overlay.alert(this, "Title", "A small title at the top of the Home Screen.", in,
            new String[] {"Save", "Hide"}, w -> {
                if (w == 0) Look.setTitle(this, in.getText().toString());
                else if (w == 1) Look.setTitle(this, "");
                refresh();
            });
    }

    private String wakeState() {
        if (!WakeService.enabled(this)) return "Off";
        if (!WakeService.canTurnScreenOn(this)) return "Needs one adb command";
        java.util.List<Integer> ports;
        synchronized (WakeService.listening) {
            ports = new java.util.ArrayList<>(WakeService.listening);
        }
        if (ports.isEmpty()) return "Starting";
        StringBuilder sb = new StringBuilder("On, port");
        if (ports.size() > 1) sb.append('s');
        for (int i = 0; i < ports.size(); i++) sb.append(i == 0 ? " " : " and ").append(ports.get(i));
        return sb.toString();
    }

    private void wakeHelp() {
        String mac = WakeService.mac(this);
        String last = WakeService.lastWake == 0 ? "none yet"
            : DateUtils.getRelativeTimeSpanString(WakeService.lastWake).toString();
        Overlay.info(this, "Wake-on-LAN",
            "With Wake-on-LAN on, any Wake-on-LAN app or tool on the same network can "
                + "turn this box on (and the TV with it, through HDMI-CEC).\n\n"
                + "Send the magic packet to " + Apps.ipAddress(this) + " or to the network broadcast "
                + "address, UDP port " + WakeService.PORT + " (set this port in your Wake-on-LAN app; the usual "
                + "port 9 is reserved for system apps on Android).\n"
                + "Network address (MAC): " + (mac.isEmpty() ? "not set, so any magic packet wakes "
                    + "the box" : mac) + "\n"
                + "Last wake: " + last + "\n\n"
                + (WakeService.canTurnScreenOn(this) ? "" : "Not ready yet: Android must allow this "
                    + "app to turn the screen on. Run once from a computer:\n"
                    + "adb shell appops set " + getPackageName() + " TURN_SCREEN_ON allow\n\n")
                + "To accept only packets for this box, set its MAC once from a computer:\n"
                + "adb shell am start -n " + getPackageName() + "/.HomeEntry --es wol_mac "
                + "aa:bb:cc:dd:ee:ff\n\n"
                + "This keeps the box out of deep sleep while the screen is off, so it uses a "
                + "little more power in standby. A box that is unplugged cannot be woken.");
    }
}
