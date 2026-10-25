package org.lean.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;

/** Customize: OK on a row steps to its next value. The home screen redraws when you go back. */
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
    private ListView list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Look.ensure(this);
        list = Ui.listScreen(this, "Customize",
            "OK changes a setting. Press Back to see it on the home screen.");

        choice("Theme", "theme", Look.THEMES, () -> Look.theme, true);
        choice("Tile size", "tileSize", Look.TILE_SIZES, () -> Look.tileSize, false);
        choice("App names", "labels", Look.LABELS, () -> Look.labels, false);
        choice("Clock", "clock", Look.CLOCKS, () -> Look.clock, false);
        choice("Date", "date", Look.SHOWN, () -> Look.date, false);
        items.add(new Item("Title, top right",
            () -> Look.title.trim().isEmpty() ? "Hidden" : Look.title.replaceAll("\\s+", " "), this::editTitle));
        choice("Glow behind the selected tile", "glow", Look.ON_OFF, () -> Look.glow, false);
        choice("Zoom the selected tile", "zoom", Look.ON_OFF, () -> Look.zoom, false);
        choice("Colour wash on the selected tile", "wash", Look.ON_OFF, () -> Look.wash, false);
        choice("Buttons under the tiles", "buttons", Look.BUTTONS, () -> Look.buttons, false);
        items.add(new Item("Wake-on-LAN", () -> wakeState(), () -> {
            WakeService.setEnabled(this, !WakeService.enabled(this));
            list.postDelayed(this::refresh, 600);
            refresh();
        }));
        items.add(new Item("Wake-on-LAN: how to wake this box", () -> "Help", this::wakeHelp));
        items.add(new Item("Use Google TV home instead", () -> "Home button", () -> GoogleHome.switchTo(this)));
        items.add(new Item("Turn Google TV home off (saves memory)", () -> "How to",
            () -> Apps.googleOffHelp(this)));
        items.add(new Item("Reset the look to defaults", () -> "", () ->
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Reset the look?")
                .setMessage("Theme, sizes, clock, title and effects go back to how they started. "
                    + "Your apps and their order stay.")
                .setPositiveButton("Reset", (d, w) -> {
                    Look.reset(this);
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show()));

        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> items.get(pos).ok.run());
        if (state != null) list.setSelection(state.getInt("pos", 0));
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
        out.putInt("pos", list.getSelectedItemPosition());
    }

    private void refresh() {
        adapter.notifyDataSetChanged();
    }

    /** A row that cycles through fixed values. Theme changes redraw this screen too. */
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
        in.setText(Look.title.trim().isEmpty() ? Look.DEFAULT_TITLE : Look.title);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        in.setSelectAllOnFocus(true);
        int pad = Ui.dp(this, 24);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        box.addView(in);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Title, top right")
            .setView(box)
            .setPositiveButton("Save", (d, w) -> {
                Look.setTitle(this, in.getText().toString());
                refresh();
            })
            .setNeutralButton("Hide", (d, w) -> {
                Look.setTitle(this, "");
                refresh();
            })
            .setNegativeButton("Default", (d, w) -> {
                Look.setTitle(this, Look.DEFAULT_TITLE);
                refresh();
            })
            .show();
    }

    private String wakeState() {
        if (!WakeService.enabled(this)) return "Off";
        if (!WakeService.canTurnScreenOn(this)) return "On, needs one adb command (see Help)";
        java.util.List<Integer> ports;
        synchronized (WakeService.listening) {
            ports = new java.util.ArrayList<>(WakeService.listening);
        }
        if (ports.isEmpty()) return "On (starting)";
        StringBuilder sb = new StringBuilder("On, port");
        if (ports.size() > 1) sb.append('s');
        for (int i = 0; i < ports.size(); i++) sb.append(i == 0 ? " " : " and ").append(ports.get(i));
        return sb.toString();
    }

    private void wakeHelp() {
        String mac = WakeService.mac(this);
        String last = WakeService.lastWake == 0 ? "none yet"
            : DateUtils.getRelativeTimeSpanString(WakeService.lastWake).toString();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Wake-on-LAN")
            .setMessage("With Wake-on-LAN on, any Wake-on-LAN app or tool on the same network can "
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
                + "little more power in standby. A box that is unplugged cannot be woken.")
            .setPositiveButton("OK", null)
            .show();
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Ui.Row h = convert == null ? new Ui.Row(CustomizeActivity.this) : (Ui.Row) convert.getTag();
            Item it = items.get(i);
            h.icon.setVisibility(View.GONE);
            h.label.setText(it.label);
            h.value.setText(it.value.get());
            return h.view;
        }
    };
}
