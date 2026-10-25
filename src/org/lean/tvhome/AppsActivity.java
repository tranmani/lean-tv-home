package org.lean.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Two lists in one screen. "All apps": OK opens an app, long-press OK adds or removes it from home.
 * "Add / remove apps": OK adds or removes, plus the switch back to Google TV's home.
 */
public class AppsActivity extends Activity {
    private static final String EXTRA_EDIT = "edit";
    private static final int ACTION_SWITCH = 1, ACTION_GOOGLE_OFF = 2;

    private boolean edit;
    private ListView list;
    private final List<Object> rows = new ArrayList<>();
    private final Set<String> onHome = new HashSet<>();

    static Intent intent(Context c, boolean edit) {
        return new Intent(c, AppsActivity.class).putExtra(EXTRA_EDIT, edit);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Look.ensure(this);
        edit = getIntent().getBooleanExtra(EXTRA_EDIT, false);
        list = Ui.listScreen(this, edit ? "Add / remove apps" : "All apps", edit
            ? "OK adds an app to the home screen or takes it off. On the home screen, hold OK on a tile to move it."
            : "OK opens an app. Hold OK to add it to the home screen or take it off.");
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> click(pos));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Object row = rows.get(pos);
            if (!(row instanceof Apps.App)) return false;
            toggle((Apps.App) row);
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        GoogleHome.resume(this);
    }

    private void load() {
        rows.clear();
        if (edit) {
            rows.add(ACTION_SWITCH);
            if (Apps.enabled(this, Apps.GOOGLE_HOME)) rows.add(ACTION_GOOGLE_OFF);
        }
        rows.addAll(Apps.all(this));
        onHome.clear();
        onHome.addAll(Apps.home(this));
        adapter.notifyDataSetChanged();
        list.requestFocus();
    }

    private void click(int pos) {
        Object row = rows.get(pos);
        if (row instanceof Apps.App) {
            Apps.App a = (Apps.App) row;
            if (edit) toggle(a);
            else if (!Apps.launch(this, a.pkg)) {
                Apps.toast(this, a.label + " could not open", false);
            }
        } else if ((Integer) row == ACTION_SWITCH) {
            GoogleHome.switchTo(this);
        } else {
            Apps.googleOffHelp(this);
        }
    }

    private void toggle(Apps.App a) {
        boolean on = Apps.toggle(this, a.pkg);
        if (on) onHome.add(a.pkg);
        else onHome.remove(a.pkg);
        Apps.toast(this, a.label + (on ? " added to home" : " taken off home"), false);
        adapter.notifyDataSetChanged();
    }

    // ---- list ---------------------------------------------------------------------------------

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Ui.Row h = convert == null ? new Ui.Row(AppsActivity.this) : (Ui.Row) convert.getTag();
            Object row = rows.get(i);
            if (row instanceof Apps.App) {
                Apps.App a = (Apps.App) row;
                boolean on = onHome.contains(a.pkg);
                h.icon.setVisibility(View.VISIBLE);
                h.icon.setImageDrawable(Apps.icon(AppsActivity.this, a));
                h.label.setText(a.label);
                h.value.setText(on ? "On home  ✓" : (edit ? "Add  +" : ""));
            } else {
                h.icon.setVisibility(View.INVISIBLE);
                h.icon.setImageDrawable(null);
                boolean sw = (Integer) row == ACTION_SWITCH;
                h.label.setText(sw ? "Use Google TV home instead" : "Turn Google TV home off (saves memory)");
                h.value.setText(sw ? "Home button" : "How to");
            }
            return h.view;
        }
    };
}
