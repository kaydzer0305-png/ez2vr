package com.ez2vr.launcher;

import android.app.NativeActivity;
import android.os.Bundle;
import android.system.Os;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Engine host.
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
 * <p>v0.1 stops with a clear log if no native graph is bundled yet — that is
 * the expected state until the Mapbase Android native build lands (see README).
 */
public class EZ2EngineActivity extends NativeActivity {
    private static final String TAG = "EZ2VR";

    /**
     * Engine entry point loaded by NativeActivity via the
     * {@code android.app.lib_name} meta-data. Must stay in sync with
     * AndroidManifest.xml; libserver/libclient are dlopen'd from it via the
     * .load graph, not by the framework.
     */
    private static final String ENGINE_LIB_SO = "liblauncher.so";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EZ2Diagnostics.startSession(this, "engine");
        if (!EZ2ContentPresence.check(EZ2ContentPaths.sharedRoot(this)).readyToLaunch) {
            fail(EZ2RecoveryActivity.REASON_CONTENT,
                    EZ2ContentPresence.check(EZ2ContentPaths.sharedRoot(this)).problem);
            return;
        }
        if (!EZ2AssetStage.isCurrent(this)) {
            fail(EZ2RecoveryActivity.REASON_STAGING,
                    "VR overlay and content layers are missing or stale. Open the "
                            + "launcher and press Stage + Launch.");
            return;
        }
        try {
            setupEngineEnvironment();
        } catch (Exception e) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    "Engine environment setup failed: " + describe(e));
            return;
        }
        if (!hasNativeGraph()) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    "No engine runtime graph is bundled. This build has the launcher "
                            + "only — the Mapbase ARM64 engine build (liblauncher.so "
                            + "plus libserver.so/libclient.so for EZ2) is not in the "
                            + "APK yet. See README.md, remaining work item 1.");
            return;
        }
        try {
            EZ2AssetStage.stageNativeGraph(this);
        } catch (IOException e) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    "Could not stage the engine load graph: " + describe(e));
            return;
        }
        // A .load manifest with no ARM64 libs beside it is a half-finished native
        // drop. NativeActivity would surface that as an UnsatisfiedLinkError
        // crash dialog, which tells the user nothing about what to fix.
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        if (!new File(nativeDir, ENGINE_LIB_SO).isFile()) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    "Engine runtime graph is bundled but " + ENGINE_LIB_SO + " is "
                            + "absent from " + nativeDir + ". Add the ARM64 engine "
                            + "libraries under app/src/main/jniLibs/arm64-v8a/.");
            return;
        }
        try {
            super.onCreate(savedInstanceState);
        } catch (UnsatisfiedLinkError e) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    ENGINE_LIB_SO + " failed to load — likely the wrong ABI. The engine "
                            + "libraries must be built for arm64-v8a.");
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

    private boolean hasNativeGraph() {
        try {
            String[] files = getAssets().list("ez2/native");
            if (files == null) {
                return false;
            }
            for (String f : files) {
                if (f.endsWith(".load")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void setupEngineEnvironment() {
        File shared = EZ2ContentPaths.sharedRoot(this);
        File modSrc = EZ2ContentPaths.modSourceDir(shared);
        File writeDir = EZ2ContentPaths.writeGameDir(this);

        List<File> vrRoots = new ArrayList<>();
        for (String layer : EZ2AssetStage.VR_LAYERS) {
            File root = EZ2ContentPaths.vrLayerRoot(this, layer);
            if (root.isDirectory()) {
                vrRoots.add(root);
            }
        }
        File overlay = EZ2ContentPaths.overlayRoot(this);

        setenv("EZ2_GAME", modSrc.getName());
        setenv("EZ2_SHARED_CONTENT_PATH", shared.getAbsolutePath());
        setenv("EZ2_WRITE_GAME_PATH", writeDir.getAbsolutePath());
        setenv("EZ2_VR_CONTENT_PATH", joinExisting(vrRoots));
        setenv("EZ2_EXTRAS_PATH", overlay.isDirectory() ? overlay.getAbsolutePath() : "");
        setenv("EZ2_APK_PATH", getApplicationInfo().sourceDir);
        setenv("EZ2_LIB_PATH", getApplicationInfo().nativeLibraryDir);
        // Launch argv. Order matters (engine applies left to right):
        //   -game <Dir>            mod mount (Windows casing; resolved
        //                          case-insensitively against the depot)
        //   -mobile                enables the GameInit-time mobile posture file
        //   +exec ez2vr_boot.cfg   Quest boot baseline (overlay)
        //   +exec ez2vr_mobile.cfg Quest new-game posture (overlay)
        //   +ez2vr_* ...           player menu overrides — ALWAYS LAST so the
        //                          menu beats boot defaults every launch.
        String userCfgNote;
        try {
            userCfgNote = EZ2VrSettings.writeUserCfg(this).getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "user cfg snapshot failed; command line still carries settings", e);
            userCfgNote = "(snapshot failed)";
        }
        String args = "-game " + modSrc.getName()
                + " -mobile"
                + " +exec ez2vr_boot.cfg"
                + " +exec ez2vr_mobile.cfg"
                + " " + EZ2VrSettings.buildExtraArgs(this);
        setenv("EZ2_GAME_ARGS", args);
        // Compat alias: a future liblauncher.so built against the SourceVR
        // env contract can read this instead. Harmless otherwise.
        setenv("SOURCEVR_USER_LAUNCH_ARGS_PATH", userCfgNote);
        setSourceVrCompatEnv(shared, modSrc, writeDir, vrRoots, overlay, userCfgNote);

        Log.i(TAG, "engine env: game=" + modSrc.getName()
                + " shared=" + shared.getAbsolutePath()
                + " write=" + writeDir.getAbsolutePath()
                + " vrLayers=" + vrRoots.size()
                + " args=[" + args + "]");
    }

    /**
     * Exports the environment names the CURRENT engine actually reads, alongside
     * the {@code EZ2_*} set above.
     *
     * <p>This is not redundant. The shipped {@code liblauncher.so} is the EZ1
     * nillerusr build, whose Java layer sets {@code APP_DATA_PATH},
     * {@code NATIVE_LIB_DIR}, {@code SOURCEVR_*} and {@code VALVE_GAME_PATH}
     * (see {@code ez1-quest-engine/.../engine/EngineEnv.java:51-74}). It reads
     * no {@code EZ2_*} name, so without this block the engine mounts no content
     * root at all: the APK installs, the activity starts, and the engine comes
     * up blind. The {@code EZ2_*} names are the forward contract for the
     * Mapbase EZ2 fork (see native/ez2vr_argv_snippet.cpp) and stay exactly as
     * they are.
     *
     * <p>Safe to drop for good once a {@code liblauncher.so} built from the
     * EZ2 fork reads the {@code EZ2_*} names; the two sets are disjoint, so the
     * shim cannot shadow the real contract in the meantime.
     */
    private void setSourceVrCompatEnv(File shared, File modSrc, File writeDir,
                                      List<File> vrRoots, File overlay, String userCfgNote) {
        String libDir = getApplicationInfo().nativeLibraryDir;
        setenv("APP_DATA_PATH", EZ2ContentPaths.filesRoot(this).getAbsolutePath());
        setenv("NATIVE_LIB_DIR", libDir);
        setenv("APP_LIB_PATH", libDir);
        setenv("SOURCEVR_APK_PATH", getApplicationInfo().sourceDir);
        setenv("SOURCEVR_GAME", modSrc.getName());
        // Read-only depot (hl2/platform/<mod>) and the mounted read-write game
        // dir are distinct roots in this layout, unlike the EZ1 mapping where
        // both VALVE_GAME_PATH and SOURCEVR_SHARED_CONTENT_PATH pointed at one
        // contentRoot.
        setenv("SOURCEVR_SHARED_CONTENT_PATH", shared.getAbsolutePath());
        setenv("VALVE_GAME_PATH", writeDir.getAbsolutePath());
        setenv("SOURCEVR_WRITE_GAME_PATH", writeDir.getAbsolutePath());
        setenv("EXTRAS_VPK_PATH", overlay.isDirectory() ? overlay.getAbsolutePath() : "");
        setenv("VR_CONTENT_PATH", joinExisting(vrRoots));
        setenv("SOURCEVR_RUNTIME_GRAPH", nativeGraphPath());
        Log.i(TAG, "compat env: appData=" + EZ2ContentPaths.filesRoot(this).getAbsolutePath()
                + " shared=" + shared.getAbsolutePath()
                + " game=" + modSrc.getName()
                + " graph=" + nativeGraphPath());
    }

    /** Absolute path of the staged {@code .load} manifest, or "" when none. */
    private String nativeGraphPath() {
        File dir = EZ2ContentPaths.nativeGraphDir(this);
        File[] kids = dir.listFiles();
        if (kids == null) {
            return "";
        }
        File best = null;
        for (File f : kids) {
            if (!f.getName().endsWith(".load")) {
                continue;
            }
            if (best == null || f.lastModified() > best.lastModified()) {
                best = f;
            }
        }
        return best == null ? "" : best.getAbsolutePath();
    }

    private static String joinExisting(List<File> dirs) {
        StringBuilder sb = new StringBuilder();
        for (File f : dirs) {
            if (f != null && f.isDirectory()) {
                if (sb.length() != 0) {
                    sb.append(',');
                }
                sb.append(f.getAbsolutePath());
            }
        }
        return sb.toString();
    }

    private static void setenv(String key, String value) {
        try {
            Os.setenv(key, value, true);
        } catch (Exception e) {
            Log.e(TAG, "setenv(" + key + ") failed", e);
        }
    }
}
