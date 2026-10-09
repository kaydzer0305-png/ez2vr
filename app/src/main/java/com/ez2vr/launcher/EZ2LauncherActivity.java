package com.ez2vr.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.ez2vr.launcher.diag.CrashProbe;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-screen launcher: import content -> presence check -> stage -> engine.
 *
 * <p>Replaces SourceVR's LauncherActivity + ImportActivity + VrAssetInstallActivity
 * chain with one EZ2-only flow. No Steam login (cf. SteamDownloadService):
 * the user points the app at their own PC Steam install once, and the
 * {@link EZ2ContentImporter} copies HL2 + Entropy: Zero 2 into the shared
 * depot over SAF — no adb, no desktop cable.
 *
 * <p>All filesystem work runs on a single background thread; progress is
 * throttled before posting so a 50k-file HL2 depot cannot flood the main
 * looper. The UI is built in code (no res/) to keep the APK minimal on Quest.
 */
public class EZ2LauncherActivity extends Activity {
    private static final String TAG = "EZ2VR";
    private static final int REQUEST_CONTENT_DIR = 1001;

    /** Persisted crash-report count from before the last engine launch. */
    private static final String KEY_CRASH_BASELINE = "ez2vr.crash_baseline";

    /**
     * Launch the engine as soon as the UI is up: {@code --ez auto_launch true}.
     *
     * <p>Exists because of a real trap. The engine activities are
     * {@code exported="false"}, so {@code adb shell am start} cannot launch them
     * and the only way in is pressing a button on the headset — which means every
     * on-device experiment needs a human wearing it. This extra rides on the
     * launcher, which <em>is</em> exported, so the whole loop becomes scriptable:
     *
     * <pre>
     *   adb shell am start -n com.ez2vr.launcher/.EZ2LauncherActivity --ez auto_launch true
     * </pre>
     *
     * <p>Deliberately on the launcher and not by exporting the engine activities:
     * loosening {@code exported} to win a debugging convenience would widen the
     * attack surface of the one component that loads native code.
     */
    private static final String EXTRA_AUTO_LAUNCH = "auto_launch";

    /**
     * Boot path for this launch, which also persists: {@code --es boot_mode flat}.
     * Lets a mode be selected over adb before the toggle has ever been pressed.
     */
    private static final String EXTRA_BOOT_MODE = "boot_mode";

    /** Backstop for the engine-never-covers-us case, in ms. */
    private static final long ENGINE_COVER_TIMEOUT_MS = 10000L;

    /**
     * How long the launcher waits, after the engine covers it, before finishing.
     *
     * <p>Long enough for the shell's immersive transition to finish handing the
     * runtime focus to the engine: measured on device, focus lands ~0.7 s after
     * the engine activity resumes, and the shell only keeps it if the launcher
     * task still exists. See {@link #onStop()}.
     */
    private static final long ENGINE_FINISH_DELAY_MS = 4000L;

    /** True once this launcher has started the engine; gates the onStop finish. */
    private boolean engineStarted;

    /** Guards the delayed finish so onStop cannot schedule it twice. */
    private boolean finishScheduled;

    /** Survives the finish() in onStop, so the next launch can spot a crash. */
    private SharedPreferences prefs;

    /** Progress posts are rate-limited to this interval; faster updates just
     *  make the bar shimmer and risk starving the main looper. */
    private static final long PROGRESS_THROTTLE_MS = 200;

    private TextView status;
    private ProgressBar progress;
    private Button pickButton;
    private Button updateButton;
    private Button launchButton;
    private Button cancelButton;
    private Button modeButton;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private volatile boolean importRunning;
    private volatile boolean importCancelled;
    private volatile long lastProgressPost;
    private String importSourceLabel = "";
    /** Last outcome worth keeping on screen (import result, error). Prepended
     * to the derived state text by {@link #refreshStatus()}. */
    private String note = "";

    private final EZ2ContentImporter.ProgressListener importProgress =
            new EZ2ContentImporter.ProgressListener() {
                @Override
                public void onScanning() {
                    post(() -> {
                        if (!importRunning) {
                            return;
                        }
                        progress.setIndeterminate(true);
                        status.setText("Scanning " + importSourceLabel + " for content...");
                    });
                }

                @Override
                public void onCopying(int filesDone, int filesTotal, long bytesDone,
                                      long bytesTotal, String currentName) {
                    long now = System.currentTimeMillis();
                    if (now - lastProgressPost < PROGRESS_THROTTLE_MS
                            && filesDone < filesTotal) {
                        return;
                    }
                    lastProgressPost = now;
                    int pct = bytesTotal > 0
                            ? (int) Math.min(100L, (bytesDone * 100L) / bytesTotal)
                            : 0;
                    String line = "Importing " + pct + "%  ("
                            + EZ2ContentImporter.formatBytes(bytesDone) + " of "
                            + EZ2ContentImporter.formatBytes(bytesTotal) + ")"
                            + (currentName.isEmpty() ? "" : "\n" + currentName);
                    post(() -> {
                        if (!importRunning) {
                            return;
                        }
                        progress.setIndeterminate(false);
                        progress.setProgress(pct);
                        status.setText(line);
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EZ2Diagnostics.startSession(this, "launcher");
        prefs = getSharedPreferences("ez2vr", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("EZ2 VR — " + EZ2Profile.DISPLAY_NAME
                + " (Steam App " + EZ2Profile.STEAM_APP_ID + ")");
        title.setTextSize(20);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Copy content from your PC Steam install. Pick the folder that "
                + "CONTAINS \"Half-Life 2\" and \"" + EZ2Profile.GAME_DIR_WINDOWS
                + "\" (usually Steam/steamapps/common), or, for a self-contained EZ2 "
                + "install, the \"EntropyZero2\" folder itself — it holds them inside. "
                + "You can re-import later to pick up a Steam update; only changed "
                + "files are copied.");
        root.addView(hint);

        status = new TextView(this);
        root.addView(status);

        progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        pickButton = new Button(this);
        pickButton.setText("Import from PC Steam folder");
        pickButton.setOnClickListener(v -> openPicker());
        root.addView(pickButton);

        updateButton = new Button(this);
        updateButton.setText("Re-import from last folder");
        updateButton.setOnClickListener(v -> reimportFromMemory());
        root.addView(updateButton);

        cancelButton = new Button(this);
        cancelButton.setText("Cancel import");
        cancelButton.setOnClickListener(v -> {
            importCancelled = true;
            status.setText("Cancelling after the current file...");
        });
        cancelButton.setVisibility(View.GONE);
        root.addView(cancelButton);

        launchButton = new Button(this);
        launchButton.setText("Stage + Launch");
        launchButton.setOnClickListener(v -> stageAndLaunch());
        root.addView(launchButton);

        // Boot-path toggle. The engine activity is exported="false" so it cannot
        // be started over adb (run-as is refused too) — the button has to be
        // pressed on the headset — which means a manifest-only BOOT_MODE switch
        // would cost a rebuild and reinstall per experiment. See EZ2BootMode.
        modeButton = new Button(this);
        modeButton.setOnClickListener(v -> {
            EZ2BootMode next =
                    EZ2BootMode.resolve(this) == EZ2BootMode.VR ? EZ2BootMode.FLAT : EZ2BootMode.VR;
            EZ2BootMode.setUserChoice(this, next);
            refreshStatus();
        });
        root.addView(modeButton);

        Button settings = new Button(this);
        settings.setText("VR Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, EZ2VrSettingsActivity.class)));
        root.addView(settings);

        Button diagnostics = new Button(this);
        diagnostics.setText("Export diagnostics");
        diagnostics.setOnClickListener(v -> android.widget.Toast.makeText(this,
                EZ2Diagnostics.exportStatusMessage(this),
                android.widget.Toast.LENGTH_LONG).show());
        root.addView(diagnostics);

        setContentView(root);
        applyLaunchExtras();
    }

    /**
     * Honours the adb-driven {@code auto_launch} / {@code boot_mode} extras.
     *
     * <p>Posted rather than run inline: {@link #stageAndLaunch} stages assets and
     * reads the depot, and doing that before the first layout pass would race the
     * button enables that {@link #refreshStatus} performs.
     */
    private void applyLaunchExtras() {
        Intent intent = getIntent();
        if (intent == null) {
            return;
        }
        for (String k : intent.getExtras().keySet()) {
            Log.i(TAG, "launcher extra [" + k + "] = " + intent.getExtras().get(k));
        }
        String requested = intent.getStringExtra(EXTRA_BOOT_MODE);
        if (requested != null && !requested.trim().isEmpty()) {
            EZ2BootMode.setUserChoice(this, EZ2BootMode.parse(requested));
            Log.i(TAG, "boot mode set from intent: " + requested);
        }
        if (!intent.getBooleanExtra(EXTRA_AUTO_LAUNCH, false)) {
            return;
        }
        Log.i(TAG, "auto_launch requested; staging and starting the engine");
        getWindow().getDecorView().postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                stageAndLaunch();
            }
        }, 1500L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The engine run that ended before this launch may have crashed. Now
        // checked here rather than in onActivityResult, because the launcher
        // finishes itself once the engine covers it (see onStop) and so never
        // gets a result back.
        if (engineCrashedSinceLastLaunch()) {
            EZ2RecoveryActivity.launch(this, EZ2RecoveryActivity.REASON_CRASH,
                    "The engine stopped unexpectedly and wrote "
                            + crashesSinceLastLaunch()
                            + " crash report(s). Export diagnostics to attach "
                            + "them to a bug report.");
            return;
        }
        refreshStatus();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Once the engine activity covers us, get out of the way. The shell has
        // one immersive slot; a 2D panel left alive behind the engine makes the
        // two contend, and the shell cycles focus instead of handing the OpenXR
        // session VISIBLE/FOCUSED. Verified on device: with this finish removed
        // the session sat at state=READY(2), rendering=0, indefinitely, while
        // logcat showed "Switching to immersive app" every ~20-70s.
        //
        // Finishing in onStop rather than immediately after startActivity is
        // deliberate: an already-finished caller makes the engine start a
        // background start, which fails the same way.
        //
        // The finish is DELAYED, and that is the part that actually unblocks the
        // session. Logcat on device (15:13:07.1-07.3, run 6) shows the shell
        // grants the engine's runtime client focus ~70 ms after xrBeginSession
        // and then takes it straight back:
        //
        //   ClientMgrFocus: FocusedClient changed from  to com.ez2vr.launcher:engine:18013
        //   ClientMgrFocus: FocusedClient changed from com.ez2vr.launcher:engine:18013 to com.oculus.vrshell:3249
        //   VolumetricContentMonitor: Focused window changed to ...FocusPlaceholderActivity
        //
        // and the ordering is the cause: this launcher's task is destroyed in the
        // same 200 ms window (wm_finish_activity ... EZ2LauncherActivity,
        // app-request), the shell has no focused window left, so it puts its own
        // FocusPlaceholderActivity up, and the engine is no longer the focused
        // client. An OpenXR session only leaves XR_SESSION_STATE_READY once the
        // runtime has focus, so the session sat at READY forever and the launcher
        // timed out into the flat path. Finishing late lets the immersive
        // transition complete and the engine keep focus; the panel is already
        // backgrounded by then, so nothing is left contending for the slot.
        if (engineStarted && !finishScheduled) {
            finishScheduled = true;
            getWindow().getDecorView().postDelayed(
                    () -> {
                        if (!isFinishing()) {
                            finish();
                        }
                    }, ENGINE_FINISH_DELAY_MS);
        }
    }

    @Override
    protected void onDestroy() {
        // Not shutdownNow: an in-flight copy is left to finish writing its
        // current file, then the thread exits. Partial files are .part, so the
        // depot stays consistent and a later import resumes.
        io.shutdown();
        super.onDestroy();
    }

    // ---- content import ---------------------------------------------------

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CONTENT_DIR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // No engine branch here any more. The engine is started with
        // startActivity() and this launcher finishes itself in onStop(), so there
        // is no return trip; the crash check moved to onResume via the persisted
        // baseline. See engineCrashedSinceLastLaunch().
        if (requestCode != REQUEST_CONTENT_DIR || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri tree = data.getData();
        if (tree == null) {
            return;
        }
        // Without a persistable grant the import still works this session, but
        // "re-import" will not, so this is worth reporting rather than ignoring.
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.w(TAG, "no persistable grant for " + tree, e);
            setNote("This provider did not grant lasting access, so re-import "
                    + "will ask for the folder again.");
        }
        EZ2ContentPaths.rememberContentRoot(this, tree.toString());
        beginImport(DocumentFileNode.fromTreeUri(this, tree));
    }

    private void reimportFromMemory() {
        String remembered = EZ2ContentPaths.rememberedContentRoot(this);
        if (remembered == null) {
            setNote("No folder chosen yet — use \"Import from PC Steam folder\".");
            return;
        }
        beginImport(DocumentFileNode.fromTreeUri(this, Uri.parse(remembered)));
    }

    private void beginImport(EZ2SourceNode source) {
        if (importRunning) {
            return;
        }
        if (source == null) {
            setNote("That folder is not readable any more (access was revoked or "
                    + "the card moved). Pick it again.");
            return;
        }
        importRunning = true;
        importCancelled = false;
        lastProgressPost = 0;
        importSourceLabel = source.name().isEmpty() ? "selected folder" : source.name();
        setImportUiRunning(true);
        status.setText("Scanning " + importSourceLabel + "...");
        io.execute(() -> runImport(source));
    }

    private void runImport(EZ2SourceNode source) {
        try {
            EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(source);
            if (!plan.isComplete()) {
                post(() -> {
                    setImportUiRunning(false);
                    setNote("Nothing imported.\n\n" + plan.describeProblems());
                });
                return;
            }
            EZ2ContentImporter.CopyStats stats = EZ2ContentImporter.copy(
                    plan,
                    EZ2ContentPaths.sharedRoot(this),
                    importProgress,
                    () -> importCancelled);
            post(() -> onImportFinished(stats));
        } catch (Exception e) {
            Log.e(TAG, "import failed", e);
            post(() -> {
                setImportUiRunning(false);
                setNote("Import failed: " + describe(e)
                        + "\n\nAlready-copied files are kept — fix the problem and "
                        + "import again to continue where it stopped.");
            });
        }
    }

    private void onImportFinished(EZ2ContentImporter.CopyStats stats) {
        setImportUiRunning(false);
        String secs = String.format(Locale.US, "%.1fs", stats.elapsedMs / 1000.0);
        if (stats.cancelled) {
            setNote("Import cancelled. Copied " + stats.filesCopied + " files ("
                    + EZ2ContentImporter.formatBytes(stats.bytesCopied) + ") in " + secs
                    + ". Import again to resume.");
        } else {
            setNote("Imported " + stats.filesCopied + " new and skipped "
                    + stats.filesSkipped + " unchanged of " + stats.filesTotal
                    + " files, " + EZ2ContentImporter.formatBytes(stats.bytesCopied)
                    + " in " + secs + ".");
        }
    }

    private void setImportUiRunning(boolean running) {
        importRunning = running;
        progress.setVisibility(running ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(running);
        cancelButton.setVisibility(running ? View.VISIBLE : View.GONE);
        launchButton.setEnabled(!running);
        pickButton.setEnabled(!running);
        updateButton.setEnabled(!running);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        if (e instanceof SecurityException) {
            return "access to the folder was denied. Pick it again"
                    + (message == null ? "." : ": " + message);
        }
        return message == null || message.isEmpty()
                ? e.getClass().getSimpleName() : message;
    }

    // ---- status -----------------------------------------------------------

    private void setNote(String text) {
        note = text;
        refreshStatus();
    }

    private void refreshStatus() {
        if (importRunning) {
            return; // live progress owns the status line
        }
        EZ2ContentPresence presence =
                EZ2ContentPresence.check(EZ2ContentPaths.sharedRoot(this));
        boolean staged = EZ2AssetStage.isCurrent(this);
        updateButton.setVisibility(
                EZ2ContentPaths.rememberedContentRoot(this) == null ? View.GONE : View.VISIBLE);

        StringBuilder sb = new StringBuilder();
        if (!note.isEmpty()) {
            sb.append(note).append("\n\n");
        }
        if (presence.readyToLaunch && staged) {
            sb.append("Ready: content present + VR assets staged.");
        } else if (!presence.readyToLaunch) {
            sb.append("Content missing: ").append(presence.problem);
        } else {
            sb.append("Content present, VR assets need staging (press Stage + Launch).");
        }
        EZ2BootMode mode = EZ2BootMode.resolve(this);
        sb.append("\n\nBoot path: ").append(mode == EZ2BootMode.VR
                ? "VR (OpenXR, immersive)" : "Flat (SDL, 2D panel — no OpenXR)");
        if (mode == EZ2BootMode.FLAT) {
            sb.append("\nFlat renders, so the EZ2 game code is testable while the "
                    + "OpenXR session stall is open.");
        }
        sb.append("\n\nDepot:\n").append(EZ2ContentPaths.sharedRoot(this).getAbsolutePath());
        status.setText(sb.toString());
        launchButton.setText(mode == EZ2BootMode.VR
                ? "Stage + Launch (VR)" : "Stage + Launch (Flat)");
        modeButton.setText("Switch to " + (mode == EZ2BootMode.VR ? "Flat" : "VR") + " boot");
        launchButton.setEnabled(true);
    }

    private void stageAndLaunch() {
        if (importRunning) {
            return;
        }
        EZ2ContentPresence presence = EZ2ContentPresence.check(EZ2ContentPaths.sharedRoot(this));
        if (!presence.readyToLaunch) {
            setNote("Cannot launch yet — " + presence.problem);
            return;
        }
        try {
            EZ2AssetStage.stage(this);
        } catch (Exception e) {
            Log.e(TAG, "asset staging failed", e);
            setNote("Asset staging failed: " + describe(e));
            return;
        }
        note = "";
        // Record the crash-report count as the baseline for THIS engine run. The
        // launcher finishes itself in onStop() (see below), so there is no return
        // trip to compare against; the next launch compares against this.
        rememberCrashBaseline(CrashProbe.countReports(this));
        engineStarted = true;

        // startActivity, NOT startActivityForResult. A forResult start keeps this
        // panel alive behind the engine, and two live activities (a 2D panel and
        // the VR engine) contend for the shell's single immersive slot. The shell
        // then cycles focus every ~20-70s and the engine's OpenXR session never
        // leaves READY. The proven EZ1 launcher starts the engine this way.
        startActivity(engineIntent());

        // Do NOT finish() here. The system processes the start asynchronously and
        // an already-finished caller turns it into a background start, which is
        // the other half of the same stuck-at-READY failure. onStop() finishes us
        // once the engine actually covers us; this timeout is the backstop so a
        // start that never takes cannot strand the user on a dead panel.
        getWindow().getDecorView().postDelayed(() -> {
            if (!isFinishing()) {
                Log.w(TAG, "engine did not cover us; finishing anyway");
                finish();
            }
        }, ENGINE_COVER_TIMEOUT_MS);
    }

    /**
     * Did the engine crash since the last time we started it?
     *
     * <p>The engine host lives in its own {@code :engine} process, so a native
     * fault kills that process outright. What distinguishes a crash from a
     * clean quit is a crash report on disk: {@code CrashProbe} in the engine
     * process catches Java faults and the engine's own
     * {@code InitCrashHandler} covers native ones, so sampling the report count
     * catches both without guessing from timings.
     *
     * <p>This used to be checked in {@code onActivityResult}, comparing against
     * a count taken moments earlier. There is no return trip any more -- the
     * launcher finishes itself in {@link #onStop()} so it stops contending with
     * the engine for the shell's immersive slot, which is what left the OpenXR
     * session stuck at READY. So the baseline is persisted before each launch and
     * the comparison happens on the NEXT launcher open instead. The cost is real:
     * a crash is reported one launch late rather than immediately.
     */
    private boolean engineCrashedSinceLastLaunch() {
        int now = CrashProbe.countReports(this);
        int baseline = prefs.getInt(KEY_CRASH_BASELINE, now);
        return now > baseline;
    }

    /** Crash reports written by the run that just ended, for the message. */
    private int crashesSinceLastLaunch() {
        int now = CrashProbe.countReports(this);
        return now - prefs.getInt(KEY_CRASH_BASELINE, now);
    }

    private void rememberCrashBaseline(int count) {
        prefs.edit().putInt(KEY_CRASH_BASELINE, count).apply();
    }

    /**
     * Explicit intent for the engine host, for the currently selected boot path.
     *
     * <p>For the VR path the categories have to be on the Intent, not only in the
     * manifest filter. This is an explicit start, so the system does not resolve
     * the filter and the categories never reach the launch Intent — and it is the
     * launch Intent that Meta's system reads to decide whether to hand focus to
     * an immersive HMD session. A manifest-only category leaves the engine
     * running in a flat 2D window on the compositor.
     *
     * <p>The flat path must NOT carry them, for the same reason in reverse: those
     * categories make the shell run a full immersive transition, which is the bug
     * documented on the 2D launcher's filter. A flat boot that asks for immersive
     * focus gets the Meta loading screen and a bounce home, not a 2D window.
     */
    private Intent engineIntent() {
        EZ2BootMode mode = EZ2BootMode.resolve(this);
        if (mode == EZ2BootMode.FLAT) {
            Log.i(TAG, "launching flat engine (SDL, no OpenXR)");
            return new Intent(this, EZ2FlatEngineActivity.class);
        }
        Log.i(TAG, "launching immersive engine (OpenXR)");
        Intent intent = new Intent(this, EZ2EngineActivity.class);
        intent.setAction("com.ez2vr.launcher.action.START_ENGINE");
        intent.addCategory("org.khronos.openxr.intent.category.IMMERSIVE_HMD");
        intent.addCategory("com.oculus.intent.category.VR");
        // Same flags the proven EZ1 launcher uses. CLEAR_TOP/SINGLE_TOP keep the
        // engine from stacking a second instance if the button is pressed twice.
        // NEW_TASK: the engine has its own taskAffinity so the immersive
        // activity lives in its own task; the launcher finishing itself then
        // cannot destroy the task the runtime is handing focus to.
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        // META-RUNTIME-KEY: the runtime associates the OpenXR client with an
        // immersive window via the launchId EXTRA on the engine activity's
        // intent. The launcher's own intent got it from VrShell; forward the
        // whole extras bundle so the engine's session gets a real clientLaunchId
        // instead of the empty one that keeps the session in READY forever.
        if (getIntent() != null && getIntent().getExtras() != null) {
            intent.putExtras(getIntent());
        }
        return intent;
    }

    /** Runs on the worker thread; drops the callback if the UI is gone. */
    private void post(Runnable r) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                r.run();
            }
        });
    }
}
