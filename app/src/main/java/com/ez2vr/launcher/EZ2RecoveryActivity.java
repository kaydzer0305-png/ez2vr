package com.ez2vr.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Fail-loud error screen shown when the engine cannot start, or died while
 * running. Replaces a silent black screen with the reason, so on-device
 * debugging does not need logcat — which is the whole point on a Quest, where
 * the user has no way to read a crash from the headset.
 *
 * <p>Before this existed every failure path in {@link EZ2EngineActivity} was
 * {@code Log.e(...)} followed by {@code finish()}. On a headset that is an
 * invisible failure: the app appears to do nothing and the user has no way to
 * tell a missing content depot from a missing engine library.
 *
 * <p>Runs in the {@code :recovery} process (see AndroidManifest.xml) so it can
 * still come up when the {@code :engine} process is wedged rather than merely
 * crashed.
 *
 * <p>Ported from the EZ1 Quest port's {@code RecoveryActivity.java}.
 */
public class EZ2RecoveryActivity extends Activity {
    public static final String EXTRA_REASON = "com.ez2vr.launcher.EXTRA_REASON";
    public static final String EXTRA_MESSAGE = "com.ez2vr.launcher.EXTRA_MESSAGE";

    /** Content depot incomplete — offer the import flow. */
    public static final String REASON_CONTENT = "content";
    /** Overlay/VR assets never staged or stale. */
    public static final String REASON_STAGING = "staging";
    /** Engine libs or the .load graph missing / wrong ABI. */
    public static final String REASON_NATIVE = "native";
    /** Engine process died while running. */
    public static final String REASON_CRASH = "crash";

    public static Intent intentFor(Context context, String reason, String message) {
        return new Intent(context, EZ2RecoveryActivity.class)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_MESSAGE, message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    /** Best-effort show; never throws into a failure path. */
    public static void launch(Context context, String reason, String message) {
        try {
            context.startActivity(intentFor(context, reason, message));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EZ2Diagnostics.startSession(this, "recovery");

        Intent intent = getIntent();
        String reason = intent == null ? null : intent.getStringExtra(EXTRA_REASON);
        String message = intent == null ? null : intent.getStringExtra(EXTRA_MESSAGE);
        if (reason == null) {
            reason = "startup";
        }
        if (message == null) {
            message = "The engine could not start.";
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.BLACK);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("EZ2 VR — cannot start (" + reason + ")");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);

        TextView body = new TextView(this);
        body.setText(message);
        body.setTextColor(Color.LTGRAY);
        body.setTextSize(15);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, pad / 2, 0, 0);

        layout.addView(title);
        layout.addView(body);

        Button export = new Button(this);
        export.setText("Export diagnostics");
        export.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(EZ2RecoveryActivity.this,
                        EZ2Diagnostics.exportStatusMessage(EZ2RecoveryActivity.this),
                        Toast.LENGTH_LONG).show();
            }
        });
        export.setPadding(0, pad, 0, 0);
        layout.addView(export);

        // Both paths return to the launcher; only the label differs, so a
        // content problem tells the user what to do next instead of dead-ending.
        Button back = new Button(this);
        back.setText(REASON_CONTENT.equals(reason)
                ? "Back to launcher — import content"
                : "Back to launcher");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        back.setPadding(0, pad / 2, 0, 0);
        layout.addView(back);

        setContentView(layout);
    }

    @Override
    protected void onDestroy() {
        EZ2Diagnostics.endSession(this, true);
        super.onDestroy();
    }
}
