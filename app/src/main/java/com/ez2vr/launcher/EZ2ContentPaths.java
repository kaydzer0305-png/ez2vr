package com.ez2vr.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;

/**
 * Simplified counterpart of SourceVR's ContentPaths.
 *
 * <p>Single-profile only: no multi-game picker, no package-suffix guessing
 * (cf. ContentPaths.java:164-208). Layout:
 *
 * <pre>
 *   &lt;externalFiles&gt;/ez2vr/v1/shared/   - depot roots (hl2, platform, EntropyZero2)
 *   &lt;externalFiles&gt;/ez2vr/v1/content/  - staged write game dir
 *   &lt;externalFiles&gt;/ez2vr/v1/overlay/  - VR overlay (own cfgs/materials)
 *   &lt;externalFiles&gt;/ez2vr/v1/vr/       - VR content layers
 * </pre>
 */
public final class EZ2ContentPaths {
    private static final String PREFS = "ez2vr_content";
    private static final String KEY_CONTENT_ROOT = "content_root_uri";

    private EZ2ContentPaths() {}

    public static File filesRoot(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        return ext != null ? ext : ctx.getFilesDir();
    }

    public static File sharedRoot(Context ctx) {
        return new File(filesRoot(ctx), "ez2vr/v1/shared");
    }

    public static File contentRoot(Context ctx) {
        return new File(filesRoot(ctx), "ez2vr/v1/content");
    }

    public static File overlayRoot(Context ctx) {
        return new File(filesRoot(ctx), "ez2vr/v1/overlay/" + EZ2Profile.PROFILE_ID);
    }

    public static File vrLayerRoot(Context ctx, String layer) {
        return new File(filesRoot(ctx), "ez2vr/v1/vr/" + layer);
    }

    /**
     * Where the engine's native {@code .load} graph is staged.
     *
     * <p>It ships inside the APK under {@code assets/ez2/native}, but a
     * {@code .load} manifest is read by path at runtime and names libraries
     * that live in the installer's extracted native lib dir, so it has to
     * exist as a real file. Kept beside the content depot so it can be pulled
     * over adb by hand when a build misbehaves.
     */
    public static File nativeGraphDir(Context ctx) {
        return new File(filesRoot(ctx), "ez2vr/v1/native");
    }

    /** Write game dir the engine mounts read-write (cf. SOURCEVR_WRITE_GAME_PATH). */
    public static File writeGameDir(Context ctx) {
        return new File(contentRoot(ctx), EZ2Profile.GAME_DIR_WINDOWS);
    }

    /**
     * Resolves a cfg file the launcher is allowed to author inside the write
     * game dir, refusing the retail leaves listed in
     * {@link EZ2Profile#RETAIL_CFG_LEAVES}.
     *
     * <p>The write game dir is the one place the launcher and the engine both
     * write, and the retail chapter/skill cfgs in it are the player's progress
     * and settings. Routing every authored cfg through here makes "never clobber
     * a retail leaf" an enforced invariant instead of a convention, so a future
     * overlay feature cannot quietly overwrite chapter1.cfg.
     */
    public static File writeGameCfg(Context ctx, String fileName) {
        for (String leaf : EZ2Profile.RETAIL_CFG_LEAVES) {
            if (leaf.equalsIgnoreCase(fileName)) {
                throw new IllegalArgumentException(
                        "refusing to author retail cfg " + fileName
                                + " — it belongs to the player, not the launcher");
            }
        }
        return new File(new File(writeGameDir(ctx), "cfg"), fileName);
    }

    /**
     * Resolves the mod source dir inside the shared depot, accepting either
     * Windows or Linux casing (SteamDB: EntropyZero2 vs entropyzero2).
     */
    public static File modSourceDir(File sharedRoot) {
        File win = new File(sharedRoot, EZ2Profile.GAME_DIR_WINDOWS);
        if (new File(win, "gameinfo.txt").isFile()) {
            return win;
        }
        File linux = new File(sharedRoot, EZ2Profile.GAME_DIR_LINUX);
        if (new File(linux, "gameinfo.txt").isFile()) {
            return linux;
        }
        return win; // default for error messages
    }

    public static void rememberContentRoot(Context ctx, String treeUri) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CONTENT_ROOT, treeUri).apply();
    }

    public static String rememberedContentRoot(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CONTENT_ROOT, null);
    }
}
