package com.ez2vr.launcher;

import android.app.Activity;
import android.content.Intent;
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
    private static final int REQUEST_ENGINE = 1002;

    /** Crash reports on disk just before the engine was launched, so a
     *  post-return increase means the engine process died. */
    private int crashReportsBeforeEngine;

    /** Progress posts are rate-limited to this interval; faster updates just
     * make the bar shimmer and risk starving the main looper. */
    private static final long PROGRESS_THROTTLE_MS = 200;

    private TextView status;
    private ProgressBar progress;
    private Button pickButton;
    private Button updateButton;
    private Button launchButton;
    private Button cancelButton;

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
                + "\" — usually Steam/steamapps/common. You can re-import later to "
                + "pick up a Steam update; only changed files are copied.");
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
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
        if (requestCode == REQUEST_ENGINE) {
            onEngineReturned();
            return;
        }
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
        sb.append("\n\nDepot:\n").append(EZ2ContentPaths.sharedRoot(this).getAbsolutePath());
        status.setText(sb.toString());
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
        crashReportsBeforeEngine = CrashProbe.countReports(this);
        startActivityForResult(engineIntent(), REQUEST_ENGINE);
    }

    /**
     * The engine host lives in its own {@code :engine} process, so a native
     * fault kills that process outright and the activity comes back with no
     * result code that distinguishes a crash from the user quitting normally.
     *
     * <p>What does distinguish them is a crash report on disk: {@code CrashProbe}
     * in the engine process catches Java faults, and the engine's own
     * {@code InitCrashHandler} covers native ones. Sampling the report count
     * across the launch therefore catches both without guessing from timings.
     */
    private void onEngineReturned() {
        int now = CrashProbe.countReports(this);
        if (now > crashReportsBeforeEngine) {
            EZ2RecoveryActivity.launch(this, EZ2RecoveryActivity.REASON_CRASH,
                    "The engine stopped unexpectedly after "
                            + (now - crashReportsBeforeEngine)
                            + " crash report(s) were written. Export diagnostics to attach "
                            + "them to a bug report.");
        }
        refreshStatus();
    }

    /**
     * Explicit intent for the engine host.
     *
     * <p>The VR categories have to be on the Intent, not only in the manifest
     * filter. This is an explicit start, so the system does not resolve the
     * filter and the categories never reach the launch Intent — and it is the
     * launch Intent that Meta's system reads to decide whether to hand focus to
     * an immersive HMD session. A manifest-only category leaves the engine
     * running in a flat 2D window on the compositor.
     */
    private Intent engineIntent() {
        Intent intent = new Intent(this, EZ2EngineActivity.class);
        intent.setAction("com.ez2vr.launcher.action.START_ENGINE");
        intent.addCategory("org.khronos.openxr.intent.category.IMMERSIVE_HMD");
        intent.addCategory("com.oculus.intent.category.VR");
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
