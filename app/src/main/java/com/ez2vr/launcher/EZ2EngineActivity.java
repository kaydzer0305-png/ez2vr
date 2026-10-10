package com.ez2vr.launcher;

import android.app.NativeActivity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.util.Map;

/**
 * Immersive engine host.
 *
 * <p>Behavioral mirror of SourceVR's MainActivity (MainActivity.java:30-65,
 * 167-213): route to installer when not staged, then export the engine
 * environment and enter native. Env names are intentionally compatible so a
 * future Mapbase-based liblauncher.so built for EZ2 can reuse them:
 *
 * <pre>
 *   EZ2_GAME, EZ2_SHARED_CONTENT_PATH, EZ2_WRITE_GAME_PATH,
 *   EZ2_VR_CONTENT_PATH, EZ2_EXTRAS_PATH, EZ2_RUNTIME_GRAPH
 * </pre>
 *
 * <p>Environment and argv construction live in {@link EZ2EngineEnv}, shared with
 * {@link EZ2FlatEngineActivity}; the native-graph gate lives in
 * {@link EZ2NativeGate}, also shared. Both are shared deliberately: a difference
 * between the two boot paths is invisible here (nothing renders) and fatal on the
 * flat path, so it would otherwise be found only once the flat path worked.
 *
 * <p><b>Former blocker, fixed 2026-10-09.</b> The OpenXR session was created and
 * begun, and then the runtime never left {@code XR_SESSION_STATE_READY}. That
 * turned out to be self-inflicted: the native frame loop waited for
 * VISIBLE/FOCUSED before its first {@code xrWaitFrame}, but the
 * {@code xrBeginSession} contract says the app "should then start its frame
 * loop", and {@code xrWaitFrame} is legal in every running state (READY
 * included). A runtime that advances the session in response to frame cadence
 * never had a reason to move a session that never pumped a frame. The loop now
 * runs from begin and {@code shouldRender} gates the draw, not the loop.
 * {@link #mXrPollRunnable} still falls back to {@link EZ2FlatEngineActivity}
 * when nothing presents within the timeout.
 */
public class EZ2EngineActivity extends NativeActivity {
    private static final String TAG = "EZ2VR";

    /** How often the XR loop's state is sampled. */
    private static final long XR_POLL_MS = 500;

    /**
     * How long the XR loop gets to present before we stop waiting.
     *
     * <p>Not a bootstrap timeout — a bootstrap failure reports
     * {@code EZ_XR_FAILED} immediately via {@link #nativeXrStatus()}. This is
     * the case where bootstrap <em>succeeds</em> and the loop then never reaches
     * a presenting state, which is exactly the stuck-at-READY failure: the
     * native side has no way to distinguish it from a slow boot, so the wait has
     * to be bounded from out here.
     */
    private static final long XR_TIMEOUT_MS = 20000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable mXrPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (mFallbackStarted || isFinishing()) {
                return;
            }
            mXrPolledMs += XR_POLL_MS;

            int status;
            try {
                status = com.ezquest.engine.EngineActivity.nativeXrStatus();
            } catch (UnsatisfiedLinkError e) {
                // An engine build without these symbols cannot present at all, so
                // waiting out XR_TIMEOUT_MS only delays the same outcome.
                Log.e(TAG, "XR status symbols missing from liblauncher.so", e);
                status = com.ezquest.engine.EngineActivity.EZ_XR_FAILED;
            }

            if (status == com.ezquest.engine.EngineActivity.EZ_XR_RUNNING) {
                Log.i(TAG, "XR loop is presenting");
                return; // stay resident; native owns presentation from here
            }
            if (status == com.ezquest.engine.EngineActivity.EZ_XR_FAILED
                    || mXrPolledMs >= XR_TIMEOUT_MS) {
                String reason;
                try {
                    reason = com.ezquest.engine.EngineActivity.nativeXrFailReason();
                } catch (UnsatisfiedLinkError e) {
                    reason = null;
                }
                Log.e(TAG, "XR bootstrap failed (status=" + status + " polledMs="
                        + mXrPolledMs + " reason=" + reason + "); falling back to flat");
                EZ2Diagnostics.event(EZ2EngineActivity.this, "xr_fallback",
                        "status", String.valueOf(status),
                        "polledMs", String.valueOf(mXrPolledMs),
                        "reason", String.valueOf(reason));
                fallBackToFlat(status == com.ezquest.engine.EngineActivity.EZ_XR_FAILED
                        ? reason
                        : "XR loop did not present within " + XR_TIMEOUT_MS
                          + "ms (session never reached VISIBLE/FOCUSED; see the "
                          + "EZQuest-VR \"waiting for session begin\" / "
                          + "\"frame pump alive\" lines for the stuck state)");
                return;
            }
            handler.postDelayed(this, XR_POLL_MS);
        }
    };

    private long mXrPolledMs;
    private boolean mFallbackStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EZ2Diagnostics.startSession(this, "engine");
        // The JNI shim {@code com.ezquest.engine.EngineActivity} resolves its
        // static natives against the libraries that were loaded through THIS
        // classloader. NativeActivity's android.app.lib_name is a native
        // entry hook, not a System.loadLibrary, so ART cannot find the
        // nativeXrStatus symbol unless we load liblauncher.so through the
        // Java loader ourselves first.
        try {
            System.loadLibrary("launcher");
        } catch (UnsatisfiedLinkError e) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    "liblauncher.so failed to load — likely the wrong ABI. The "
                            + "engine libraries must be built for arm64-v8a.");
            return;
        }
        if (!prepareEngine()) {
            return;
        }
        try {
            super.onCreate(savedInstanceState);
        } catch (UnsatisfiedLinkError e) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    EZ2NativeGate.ENGINE_LIB_SO + " failed to load — likely the wrong "
                            + "ABI. The engine libraries must be built for arm64-v8a.");
            return;
        }
        // Only now is liblauncher.so resident, so this is the earliest point the
        // XR status symbols can resolve.
        handler.postDelayed(mXrPollRunnable, XR_POLL_MS);
    }

    /**
     * Gates, then exports the engine environment with the stereo path enabled.
     *
     * @return true when {@code super.onCreate} should proceed
     */
    private boolean prepareEngine() {
        EZ2ContentPresence presence =
                EZ2ContentPresence.check(EZ2ContentPaths.sharedRoot(this));
        if (!presence.readyToLaunch) {
            // This activity is the app's launch entry now (VrShell classifies a
            // package as VR from its launch component), so it is also the only
            // thing standing between the user and the game. Content missing means
            // the 2D import UI has to be put in front of them — a recovery screen
            // that only explains the problem would strand a fresh install.
            openImportUi(presence.problem);
            fail(EZ2RecoveryActivity.REASON_CONTENT, presence.problem);
            return false;
        }
        if (!EZ2AssetStage.isCurrent(this)) {
            openImportUi("Staged assets are missing or stale.");
            fail(EZ2RecoveryActivity.REASON_STAGING,
                    "VR overlay and content layers are missing or stale. Open the "
                            + "launcher and press Stage + Launch.");
            return false;
        }
        if (!EZ2NativeGate.hasNativeGraph(this)) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    "No engine runtime graph is bundled. Build the engine and run "
                            + ":app:stageEngineLibs before assembling the APK.");
            return false;
        }
        try {
            EZ2AssetStage.stageNativeGraph(this);
        } catch (IOException e) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    "Could not stage the engine load graph: " + describe(e));
            return false;
        }
        String nativeProblem = EZ2NativeGate.verify(this);
        if (nativeProblem != null) {
            fail(EZ2RecoveryActivity.REASON_NATIVE, nativeProblem);
            return false;
        }
        try {
            setupEngineEnvironment();
        } catch (Exception e) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    "Engine environment setup failed: " + describe(e));
            return false;
        }
        return true;
    }

    /**
     * Starts the flat engine in this activity's place.
     *
     * <p>This is the fallback the Android manifest comment claims the native loop
     * performs. It does not: {@code vr_main.cpp} only records the reason and calls
     * {@code ANativeActivity_finish}, so the hand-off has always had to happen
     * here in Java.
     */
    private void fallBackToFlat(String reason) {
        if (mFallbackStarted) {
            return;
        }
        mFallbackStarted = true;
        handler.removeCallbacks(mXrPollRunnable);
        Log.w(TAG, "flat fallback: " + reason);
        try {
            startActivity(new Intent(this, EZ2FlatEngineActivity.class));
        } catch (Exception e) {
            Log.e(TAG, "flat fallback failed", e);
            EZ2RecoveryActivity.launch(this, "xr",
                    "XR boot failed (" + reason + ") and the flat engine could not "
                            + "start.");
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(mXrPollRunnable);
        super.onDestroy();
    }

    /**
     * Puts the 2D import/settings UI in front of the user.
     *
     * <p>Best-effort: the reason is surfaced by {@link #fail} either way, so a
     * refusal here costs the extra context, never the diagnosis.
     */
    private void openImportUi(String why) {
        try {
            Intent intent = new Intent(this, EZ2LauncherActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "could not open the import UI (" + why + ")", e);
        }
    }

    /**
     * Shows the fail-loud reason, then leaves.
     *
     * <p>Never just {@code Log.e() + finish()}: a Quest user cannot read
     * logcat, so a silent finish is indistinguishable from a hung app. The
     * reason is also recorded so the launcher's crash accounting can tell a
     * pre-boot refusal apart from an engine that died mid-session.
     */
    private void fail(String reason, String message) {
        Log.e(TAG, "engine start refused (" + reason + "): " + message);
        EZ2Diagnostics.event(this, "engine_start_refused", "reason", reason,
                "message", message);
        EZ2RecoveryActivity.launch(this, reason, message);
        finish();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty()
                ? e.getClass().getSimpleName() : message;
    }

    /**
     * Exports the engine environment with the stereo path explicitly enabled.
     *
     * <p>No {@code setArgs} is handed over here — this is a {@code NativeActivity}
     * and nothing calls {@link com.valvesoftware.ValveActivity2#setArgs} — so
     * the engine's {@code java_args} buffer stays empty. The argv instead rides
     * along as {@link EZ2EngineEnv#ENV_GAME_ARGS}, which the bootstrap merges
     * when {@code java_args} is empty (native/ez2vr_argv_snippet.cpp): the same
     * {@code -game -mobile +exec +ez2vr_*} line the flat path passes to
     * {@code setArgs}. {@code SetLauncherArgs()} still synthesizes a
     * {@code -game} fallback underneath, but the env-merged absolute
     * {@code -game} comes first and the engine takes its FIRST {@code -game},
     * so the fallback never wins when the merge ran.
     */
    private void setupEngineEnvironment() {
        String userCfgNote;
        try {
            userCfgNote = EZ2VrSettings.writeUserCfg(this).getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "user cfg snapshot failed; command line still carries settings", e);
            userCfgNote = "(snapshot failed)";
        }

        EZ2EngineEnv.Snapshot snap = EZ2EngineEnv.resolve(this, userCfgNote);
        Map<String, String> env =
                EZ2EngineEnv.env(snap, /* vrEnabled= */ true, userCfgNote,
                        EZ2VrSettings.buildExtraArgs(this),
                        EZ2VrSettings.ffr(this), EZ2VrSettings.perfMode(this));
        EZ2EngineEnv.export(env);

        Log.i(TAG, "engine env: game=" + snap.modName
                + " mod=" + snap.modPath
                + " shared=" + snap.shared
                + " write=" + snap.writeDir
                + " vrLayers=" + (snap.vrLayers.isEmpty() ? 0 : snap.vrLayers.split(",").length)
                + " " + EZ2EngineEnv.ENV_VR_ENABLE + "=" + env.get(EZ2EngineEnv.ENV_VR_ENABLE)
                + " graph=" + snap.graph);
    }
}
