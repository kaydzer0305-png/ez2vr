package com.ez2vr.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;
import org.junit.Test;

/**
 * Locks down the two pure builders behind both boot paths: the flat argv string
 * and the environment map.
 *
 * <p>These cross into C++ by value and there is no way to inspect what the engine
 * actually received on device except by reading logcat off the headset. A silent
 * mistake here — a wrong {@code -game}, a missing opt-out — presents as a black
 * window or as content that is mysteriously absent, which is the same symptom the
 * VR path already has. So the string is pinned here instead.
 */
public class EZ2EngineEnvTest {

    private static EZ2EngineEnv.Snapshot snapshot(String modPath) {
        // shared is the mod dir's parent, so a test can supply a coherent pair.
        // That matters because the engine rebuilds -game as
        // VALVE_GAME_PATH + "/" + SOURCEVR_GAME, and one of the tests below
        // asserts the two agree.
        int slash = modPath.lastIndexOf('/');
        String shared = slash > 0 ? modPath.substring(0, slash) : "/data/app/files/ez2vr/v1/shared";
        return new EZ2EngineEnv.Snapshot(
                "/data/app/files",                       // appData
                shared,                                  // shared
                "EntropyZero2",                          // modName
                modPath,                                 // modPath
                "/data/app/files/ez2vr/v1/content/EntropyZero2", // writeDir
                "/data/app/files/ez2vr/v1/vr/ez2",       // vrLayers
                "/data/app/files/ez2vr/v1/overlay/ez2",  // overlay
                "/data/app/base.apk",                    // apkPath
                "/data/app/lib/arm64",                   // libDir
                "/data/app/files/ez2vr/v1/native/ez2vr.load"); // graph
    }

    // ---- flatArgv ---------------------------------------------------------

    @Test
    public void flatArgvMountsTheModByAbsolutePath() {
        String argv = EZ2EngineEnv.flatArgv(
                "/data/app/files/ez2vr/v1/shared/EntropyZero2", "");
        assertTrue(argv, argv.startsWith(
                "-game /data/app/files/ez2vr/v1/shared/EntropyZero2"));
    }

    /**
     * The bug this exists to prevent: naming the mod by bare directory name. The
     * engine's SetLauncherArgs only synthesizes its own -game when java_args has
     * no -game token at all, and when it does it builds that from
     * VALVE_GAME_PATH + SOURCEVR_GAME, which in this layout is the WRITE dir. A
     * bare name therefore resolves to the wrong tree and the engine comes up
     * with no content while every gate in the launcher reported success.
     */
    @Test
    public void flatArgvNeverPassesABareDirectoryNameAsGame() {
        String argv = EZ2EngineEnv.flatArgv(
                "/data/app/files/ez2vr/v1/shared/EntropyZero2", "");
        String game = argv.substring(argv.indexOf("-game ") + "-game ".length());
        game = game.substring(0, game.indexOf(' '));
        assertTrue("must be absolute so it cannot be re-rooted onto the write dir: " + game,
                game.startsWith("/"));
    }

    @Test
    public void flatArgvOrdersConvarOverridesLast() {
        String argv = EZ2EngineEnv.flatArgv("/mod", "+ez2vr_seated 1 +ez2vr_ffr 2");
        int execBoot = argv.indexOf("+exec ez2vr_boot.cfg");
        int execMobile = argv.indexOf("+exec ez2vr_mobile.cfg");
        int seated = argv.indexOf("+ez2vr_seated");
        assertTrue(execBoot >= 0 && execMobile > execBoot && seated > execMobile);
    }

    @Test
    public void flatArgvCarriesMobileAndBothOverlayCfgs() {
        String argv = EZ2EngineEnv.flatArgv("/mod", "");
        assertTrue(argv, argv.contains(" -mobile"));
        assertTrue(argv, argv.contains(" +exec ez2vr_boot.cfg"));
        assertTrue(argv, argv.contains(" +exec ez2vr_mobile.cfg"));
    }

    @Test
    public void flatArgvToleratesEmptyConvarArgs() {
        String withEmpty = EZ2EngineEnv.flatArgv("/mod", "");
        String withNull = EZ2EngineEnv.flatArgv("/mod", null);
        assertEquals(withEmpty, withNull);
        assertFalse(withEmpty, withEmpty.endsWith(" "));
    }

    /**
     * The engine splits argv on spaces with strtok and no quoting, so a space in
     * the path silently truncates -game. That looks identical to missing content,
     * so refuse instead of producing it.
     */
    @Test
    public void flatArgvRefusesAPathWithASpace() {
        try {
            EZ2EngineEnv.flatArgv("/data/My Games/EntropyZero2", "");
            fail("expected refusal: the engine cannot receive a quoted path");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("space"));
        }
    }

    @Test
    public void flatArgvRefusesAnEmptyModPath() {
        try {
            EZ2EngineEnv.flatArgv("", "");
            fail("expected refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("mod path"));
        }
    }

    // ---- env --------------------------------------------------------------

    /**
     * The load-bearing assertion of the whole flat path. Without this the engine
     * takes its Source VR stereo loop with no XR runtime behind it: no tracking
     * snapshot is ever published, so GetEyeProjectionMatrix returns an identity
     * matrix while the caller still sets m_bViewToProjectionOverride, and the
     * 3D pass renders into per-eye targets nothing composites. Black window.
     */
    @Test
    public void envDisablesTheStereoPathOnFlat() {
        Map<String, String> flat =
                EZ2EngineEnv.env(snapshot("/mod"), false, "/cfg/ez2vr_user.cfg", "");
        assertEquals("0", flat.get(EZ2EngineEnv.ENV_VR_ENABLE));
    }

    @Test
    public void envEnablesTheStereoPathOnVr() {
        Map<String, String> vr =
                EZ2EngineEnv.env(snapshot("/mod"), true, "/cfg/ez2vr_user.cfg", "");
        assertEquals("1", vr.get(EZ2EngineEnv.ENV_VR_ENABLE));
    }

    /**
     * Unset must mean "on". The engine treats anything but a leading 0 as on, so
     * the hl2 and ez1 profiles keep their existing behavior without this name
     * being set at all.
     */
    @Test
    public void stereoOptOutIsOptOutOnly() {
        assertEquals("1", EZ2EngineEnv.env(snapshot("/mod"), true, "", "").get("EZQUEST_VR_ENABLE"));
        assertEquals("0", EZ2EngineEnv.env(snapshot("/mod"), false, "", "").get("EZQUEST_VR_ENABLE"));
    }

    /**
     * The names the shipped nillerusr-derived engine actually reads. It reads no
     * EZ2_* name, so without these it mounts no content root at all: the APK
     * installs, the activity starts, and the engine comes up blind.
     */
    @Test
    public void envCarriesTheNamesTheCurrentEngineReads() {
        Map<String, String> env =
                EZ2EngineEnv.env(snapshot("/mod"), false, "/cfg/ez2vr_user.cfg", "");
        assertEquals("/data/app/files", env.get("APP_DATA_PATH"));
        assertEquals("/data/app/lib/arm64", env.get("NATIVE_LIB_DIR"));
        assertEquals("/data/app/lib/arm64", env.get("APP_LIB_PATH"));
        assertEquals("EntropyZero2", env.get("SOURCEVR_GAME"));
        assertEquals("/data/app/files/ez2vr/v1/shared", env.get("SOURCEVR_SHARED_CONTENT_PATH"));
        assertEquals("/data/app/files/ez2vr/v1/native/ez2vr.load",
                env.get("SOURCEVR_RUNTIME_GRAPH"));
    }

    /** The forward contract for a Mapbase EZ2 fork; kept alongside the compat set. */
    @Test
    public void envCarriesTheEz2ForwardContract() {
        Map<String, String> env =
                EZ2EngineEnv.env(snapshot("/mod"), false, "/cfg/ez2vr_user.cfg", "");
        assertEquals("EntropyZero2", env.get("EZ2_GAME"));
        assertEquals("/data/app/files/ez2vr/v1/shared", env.get("EZ2_SHARED_CONTENT_PATH"));
        assertEquals("/data/app/files/ez2vr/v1/content/EntropyZero2",
                env.get("EZ2_WRITE_GAME_PATH"));
        assertEquals("/data/app/files/ez2vr/v1/native/ez2vr.load", env.get("EZ2_RUNTIME_GRAPH"));
    }

    /**
     * Regression, and the one that made the flat path render nothing.
     *
     * <p>GetBaseDirectory() returns getenv("VALVE_GAME_PATH") on Android
     * (launcher.cpp:256-261), so this value is the engine's base directory. It
     * pointed at the write dir, which holds only cfg/, and the engine died on the
     * first content load with "Can't find background image
     * 'materials/console/startup_loading.vtf'".
     *
     * <p>Content root, not mod dir, because SetLauncherArgs() rebuilds -game as
     * "<VALVE_GAME_PATH>/<SOURCEVR_GAME>" (main.cpp:79) — so this must be the
     * parent of the mod dir for the engine's own -game synthesis to land right.
     */
    @Test
    public void valveGamePathIsTheContentRootNotTheWriteDir() {
        Map<String, String> env =
                EZ2EngineEnv.env(snapshot("/mod"), false, "/cfg/ez2vr_user.cfg", "");
        assertEquals("/data/app/files/ez2vr/v1/shared", env.get("VALVE_GAME_PATH"));
        assertEquals("/data/app/files/ez2vr/v1/content/EntropyZero2",
                env.get("SOURCEVR_WRITE_GAME_PATH"));
    }

    /** VALVE_GAME_PATH + SOURCEVR_GAME must reconstruct the -game we pass. */
    @Test
    public void engineSynthesisedGamePathMatchesOurFlatArgv() {
        EZ2EngineEnv.Snapshot snap = snapshot("/data/shared/EntropyZero2");
        Map<String, String> env = EZ2EngineEnv.env(snap, false, "", "");
        String synthesised = env.get("VALVE_GAME_PATH") + "/" + env.get("SOURCEVR_GAME");
        assertTrue(synthesised, EZ2EngineEnv.flatArgv(snap.modPath, "")
                .startsWith("-game " + synthesised + " "));
    }

    /** No value may be null: Os.setenv rejects null and the export loop logs. */
    @Test
    public void envHasNoNullValues() {
        Map<String, String> env =
                EZ2EngineEnv.env(snapshot("/mod"), false, null, "");
        for (Map.Entry<String, String> e : env.entrySet()) {
            assertFalse("null value for " + e.getKey(), e.getValue() == null);
        }
    }

    /**
     * Regression: this used to carry the path of ez2vr_user.cfg, a convar dump.
     * The engine appends every whitespace-separated token of that file to argv
     * (main.cpp:100-127), so the engine received the 14 words of the file's
     * comment header as command-line arguments on every launch.
     */
    @Test
    public void launchArgsPathIsUnsetNotTheConvarDump() {
        Map<String, String> env = EZ2EngineEnv.env(snapshot("/mod"), false, "/cfg/ez2vr_user.cfg", "");
        assertEquals("", env.get("SOURCEVR_USER_LAUNCH_ARGS_PATH"));
    }

    /** Stable order keeps the on-device env dump diffable between launches. */
    @Test
    public void envOrderIsStable() {
        Map<String, String> env = EZ2EngineEnv.env(snapshot("/mod"), true, "x", "");
        java.util.List<String> keys = new java.util.ArrayList<>(env.keySet());
        assertEquals("EZ2_GAME", keys.get(0));
        assertEquals(EZ2EngineEnv.ENV_VR_ENABLE, keys.get(keys.size() - 1));
    }

    /**
     * The VR path's argv. The VR host is a NativeActivity so java_args stays
     * empty; without this the engine would synthesize only -game and boot
     * bone-stock retail (no -mobile, no +exec, no menu convars).
     */
    @Test
    public void envCarriesTheVrArgvAsGameArgs() {
        EZ2EngineEnv.Snapshot snap = snapshot("/data/shared/EntropyZero2");
        Map<String, String> env = EZ2EngineEnv.env(snap, true, "", "+ez2vr_seated 1");
        String args = env.get(EZ2EngineEnv.ENV_GAME_ARGS);
        assertTrue(args, args.startsWith("-game /data/shared/EntropyZero2"));
        assertTrue(args, args.contains(" -mobile"));
        assertTrue(args, args.contains(" +exec ez2vr_boot.cfg"));
        assertTrue(args, args.contains(" +exec ez2vr_mobile.cfg"));
        int mobile = args.indexOf("+exec ez2vr_mobile.cfg");
        assertTrue(args, args.indexOf("+ez2vr_seated 1") > mobile);
    }

    /** The flat path exports the same string it hands to setArgs (parity). */
    @Test
    public void gameArgsMatchTheFlatArgv() {
        EZ2EngineEnv.Snapshot snap = snapshot("/data/shared/EntropyZero2");
        Map<String, String> env = EZ2EngineEnv.env(snap, false, "", "+ez2vr_seated 1");
        assertEquals(EZ2EngineEnv.flatArgv(snap.modPath, "+ez2vr_seated 1"),
                env.get(EZ2EngineEnv.ENV_GAME_ARGS));
    }

    /** Through-the-seam switches: on for VR (XR worker + external GL exist). */
    @Test
    public void envArmsTheSeamSwitchesOnVr() {
        Map<String, String> env = EZ2EngineEnv.env(snapshot("/mod"), true, "", "");
        assertEquals("1", env.get(EZ2EngineEnv.ENV_TRY_ENGINE));
        assertEquals("1", env.get(EZ2EngineEnv.ENV_EXTERNAL_GL));
    }

    /** ...and off for flat (SDL owns the window; there is no XR loop). */
    @Test
    public void envDisarmsTheSeamSwitchesOnFlat() {
        Map<String, String> env = EZ2EngineEnv.env(snapshot("/mod"), false, "", "");
        assertEquals("0", env.get(EZ2EngineEnv.ENV_TRY_ENGINE));
        assertEquals("0", env.get(EZ2EngineEnv.ENV_EXTERNAL_GL));
    }
}
