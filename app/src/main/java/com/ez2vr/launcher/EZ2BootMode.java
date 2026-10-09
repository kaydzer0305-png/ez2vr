package com.ez2vr.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which engine boot path to take.
 *
 * <p>Mirrors the proven EZ1 contract ({@code com.ezquest.engine.BOOT_MODE} in
 * {@code ez1-quest-engine/android/manifest/AndroidManifest.xml:53-58}, read by
 * {@code LauncherActivity.bootMode()}), with one deliberate difference: the
 * manifest meta-data is only the <em>default</em>, and a launcher UI toggle
 * overrides it.
 *
 * <p>That difference is the whole reason this class exists. The engine activity
 * is {@code exported="false"}, so it cannot be started with {@code adb shell am
 * start} and {@code run-as} is refused too — the Stage + Launch button has to be
 * pressed on the headset. Flipping boot mode through the manifest would mean a
 * rebuild and reinstall per experiment, and the open question right now is
 * precisely which path works, so the toggle has to be reachable at runtime.
 *
 * <p>Precedence: user toggle &gt; manifest meta-data &gt; {@link #VR}.
 */
public enum EZ2BootMode {
    /**
     * Immersive path: {@link EZ2EngineActivity} (a {@code NativeActivity}) opens
     * an OpenXR session and renders stereo through the engine's VR slice.
     */
    VR,

    /**
     * Flat path: {@link EZ2FlatEngineActivity} (an {@code SDLActivity}) boots the
     * same engine binaries through {@code LauncherMainAndroid} with no OpenXR
     * involved, presenting to an ordinary 2D surface.
     *
     * <p>This is the validation path. It exists because the VR path currently
     * creates and begins a session and then never leaves
     * {@code XR_SESSION_STATE_READY}, which makes every other part of the port —
     * the EZ2 game DLLs, the Mapbase AI backplane, the prediction changes —
     * unverifiable on device. With the flat path none of that has to wait on the
     * OpenXR question.
     */
    FLAT;

    public static final String META_BOOT_MODE = "com.ez2vr.launcher.BOOT_MODE";

    private static final String PREFS = "ez2vr";
    private static final String KEY_BOOT_MODE = "boot_mode";

    /**
     * Parses a stored or manifest value.
     *
     * <p>Case-insensitive and fail-safe in the same direction as EZ1: only an
     * explicit {@code flat} selects flat, and anything else (including
     * {@code vr}, an empty string, or a typo like {@code flats}) stays on the
     * immersive path rather than silently dropping the user into a mode they did
     * not ask for.
     */
    public static EZ2BootMode parse(String raw) {
        return "flat".equalsIgnoreCase(raw == null ? "" : raw.trim()) ? FLAT : VR;
    }

    /** Canonical token, as written to the manifest and the preference. */
    public String token() {
        return name().toLowerCase(java.util.Locale.US);
    }

    /** The user's toggle, or null when they have never expressed a preference. */
    public static EZ2BootMode userChoice(Context ctx) {
        String stored = prefs(ctx).getString(KEY_BOOT_MODE, null);
        return stored == null ? null : parse(stored);
    }

    /**
     * Records the toggle. Passing null clears it, returning the app to the
     * manifest default.
     */
    public static void setUserChoice(Context ctx, EZ2BootMode mode) {
        SharedPreferences.Editor edit = prefs(ctx).edit();
        if (mode == null) {
            edit.remove(KEY_BOOT_MODE);
        } else {
            edit.putString(KEY_BOOT_MODE, mode.token());
        }
        edit.apply();
    }

    /**
     * The mode to launch in: user toggle first, then the manifest meta-data.
     *
     * <p>The meta-data lookup can throw on a malformed manifest or a restricted
     * profile, so it degrades to {@link #VR} — the mode that was in place before
     * this existed — rather than propagating.
     */
    public static EZ2BootMode resolve(Context ctx) {
        EZ2BootMode chosen = userChoice(ctx);
        if (chosen != null) {
            return chosen;
        }
        try {
            android.content.pm.ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(),
                            android.content.pm.PackageManager.GET_META_DATA);
            if (ai != null && ai.metaData != null) {
                return parse(ai.metaData.getString(META_BOOT_MODE));
            }
        } catch (Exception e) {
            android.util.Log.w("EZ2VR", "boot mode meta-data lookup failed; using "
                    + VR.token(), e);
        }
        return VR;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
