package com.ez2vr.launcher.diag;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Watches for application stalls (ANR precursors) and captures a report.
 *
 * <p>Two independent signals: a token posted to the main thread every poll
 * (late token == main thread stuck), and an optional native frame-loop
 * heartbeat file ({@code diagnostics/heartbeat.bin}, one little-endian long
 * counter written by native code). A report is written at most once per rearm
 * window.
 *
 * <p>The heartbeat half stays dormant until the Mapbase engine build starts
 * writing that file — {@link #heartbeatLateMs()} returns 0 while it is absent,
 * so arming this before the native libs land costs nothing but the main-thread
 * token check.
 *
 * <p>Ported from the EZ1 Quest port's {@code diag/AnrWatchdog.java}.
 */
public final class AnrWatchdog {
    /** Main thread unresponsive past this counts as a stall. */
    public static final long TIMEOUT_MS = 3000;
    private static final long POLL_MS = 500;
    private static final long REARM_MS = 30000;
    private static final String HEARTBEAT_NAME = "heartbeat.bin";
    private static final String TAG = "EZ2VR-ANR";

    private static AnrWatchdog instance;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile long tokenPosted;
    private volatile long tokenSeen;
    private long lastNativeCounter = -1;
    private long lastNativeChangeMs;
    private long lastReportMs;
    private int stallCount;

    private AnrWatchdog(Context context) {
        ctx = context.getApplicationContext();
    }

    public static synchronized AnrWatchdog get(Context context) {
        if (instance == null) {
            instance = new AnrWatchdog(context);
        }
        return instance;
    }

    public int stallCount() {
        return stallCount;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        lastNativeChangeMs = SystemClock.uptimeMillis();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "ez2vr-anr-watchdog");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "watchdog armed (main-thread token + optional native heartbeat)");
    }

    public synchronized void stop() {
        running = false;
    }

    private void loop() {
        while (running) {
            final long token = tokenPosted + 1;
            tokenPosted = token;
            main.post(new Runnable() {
                @Override
                public void run() {
                    tokenSeen = token;
                }
            });
            long postedAt = SystemClock.uptimeMillis();
            sleepQuietly(POLL_MS);
            if (!running) {
                return;
            }
            long javaLateMs = 0;
            if (tokenSeen < token) {
                while (running && tokenSeen < token
                        && SystemClock.uptimeMillis() - postedAt < TIMEOUT_MS) {
                    sleepQuietly(POLL_MS);
                }
                if (running && tokenSeen < token) {
                    javaLateMs = SystemClock.uptimeMillis() - postedAt;
                }
            }
            long nativeLateMs = heartbeatLateMs();
            if (javaLateMs >= TIMEOUT_MS || nativeLateMs >= TIMEOUT_MS) {
                maybeReport(javaLateMs, nativeLateMs);
            }
        }
    }

    /**
     * @return ms since the native counter last moved, 0 when healthy or when
     *         no heartbeat file is wired up yet.
     */
    private long heartbeatLateMs() {
        File file = new File(DiagnosticsDir.get(ctx), HEARTBEAT_NAME);
        if (!file.isFile()) {
            return 0;
        }
        long counter = readCounter(file);
        long now = SystemClock.uptimeMillis();
        if (counter != lastNativeCounter) {
            lastNativeCounter = counter;
            lastNativeChangeMs = now;
            return 0;
        }
        if (counter <= 0) {
            return 0;
        }
        return now - lastNativeChangeMs;
    }

    private long readCounter(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            if (read < buf.length) {
                return lastNativeCounter;
            }
            return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getLong();
        } catch (Exception ignored) {
            return lastNativeCounter;
        }
    }

    private void maybeReport(long javaLateMs, long nativeLateMs) {
        long now = SystemClock.uptimeMillis();
        if (now - lastReportMs < REARM_MS) {
            return;
        }
        lastReportMs = now;
        stallCount++;
        writeReport(javaLateMs, nativeLateMs);
    }

    private void writeReport(long javaLateMs, long nativeLateMs) {
        File file = new File(DiagnosticsDir.get(ctx),
                "anr-" + (System.currentTimeMillis() / 1000)
                        + "-" + BuildInfo.buildHash(ctx) + ".txt");
        StringBuilder sb = new StringBuilder(32768);
        sb.append("=== EZ2 VR ANR report ===\n");
        String[] fields = BuildInfo.reportFields(ctx);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            sb.append(fields[i]).append(": ").append(fields[i + 1]).append('\n');
        }
        sb.append("timeout_ms: ").append(TIMEOUT_MS).append('\n');
        sb.append("java_main_late_ms: ").append(javaLateMs).append('\n');
        sb.append("native_frame_late_ms: ").append(nativeLateMs).append('\n');
        sb.append("native_frame_counter: ");
        sb.append(lastNativeCounter < 0 ? "<not wired>" : String.valueOf(lastNativeCounter));
        sb.append('\n');
        sb.append("stall_index: ").append(stallCount).append('\n');
        sb.append("which: ");
        if (javaLateMs >= TIMEOUT_MS) {
            sb.append(nativeLateMs >= TIMEOUT_MS ? "java-main+native-frameloop" : "java-main");
        } else {
            sb.append("native-frameloop");
        }
        sb.append('\n');
        sb.append("uptime_ms: ").append(SystemClock.uptimeMillis()).append("\n\n");
        appendJavaThreads(sb);
        appendNativeTasks(sb);
        sb.append("=== end ===\n");
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            Log.e(TAG, "could not write " + file, e);
            return;
        }
        Log.w(TAG, "ANR precursor captured: " + file.getAbsolutePath()
                + " java_late=" + javaLateMs + "ms native_late=" + nativeLateMs + "ms");
        EventLog.get(ctx).event("anr_precursor",
                "java_late_ms", String.valueOf(javaLateMs),
                "native_late_ms", String.valueOf(nativeLateMs),
                "file", file.getName());
    }

    private static void appendJavaThreads(StringBuilder sb) {
        sb.append("--- java threads ---\n");
        Thread mainThread = Looper.getMainLooper().getThread();
        try {
            for (Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                sb.append(String.format(Locale.US, "\"%s\" tid=%d state=%s%s\n",
                        thread.getName(), thread.getId(), thread.getState(),
                        thread == mainThread ? "  <-- MAIN" : ""));
                for (StackTraceElement frame : entry.getValue()) {
                    sb.append("    at ").append(frame).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("(unavailable: ").append(t).append(")\n");
        }
    }

    private static void appendNativeTasks(StringBuilder sb) {
        sb.append("\n--- native threads (/proc/self/task) ---\n");
        try {
            File[] tasks = new File("/proc/self/task").listFiles();
            if (tasks == null) {
                sb.append("(unavailable)\n");
                return;
            }
            for (File task : tasks) {
                String comm = readSmall(new File(task, "comm")).trim();
                String stat = readSmall(new File(task, "stat")).trim();
                String wchan = readSmall(new File(task, "wchan")).trim();
                sb.append(String.format(Locale.US, "tid %-7s %-16s state=%s wchan=%s\n",
                        task.getName(), comm, threadState(stat),
                        wchan.isEmpty() ? "?" : wchan));
            }
        } catch (Throwable t) {
            sb.append("(unavailable: ").append(t).append(")\n");
        }
    }

    private static String threadState(String stat) {
        int close = stat.lastIndexOf(')');
        if (close > 0 && close + 2 < stat.length()) {
            return stat.substring(close + 2, close + 3);
        }
        return "?";
    }

    private static String readSmall(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int read = in.read(buf);
            return read <= 0 ? "" : new String(buf, 0, read, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
