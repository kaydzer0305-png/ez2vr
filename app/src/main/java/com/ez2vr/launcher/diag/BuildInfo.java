package com.ez2vr.launcher.diag;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

/**
 * Build + device identity stamped into every report.
 *
 * <p>Build identity comes from the optional {@code assets/ez2vr-build.properties}
 * file (keys {@code build_hash}, {@code native_pin}), falling back to the
 * package version. {@code native_pin} matters more here than it did in EZ1:
 * the engine is a from-source Mapbase fork, so a report has to name which
 * commit of that fork produced the crash.
 *
 * <p>Ported from the EZ1 Quest port's {@code diag/BuildInfo.java}.
 */
public final class BuildInfo {
    private static final String ASSET = "ez2vr-build.properties";
    private static final String TAG = "EZ2VR-Diag";

    private static boolean sLoaded;
    private static String sVersionName = "unknown";
    private static long sVersionCode;
    private static String sBuildHash = "dev";
    private static String sNativePin = "none";

    private BuildInfo() {
    }

    private static synchronized void ensure(Context context) {
        if (sLoaded) {
            return;
        }
        sLoaded = true;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            if (info.versionName != null) {
                sVersionName = info.versionName;
            }
            sVersionCode = info.getLongVersionCode();
        } catch (Exception e) {
            Log.w(TAG, "package info unavailable", e);
        }
        try (InputStream in = context.getAssets().open(ASSET)) {
            Properties props = new Properties();
            props.load(in);
            String hash = props.getProperty("build_hash");
            if (hash != null && !hash.trim().isEmpty()) {
                sBuildHash = hash.trim();
            }
            String pin = props.getProperty("native_pin");
            if (pin != null && !pin.trim().isEmpty()) {
                sNativePin = pin.trim();
            }
        } catch (Exception ignored) {
            // Optional asset; "dev" + package version is a fine stamp.
        }
    }

    public static String versionName(Context context) {
        ensure(context);
        return sVersionName;
    }

    public static String buildHash(Context context) {
        ensure(context);
        return sBuildHash;
    }

    public static String versionLine(Context context) {
        ensure(context);
        return "v" + sVersionName + " (" + sVersionCode + ") build " + sBuildHash;
    }

    public static void logBanner(Context context) {
        ensure(context);
        Log.i(TAG, "EZ2 VR " + versionLine(context)
                + " native=" + sNativePin
                + " pkg=" + context.getPackageName()
                + " device=" + Build.MODEL + "/" + Build.DEVICE
                + " os=" + Build.VERSION.RELEASE
                + " abi=" + firstAbi());
    }

    /** Flat key/value pairs for report headers. */
    public static String[] reportFields(Context context) {
        ensure(context);
        return new String[]{
            "app_version", sVersionName,
            "app_version_code", String.valueOf(sVersionCode),
            "build_hash", sBuildHash,
            "native_pin", sNativePin,
            "package", context.getPackageName(),
            "headset_model", Build.MODEL,
            "headset_device", Build.DEVICE,
            "os_release", Build.VERSION.RELEASE,
            "ro_vros_build_version", systemProperty("ro.vros.build.version"),
            "abi", firstAbi(),
        };
    }

    private static String firstAbi() {
        try {
            if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
                return Build.SUPPORTED_ABIS[0];
            }
        } catch (Exception ignored) {
        }
        return "?";
    }

    public static String systemProperty(String name) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ProcessBuilder("/system/bin/getprop", name)
                        .redirectErrorStream(true).start().getInputStream()))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
