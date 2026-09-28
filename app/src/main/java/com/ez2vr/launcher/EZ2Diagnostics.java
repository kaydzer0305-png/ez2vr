package com.ez2vr.launcher;

import android.content.Context;
import com.ez2vr.launcher.diag.AnrWatchdog;
import com.ez2vr.launcher.diag.BuildInfo;
import com.ez2vr.launcher.diag.DiagExport;
import com.ez2vr.launcher.diag.DiagnosticsDir;
import com.ez2vr.launcher.diag.EventLog;
import java.io.File;

/**
 * One-line facade over the diagnostics package: session lifecycle, crash
 * capture, watchdog, export.
 *
 * <p>Both processes call {@link #startSession} — the launcher and the
 * {@code :engine} engine host are separate processes, so each opens its own
 * session file and exports them side by side.
 *
 * <p>Ported from the EZ1 Quest port's {@code Diagnostics.java}.
 */
public final class EZ2Diagnostics {
    private EZ2Diagnostics() {
    }

    public static File dir(Context context) {
        return DiagnosticsDir.get(context);
    }

    /**
     * Begin a session: arm crash capture and the ANR watchdog, log the build
     * banner, then write session_start (pruning first).
     */
    public static void startSession(Context context, String entryPoint) {
        try {
            com.ez2vr.launcher.diag.CrashProbe.arm(context);
            AnrWatchdog.get(context).start();
            BuildInfo.logBanner(context);
            EventLog.get(context).sessionStart(entryPoint);
        } catch (Exception e) {
            android.util.Log.w("EZ2VR-Diag", "startSession failed", e);
        }
    }

    public static void endSession(Context context, boolean cleanShutdown) {
        try {
            EventLog.get(context).sessionEnd(cleanShutdown);
        } catch (Exception ignored) {
        }
    }

    public static void event(Context context, String name, String... pairs) {
        try {
            EventLog.get(context).event(name, pairs);
        } catch (Exception ignored) {
        }
    }

    /** Human-readable export status (safe to Toast; no string resources exist). */
    public static String exportStatusMessage(Context context) {
        try {
            return DiagExport.exportToDownloads(context);
        } catch (Exception e) {
            return "Export failed: " + e.getMessage();
        }
    }
}
