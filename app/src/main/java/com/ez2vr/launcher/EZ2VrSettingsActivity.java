package com.ez2vr.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * Stub VR Settings menu — the owning authority for the ez2vr_* keys cataloged
 * in vr_defaults.cfg. Values persist here (playing config.cfg's role) and are
 * appended to the engine launch args by EZ2EngineActivity, so they always beat
 * overlay boot defaults.
 *
 * <p>v0.1 covers the 8 highest-leverage settings. Volumes, HUD distance,
 * gunpoint tuning and teleport range join once the VR layer registers them.
 */
public class EZ2VrSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("EZ2 VR Settings");
        title.setTextSize(20);
        root.addView(title);

        Spinner locomotion = addSpinner(root, "Locomotion",
                new String[]{"Smooth", "Teleport", "Teleport+Dash"},
                EZ2VrSettings.locomotion(this));
        Spinner turn = addSpinner(root, "Turning",
                new String[]{"Snap", "Smooth"},
                EZ2VrSettings.turn(this));
        CheckBox seated = addCheck(root, "Seated mode", EZ2VrSettings.seated(this));
        CheckBox visor = addCheck(root, "Bad Cop visor overlay", EZ2VrSettings.visor(this));
        Spinner flashlight = addSpinner(root, "Flashlight mount",
                new String[]{"Hand", "Head"},
                EZ2VrSettings.flashlightMount(this));
        Spinner vehicle = addSpinner(root, "APC aim",
                new String[]{"Head-aim", "Hand-aim"},
                EZ2VrSettings.vehicleAim(this));
        Spinner perf = addSpinner(root, "Performance",
                new String[]{"Quality", "Balanced", "Performance"},
                EZ2VrSettings.perfMode(this));
        Spinner ffr = addSpinner(root, "Foveated rendering (FFR)",
                new String[]{"Off", "Low", "Medium", "High"},
                EZ2VrSettings.ffr(this));

        TextView hint = new TextView(this);
        hint.setText("Saved locally; applied on next launch after the overlay cfgs.");
        root.addView(hint);

        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(v -> {
            EZ2VrSettings.save(this,
                    locomotion.getSelectedItemPosition(),
                    turn.getSelectedItemPosition(),
                    seated.isChecked(),
                    visor.isChecked(),
                    flashlight.getSelectedItemPosition(),
                    vehicle.getSelectedItemPosition(),
                    perf.getSelectedItemPosition(),
                    ffr.getSelectedItemPosition());
            finish();
        });
        root.addView(save);

        setContentView(root);
    }

    private Spinner addSpinner(LinearLayout root, String label, String[] items, int selected) {
        TextView tv = new TextView(this);
        tv.setText(label);
        root.addView(tv);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selected, items.length - 1)));
        root.addView(spinner);
        return spinner;
    }

    private CheckBox addCheck(LinearLayout root, String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setChecked(checked);
        root.addView(box);
        return box;
    }
}
