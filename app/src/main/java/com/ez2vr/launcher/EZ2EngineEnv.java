package com.ez2vr.launcher;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's environment and launch argv, in one place.
 *
 * <p>Both boot paths need the same answer to "where is the content, what is the
 * mod, what is the write dir, what cvars apply", and they need it to be the
 * <em>same</em> answer: a divergence here is exactly the class of bug that is
 * invisible on the VR path (which renders nothing) and fatal on the flat one.
 * So the resolution and the formatting live here and both
 * {@link EZ2EngineActivity} and {@link EZ2FlatEngineActivity} call it.
 *
 * <p>The argv and env <em>builders</em> ({@link #flatArgv}, {@link #env}) take
 * plain strings and are unit tested on the JVM; only {@link #resolve} touches
 * Android.
 */
public final class EZ2EngineEnv {
    private static final String TAG = "EZ2VR";

    /**
     * Env switch that decides whether the engine takes its Source VR stereo
     * path at all. Read once by {@code sourcevr/ezquest_openxr_vr.cpp}.
     *
     * <p>Unset means "on", which is the pre-existing behavior for every profile,
     * so this stays a pure opt-out and the hl2/ez1 builds are unaffected. The
     * flat path must set it to 0: with no OpenXR session behind it, the stereo
     * path has no valid tracking snapshot, so
     * {@code GetEyeProjectionMatrix()} hands back an identity matrix while the
     * caller still sets {@code m_bViewToProjectionOverride}, the display bounds
     * claim VR-sized dimensions, and the 3D pass renders into per-eye targets
     * nothing composites. That is a black window, not a degraded picture.
     */
    public static final String ENV_VR_ENABLE = "EZQUEST_VR_ENABLE";

    /**
     * Full launch command line for the VR path, exported as one env string.
     *
     * <p>The VR host is a NativeActivity: nothing calls
     * {@code ValveActivity2.setArgs}, so the engine's {@code java_args} buffer
     * stays empty and {@code SetLauncherArgs()} would synthesize only
     * {@code -game} — no {@code -mobile}, no {@code +exec}, no menu convars.
     * The VR path would boot bone-stock retail. Instead the engine merges this
     * string into its argv when {@code java_args} is empty (see
     * native/ez2vr_argv_snippet.cpp, integrated in launcher/android/main.cpp),
     * so both paths honor the identical command line. The flat path exports it
     * too and ignores it there: {@code java_args} is nonempty on flat, so the
     * merge is skipped and {@code setArgs} stays authoritative.
     */
    public static final String ENV_GAME_ARGS = "EZ2_GAME_ARGS";

    /**
     * Opt-in, read once by the XR worker after the first presented frame.
     * {@code =1} spawns the engine boot worker (LauncherMainAndroid) on a
     * thread sharing the XR EGL share group, so the Source renderer draws
     * through the seam instead of the built-in diagnostic scene. Exported
     * {@code =1} on VR, {@code =0} on flat (which has no XR loop to read it).
     */
    public static final String ENV_TRY_ENGINE = "EZQUEST_VR_TRY_ENGINE";

    /**
     * Read by CSDLMgr at video init. {@code =1} skips SDL video/window setup
     * and binds the XR share-group context instead: a bare NativeActivity has
     * no SDLActivity behind it, so SDL_Init(VIDEO) cannot succeed there and
     * used to abort the whole :engine process. Exported {@code =1} on VR,
     * {@code =0} on flat (SDL owns the window there).
     */
    public static final String ENV_EXTERNAL_GL = "EZQUEST_VR_EXTERNAL_GL";

    /**
     * Fixed-foveated rendering master switch, read by the XR worker when it
     * applies the Meta framebuffer extensions. The VR Settings menu owns the
     * value ({@code ez2vr_ffr_level 0} means off); {@code =0} on flat, where
     * there is no runtime to apply it to.
     */
    public static final String ENV_FFR = "EZQUEST_VR_FFR";

    /**
     * Foveation level for the runtime, {@code 0..4} (the runtime's own scale;
     * the menu's {@code 0..3} passes straight through, so the top runtime
     * step stays reserved until the menu grows a fourth). Read alongside
     * {@link #ENV_FFR}; meaningless when that is {@code 0}.
     */
    public static final String ENV_FFR_LEVEL = "EZQUEST_VR_FFR_LEVEL";

    /**
     * Performance posture, {@code 0 quality, 1 balanced, 2 performance}, owned
     * by the VR Settings menu. Currently informational: the engine's lever
     * sets (fill-rate, particles, bone setup) have not landed yet, so nothing
     * reads it — but the value rides the same prefs-to-env path as
     * {@link #ENV_FFR_LEVEL}, which is what will consume it, and the on-device
     * env dump already shows what the menu chose.
     */
    public static final String ENV_PERF_MODE = "EZQUEST_VR_PERF_MODE";

    private EZ2EngineEnv() {}

    /** Everything the env and argv builders need, already resolved to strings. */
    public static final class Snapshot {
        public final String appData;
        public final String shared;
        public final String modName;
        public final String modPath;
        public final String writeDir;
        public final String vrLayers;
        public final String overlay;
        public final String apkPath;
        public final String libDir;
        public final String graph;

        Snapshot(String appData, String shared, String modName, String modPath,
                 String writeDir, String vrLayers, String overlay, String apkPath,
                 String libDir, String graph) {
            this.appData = appData;
            this.shared = shared;
            this.modName = modName;
            this.modPath = modPath;
            this.writeDir = writeDir;
            this.vrLayers = vrLayers;
            this.overlay = overlay;
            this.apkPath = apkPath;
            this.libDir = libDir;
            this.graph = graph;
        }
    }

    /**
     * Walks the depot and the app's own paths.
     *
     * @param userCfgNote absolute path of the authored settings snapshot, or a
     *                    human-readable note when it could not be written
     */
    public static Snapshot resolve(Context ctx, String userCfgNote) {
        File filesRoot = EZ2ContentPaths.filesRoot(ctx);
        File sharedRoot = EZ2ContentPaths.sharedRoot(ctx);
        File modSrc = EZ2ContentPaths.modSourceDir(sharedRoot);
        File writeDir = EZ2ContentPaths.writeGameDir(ctx);
        File overlay = EZ2ContentPaths.overlayRoot(ctx);

        List<File> vrRoots = new ArrayList<>();
        for (String layer : EZ2AssetStage.VR_LAYERS) {
            File root = EZ2ContentPaths.vrLayerRoot(ctx, layer);
            if (root.isDirectory()) {
                vrRoots.add(root);
            }
        }
        return new Snapshot(
                filesRoot.getAbsolutePath(),
                sharedRoot.getAbsolutePath(),
                modSrc.getName(),
                modSrc.getAbsolutePath(),
                writeDir.getAbsolutePath(),
                joinExisting(vrRoots),
                overlay.isDirectory() ? overlay.getAbsolutePath() : "",
                ctx.getApplicationInfo().sourceDir,
                ctx.getApplicationInfo().nativeLibraryDir,
                nativeGraphPath(ctx));
    }

    /**
     * The launch argv for the flat path.
     *
     * <p>Order matters; the engine applies tokens left to right:
     * <pre>
     *   -game &lt;abs mod dir&gt;      mount the EZ2 mod (absolute, see below)
     *   -mobile                     the GameInit-time mobile posture
     *   +exec ez2vr_boot.cfg        overlay boot baseline
     *   +exec ez2vr_mobile.cfg      overlay new-game posture
     *   +ez2vr_* ...                player menu overrides, ALWAYS LAST
     * </pre>
     *
     * <p>{@code -game} must be the <em>absolute</em> mod directory here, not the
     * bare directory name. The engine's {@code SetLauncherArgs()} only injects a
     * {@code -game} of its own when {@code java_args} contains no {@code -game}
     * token at all, and when it does inject one it builds it from
     * {@code VALVE_GAME_PATH} + {@code SOURCEVR_GAME} — which in this layout is
     * the <em>write</em> dir, not the content dir. Naming the mod by bare
     * directory name would therefore resolve to the wrong tree.
     *
     * <p>The token string is split on spaces by {@code strtok} in the engine
     * ({@code launcher/android/main.cpp:88-95}) with no quoting and no escape, so
     * a path containing a space cannot survive the crossing. The depot root is
     * {@code <externalFiles>/ez2vr/v1}, which cannot contain one today, but this
     * refuses rather than trusting that: a silently truncated {@code -game} looks
     * exactly like missing content.
     */
    public static String flatArgv(String modAbsPath, String convarArgs) {
        if (modAbsPath == null || modAbsPath.isEmpty()) {
            throw new IllegalArgumentException("flat argv needs a mod path");
        }
        if (modAbsPath.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("mod path contains a space and cannot "
                    + "cross into the engine's argv, which is split on whitespace "
                    + "without quoting: " + modAbsPath);
        }
        StringBuilder sb = new StringBuilder()
                .append("-game ").append(modAbsPath)
                .append(" -mobile")
                .append(" +exec ez2vr_boot.cfg")
                .append(" +exec ez2vr_mobile.cfg");
        if (convarArgs != null && !convarArgs.isEmpty()) {
            sb.append(' ').append(convarArgs);
        }
        return sb.toString();
    }

    /**
     * The full environment, in a stable order for logging.
     *
     * <p>Two disjoint sets, deliberately:
     * <ul>
     *   <li>{@code EZ2_*} — the forward contract for a Mapbase EZ2 fork of
     *       {@code liblauncher.so}. Nothing in the current engine reads these,
     *       except {@link #ENV_GAME_ARGS}, which the current bootstrap merges
     *       (see its javadoc).
     *   <li>{@code APP_DATA_PATH}, {@code NATIVE_LIB_DIR}, {@code SOURCEVR_*},
     *       {@code VALVE_GAME_PATH}, {@code EXTRAS_VPK_PATH} — what the shipped
     *       nillerusr-derived engine <em>actually</em> reads today. Without these
     *       it mounts no content root at all: the APK installs, the activity
     *       starts, and the engine comes up blind.
     * </ul>
     * They cannot shadow each other, so the compat set is safe to drop once a
     * real EZ2 engine build lands.
     *
     * @param convarArgs player menu overrides ({@code +ez2vr_* ...}), appended
     *                   last in {@link #ENV_GAME_ARGS} so they win over boot
     *                   defaults on the VR path exactly as they do in
     *                   {@link #flatArgv} on flat
     * @param ffrLevel menu's {@code ez2vr_ffr_level} ({@code 0..3}, clamped),
     *                 driving {@link #ENV_FFR} + {@link #ENV_FFR_LEVEL}
     * @param perfMode menu's {@code ez2vr_performance_mode} ({@code 0..2}),
     *                 exported as {@link #ENV_PERF_MODE}
     */
    public static Map<String, String> env(Snapshot s, boolean vrEnabled, String userCfgNote,
                                          String convarArgs, int ffrLevel, int perfMode) {
        Map<String, String> m = new LinkedHashMap<>();
        // Forward contract.
        m.put("EZ2_GAME", s.modName);
        m.put("EZ2_SHARED_CONTENT_PATH", s.shared);
        m.put("EZ2_WRITE_GAME_PATH", s.writeDir);
        m.put("EZ2_VR_CONTENT_PATH", s.vrLayers);
        m.put("EZ2_EXTRAS_PATH", s.overlay);
        m.put("EZ2_APK_PATH", s.apkPath);
        m.put("EZ2_LIB_PATH", s.libDir);
        m.put("EZ2_RUNTIME_GRAPH", s.graph);
        // What the current engine reads.
        m.put("APP_DATA_PATH", s.appData);
        m.put("NATIVE_LIB_DIR", s.libDir);
        m.put("APP_LIB_PATH", s.libDir);
        m.put("SOURCEVR_APK_PATH", s.apkPath);
        m.put("SOURCEVR_GAME", s.modName);
        m.put("SOURCEVR_SHARED_CONTENT_PATH", s.shared);
        // The CONTENT root, not the write dir, and not the mod dir.
        //
        // On Android this is the base directory, full stop: GetBaseDirectory()
        // (launcher/launcher.cpp:256-261) returns getenv("VALVE_GAME_PATH") under
        // #ifdef ANDROID, ignoring both the computed g_szBasedir and any -basedir
        // override. The engine then chdir()s there and resolves materials, maps
        // and VPKs against it, so pointing it at the write dir — which holds only
        // cfg/ — makes the first content lookup fail with "Can't find background
        // image 'materials/console/startup_loading.vtf'" and nothing renders.
        //
        // Content root (not the mod dir) is the right value because the engine's
        // own -game synthesis expects exactly that shape: SetLauncherArgs() builds
        // "<VALVE_GAME_PATH>/<SOURCEVR_GAME>" (main.cpp:79), so shared +
        // "EntropyZero2" resolves back to the mod dir. That also repairs the VR
        // path, which has been resolving -game to <writeDir>/EntropyZero2.
        // Matches the proven EZ1 mapping, where VALVE_GAME_PATH and
        // SOURCEVR_SHARED_CONTENT_PATH are the same content root.
        m.put("VALVE_GAME_PATH", s.shared);
        m.put("SOURCEVR_WRITE_GAME_PATH", s.writeDir);
        m.put("EXTRAS_VPK_PATH", s.overlay);
        m.put("VR_CONTENT_PATH", s.vrLayers);
        m.put("SOURCEVR_RUNTIME_GRAPH", s.graph);
        // Deliberately EMPTY, and the userCfgNote argument is not used for it.
        //
        // SOURCEVR_USER_LAUNCH_ARGS_PATH is not a cfg path: the engine opens it
        // and appends every whitespace-separated token to argv
        // (launcher/android/main.cpp:100-127), stopping at the first token that
        // starts with a quote or '@'. It expects a launch-args file that a
        // Java-side validator has already checked.
        //
        // It used to be pointed at ez2vr_user.cfg, which is a debug convar dump,
        // and the engine duly appended the 14 words of its comment header as
        // command-line arguments. Settings already travel on the command line
        // (flatArgv, or the engine's own -game synthesis on the VR path), so the
        // correct value here is "unset", which the engine skips.
        m.put("SOURCEVR_USER_LAUNCH_ARGS_PATH", "");
        // The VR path's argv. Identical string to the flat path's setArgs line,
        // so both boots honor -game -mobile +execs + menu overrides. Merged by
        // the engine only when java_args is empty (VR); skipped on flat.
        m.put(ENV_GAME_ARGS, flatArgv(s.modPath, convarArgs));
        // Through-the-seam rendering switches. Read by the XR worker (boot) and
        // CSDLMgr (external GL context) respectively; the flat path has neither
        // reader, so exporting 0 there is inert documentation.
        m.put(ENV_TRY_ENGINE, vrEnabled ? "1" : "0");
        m.put(ENV_EXTERNAL_GL, vrEnabled ? "1" : "0");
        // Menu-owned frame pacing. The runtime applies FFR + refresh when the
        // XR session comes up (see EZQuestVrApplyFfrAndRefresh); refresh stays
        // on the runtime default (90 Hz) until the menu owns a refresh pref.
        int ffr = Math.max(0, Math.min(3, ffrLevel));
        m.put(ENV_FFR, vrEnabled && ffr > 0 ? "1" : "0");
        m.put(ENV_FFR_LEVEL, String.valueOf(ffr));
        m.put(ENV_PERF_MODE,
                String.valueOf(Math.max(0, Math.min(2, perfMode))));
        // Stereo-path opt-out. Must be set explicitly on both paths so the mode
        // never depends on a race between the XR worker and engine init.
        m.put(ENV_VR_ENABLE, vrEnabled ? "1" : "0");
        return m;
    }

    /**
     * Exports an environment map via {@code Os.setenv}.
     *
     * <p>Per-key failures are logged and skipped rather than aborting: a single
     * unset name should not cost the user the whole boot, and the log line names
     * exactly which one.
     */
    public static void export(Map<String, String> env) {
        for (Map.Entry<String, String> e : env.entrySet()) {
            try {
                android.system.Os.setenv(e.getKey(), e.getValue(), true);
            } catch (Exception ex) {
                Log.e(TAG, "setenv(" + e.getKey() + ") failed", ex);
            }
        }
    }

    /** Absolute path of the staged {@code .load} manifest, or "" when none. */
    private static String nativeGraphPath(Context ctx) {
        File dir = EZ2ContentPaths.nativeGraphDir(ctx);
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
}
