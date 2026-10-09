package com.ez2vr.launcher;

import android.os.Bundle;
import android.util.Log;
import java.io.IOException;
import java.util.Map;
import org.libsdl.app.SDLActivity;

/**
 * Flat engine host: boots the same engine binaries as {@link EZ2EngineActivity}
 * with no OpenXR anywhere in the picture.
 *
 * <p>This is the validation path for the port. The VR path currently creates and
 * begins an OpenXR session and then never leaves {@code XR_SESSION_STATE_READY},
 * so nothing renders — which leaves the EZ2 game DLLs, the Mapbase AI backplane
 * and the prediction changes unverifiable on device. This path renders, so all of
 * it can be exercised while the OpenXR question stays open.
 *
 * <p>Mechanically it is EZ1's flat boot path, and the reason it is cheap: the
 * engine already contains it. {@code launcher/android/main.cpp:256}
 * ({@code LauncherMainAndroid}) is compiled into the same {@code liblauncher.so}
 * the VR path uses and is exported from it — the flat path needs no engine work
 * beyond the stereo opt-out in {@link EZ2EngineEnv#ENV_VR_ENABLE}.
 *
 * <h2>Ordering, which is the whole subtlety here</h2>
 *
 * <p>{@code SDLActivity.onCreate} returns promptly but starts the engine on its
 * own thread the moment the surface exists ({@code SDLActivity.java:589} ->
 * {@code SDLMain} -> {@code nativeRunMain} -> {@code SDL_main}, the last of which
 * is a <em>synchronous</em> call at {@code SDL_android.c:772}). So the engine is
 * already running by the time {@code onCreate} returns, and anything configured
 * afterwards races it. Everything therefore happens before {@code super}:
 *
 * <ol>
 *   <li>{@code System.loadLibrary("launcher")} — {@link com.valvesoftware.ValveActivity2}
 *       binds by fully-qualified name, so the library has to be resident before
 *       {@code setArgs} can be called at all. {@code SDLActivity} would load it
 *       later, which is too late.
 *   <li>env export and {@code setArgs} — both read by the engine during
 *       {@code LauncherMainAndroid}.
 *   <li>{@code super.onCreate} — loads SDL2 (the engine lib is already resident),
 *       creates the surface, and the SDL thread enters {@code LauncherMain}.
 * </ol>
 *
 * <p>EZ1's {@code ValveActivity2} gets this wrong: it calls {@code super.onCreate}
 * first and configures the environment afterwards, so its {@code setArgs("-game
 * ...")} races the boot and is sometimes too late to matter.
 *
 * <h2>Not a VR activity</h2>
 *
 * <p>Deliberately carries no {@code com.oculus.intent.category.VR} and no
 * {@code org.khronos.openxr.intent.category.IMMERSIVE_HMD}, on the Intent or in
 * the manifest. Those categories make Meta's shell run a full immersive
 * transition for the window, which is what left the 2D launcher showing the Meta
 * loading screen and then bouncing home with {@code buffer=0x0}. A flat path that
 * asks for immersive focus is not a flat path.
 */
public class EZ2FlatEngineActivity extends SDLActivity {
    private static final String TAG = "EZ2VR";

    /**
     * So names, not file names.
     *
     * <p>{@code SDL.loadLibrary} wraps each entry as {@code lib<name>.so} and hands
     * it to {@code System.loadLibrary}. Passing {@code "launcher.so"} here
     * therefore asks for {@code liblauncher.so.so} and the boot dies in
     * {@code dlopen} — which is what happened the first time this was derived by
     * chopping the string rather than naming the library.
     *
     * <p>{@code "launcher"} is the same value the immersive host declares as
     * {@code android.app.lib_name} in AndroidManifest.xml, so both boot paths load
     * the identical file.
     */
    @Override
    protected String[] getLibraries() {
        // SDL2 first: it provides the JNI_OnLoad that registers the
        // org.libsdl.app.* natives this class inherits from.
        return new String[] {
                "SDL2",
                "launcher"
        };
    }

    /**
     * Absolute path to the shared object holding {@code LauncherMainAndroid}.
     *
     * <p>{@code SDLActivity}'s default already builds this correctly from
     * {@link #getLibraries()} as {@code nativeLibraryDir + "/lib" + <last entry> +
     * ".so"} (SDLActivity.java:138-147). Overridden anyway so the path is stated
     * rather than inferred — and, more usefully, so it is a full path: the engine
     * dlopens this name directly, so a bare basename resolves against the process
     * working directory and is not found.
     */
    @Override
    protected String getMainSharedObject() {
        return getApplicationInfo().nativeLibraryDir + "/"
                + EZ2NativeGate.ENGINE_LIB_SO;
    }

    @Override
    protected String getMainFunction() {
        // launcher/android/main.cpp:256. Not ANativeActivity_onCreate, which is
        // the VR entry and would take us straight back into OpenXR.
        return "LauncherMainAndroid";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EZ2Diagnostics.startSession(this, "engine-flat");
        if (!prepareEngine()) {
            return;
        }
        try {
            super.onCreate(savedInstanceState);
        } catch (UnsatisfiedLinkError e) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    EZ2NativeGate.ENGINE_LIB_SO + " failed to load — likely the wrong "
                            + "ABI. The engine libraries must be built for arm64-v8a.");
        }
    }

    /**
     * Gates, then loads the engine and hands it its argv.
     *
     * @return true when {@code super.onCreate} should proceed
     */
    private boolean prepareEngine() {
        EZ2ContentPresence presence =
                EZ2ContentPresence.check(EZ2ContentPaths.sharedRoot(this));
        if (!presence.readyToLaunch) {
            fail(EZ2RecoveryActivity.REASON_CONTENT, presence.problem);
            return false;
        }
        if (!EZ2AssetStage.isCurrent(this)) {
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

        // Before super.onCreate(), so the by-name natives in
        // com.valvesoftware.ValveActivity2 bind and the engine sees the argv.
        try {
            System.loadLibrary("launcher");
        } catch (UnsatisfiedLinkError e) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    EZ2NativeGate.ENGINE_LIB_SO + " failed to load — likely the wrong "
                            + "ABI. The engine libraries must be built for arm64-v8a.");
            return false;
        }

        String argv;
        try {
            argv = configureEngine();
        } catch (RuntimeException e) {
            fail(EZ2RecoveryActivity.REASON_NATIVE,
                    "Engine environment setup failed: " + describe(e));
            return false;
        }
        com.valvesoftware.ValveActivity2.setArgs(argv);
        Log.i(TAG, "flat argv: [" + argv + "]");
        return true;
    }

    /**
     * Exports the engine environment with the stereo path switched off, and
     * returns the argv to hand to {@code LauncherMainAndroid}.
     */
    private String configureEngine() {
        String userCfgNote;
        try {
            userCfgNote = EZ2VrSettings.writeUserCfg(this).getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "user cfg snapshot failed; command line still carries settings", e);
            userCfgNote = "(snapshot failed)";
        }

        EZ2EngineEnv.Snapshot snap = EZ2EngineEnv.resolve(this, userCfgNote);
        Map<String, String> env = EZ2EngineEnv.env(snap, /* vrEnabled= */ false, userCfgNote);
        EZ2EngineEnv.export(env);

        String argv = EZ2EngineEnv.flatArgv(
                snap.modPath, EZ2VrSettings.buildExtraArgs(this));

        Log.i(TAG, "flat env: game=" + snap.modName
                + " mod=" + snap.modPath
                + " shared=" + snap.shared
                + " write=" + snap.writeDir
                + " vrLayers=" + (snap.vrLayers.isEmpty() ? 0 : snap.vrLayers.split(",").length)
                + " " + EZ2EngineEnv.ENV_VR_ENABLE + "=" + env.get(EZ2EngineEnv.ENV_VR_ENABLE)
                + " graph=" + snap.graph);
        return argv;
    }

    /**
     * Shows the fail-loud reason, then leaves.
     *
     * <p>Never just {@code Log.e() + finish()}: a Quest user cannot read logcat,
     * so a silent finish is indistinguishable from a hung app.
     */
    private void fail(String reason, String message) {
        Log.e(TAG, "flat engine start refused (" + reason + "): " + message);
        EZ2Diagnostics.event(this, "engine_start_refused", "mode", EZ2BootMode.FLAT.token(),
                "reason", reason, "message", message);
        EZ2RecoveryActivity.launch(this, reason, message);
        finish();
    }

    /**
     * {@code SDLMain} calls {@code finish()} on us when the engine's main
     * function returns ({@code SDLActivity.java:1669}), so this is the normal
     * exit and the place to close the diagnostics session — which the VR path
     * cannot currently do from the engine process at all.
     */
    @Override
    protected void onDestroy() {
        EZ2Diagnostics.endSession(this, !isFinishing());
        super.onDestroy();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty()
                ? e.getClass().getSimpleName() : message;
    }
}
