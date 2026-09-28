package com.ez2vr.launcher.diag;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Java-layer crash capture. Installs a default uncaught-exception handler
 * that writes a {@code crash-<epoch>-<hash>.txt} report (build stamp, thread,
 * stack trace, memory snapshot) into the diagnostics dir, logs the event,
 * then chains to the previous handler so the process still dies loudly.
 *
 * <p>Must be armed in BOTH processes. The engine host runs in {@code :engine},
 * which is a separate process with its own static state, so arming only in the
 * launcher would leave an engine-side Java fault with no report. The launcher
 * then notices the new report file and shows {@code EZ2RecoveryActivity}.
 *
 * <p>Complements — not replaces — the native signal handler the engine
 * installs ({@code InitCrashHandler} in launcher/android/main.cpp), which
 * covers native faults this layer never sees. {@link #countReports} is what the
 * launcher watches, so both kinds of fault surface the same way.
 *
 * <p>Ported from the EZ1 Quest port's {@code diag/CrashProbe.java}.
 */
public final class CrashProbe {
    private static final String TAG = "EZ2VR-Crash";
    private static boolean armed;

    private CrashProbe() {
    }

    public static synchronized boolean arm(Context context) {
        if (armed) {
            return true;
        }
        final Context appCtx = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    File report = writeReport(appCtx, thread, error);
                    EventLog.get(appCtx).event("crash",
                            "process", String.valueOf(processSuffix()),
                            "thread", thread.getName(),
                            "error", String.valueOf(error),
                            "file", report.getName());
                    Log.e(TAG, "crash captured: " + report.getAbsolutePath(), error);
                } catch (Throwable t) {
                    Log.e(TAG, "crash report failed", t);
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            }
        });
        armed = true;
        Log.i(TAG, "java crash capture armed");
        return true;
    }

    public static boolean isArmed() {
        return armed;
    }

    /**
     * Crash reports waiting in the diagnostics dir. The launcher samples this
     * before starting the engine and again after it returns, so a fault in the
     * {@code :engine} process — which is simply gone by then, with no result
     * code of its own — is still noticed.
     */
    public static int countReports(Context context) {
        return EventLog.get(context).countMatching("crash-", ".txt");
    }

    private static File writeReport(Context context, Thread thread, Throwable error)
            throws Exception {
        File file = new File(DiagnosticsDir.get(context),
                "crash-" + (System.currentTimeMillis() / 1000)
                        + "-" + BuildInfo.buildHash(context) + ".txt");
        StringBuilder sb = new StringBuilder(16384);
        sb.append("=== EZ2 VR crash report ===\n");
        String[] fields = BuildInfo.reportFields(context);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            sb.append(fields[i]).append(": ").append(fields[i + 1]).append('\n');
        }
        sb.append("process: ").append(processSuffix()).append('\n');
        sb.append("thread: ").append(thread.getName())
                .append(" (id=").append(thread.getId()).append(")\n");
        sb.append(memoryLine(context));
        sb.append("\n--- stack ---\n");
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        sb.append(sw).append("\n=== end ===\n");
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        return file;
    }

    private static String processSuffix() {
        String name = android.app.Application.getProcessName();
        if (name == null || name.isEmpty()) {
            return "?";
        }
        return name.contains(":") ? name.substring(name.indexOf(':')) : "main";
    }

    private static String memoryLine(Context context) {
        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return "memory: totalMB=" + (mi.totalMem / 1048576L)
                    + " availMB=" + (mi.availMem / 1048576L)
                    + " lowMemory=" + mi.lowMemory
                    + " maxHeapBytes=" + Runtime.getRuntime().maxMemory() + "\n";
        } catch (Exception e) {
            return "memory: (unavailable: " + e + ")\n";
        }
    }
}
