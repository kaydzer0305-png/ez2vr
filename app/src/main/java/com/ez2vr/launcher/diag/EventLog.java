package com.ez2vr.launcher.diag;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Append-only JSON-lines session log in the diagnostics dir, one file per
 * process start ({@code session-<epoch>.jsonl}).
 *
 * <p>One file per PROCESS, not per app: the engine runs in the {@code :engine}
 * process (see AndroidManifest.xml), so the launcher and the engine each open
 * their own session file and a stalled engine is distinguishable from a
 * stalled launcher in the export.
 *
 * <p>Old files are pruned on session start: anything older than 30 days goes,
 * then oldest non-report files until the dir is back under 200 MB. Crash and
 * ANR reports are pruned last so evidence survives pressure.
 *
 * <p>Ported from the EZ1 Quest port's {@code diag/EventLog.java}.
 */
public final class EventLog {
    /** 30 days. */
    public static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    /** 200 MB. */
    public static final long MAX_BYTES = 200L * 1024 * 1024;

    private static final String TAG = "EZ2VR-Diag";

    private static EventLog sInstance;

    private final Context ctx;
    private final long startUptime = SystemClock.uptimeMillis();
    private final File file;
    private boolean ended;
    private int events;

    private EventLog(Context context) {
        ctx = context.getApplicationContext();
        file = new File(DiagnosticsDir.get(ctx),
                "session-" + (System.currentTimeMillis() / 1000) + ".jsonl");
    }

    public static synchronized EventLog get(Context context) {
        if (sInstance == null) {
            sInstance = new EventLog(context);
        }
        return sInstance;
    }

    public File file() {
        return file;
    }

    public void sessionStart(String entryPoint) {
        prune();
        try {
            JSONObject obj = base("session_start");
            putFields(obj, BuildInfo.reportFields(ctx));
            obj.put("entry_point", entryPoint);
            obj.put("process", processName());
            obj.put("pending_crash_reports", countMatching("crash-", ".txt"));
            obj.put("pending_anr_reports", countMatching("anr-", ".txt"));
            write(obj);
        } catch (Exception e) {
            Log.w(TAG, "sessionStart failed", e);
        }
    }

    public synchronized void sessionEnd(boolean cleanShutdown) {
        if (ended) {
            return;
        }
        ended = true;
        try {
            JSONObject obj = base("session_end");
            obj.put("clean_shutdown", cleanShutdown);
            obj.put("duration_ms", SystemClock.uptimeMillis() - startUptime);
            obj.put("events", events);
            write(obj);
        } catch (Exception e) {
            Log.w(TAG, "sessionEnd failed", e);
        }
    }

    /** Log an event with flat key/value pairs. */
    public void event(String name, String... pairs) {
        try {
            JSONObject obj = base(name);
            putFields(obj, pairs);
            write(obj);
        } catch (Exception e) {
            Log.w(TAG, "event " + name + " failed", e);
        }
    }

    private JSONObject base(String name) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("t", System.currentTimeMillis());
        obj.put("uptime_ms", SystemClock.uptimeMillis() - startUptime);
        obj.put("event", name);
        obj.put("build_hash", BuildInfo.buildHash(ctx));
        return obj;
    }

    private static void putFields(JSONObject obj, String[] pairs) throws Exception {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            obj.put(pairs[i], pairs[i + 1]);
        }
    }

    private synchronized void write(JSONObject obj) {
        events++;
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            out.write('\n');
        } catch (Exception e) {
            Log.w(TAG, "write failed", e);
        }
    }

    public int countMatching(String prefix, String suffix) {
        File[] files = DiagnosticsDir.get(ctx).listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File f : files) {
            if (f.getName().startsWith(prefix) && f.getName().endsWith(suffix)) {
                count++;
            }
        }
        return count;
    }

    public void prune() {
        try {
            File[] files = DiagnosticsDir.get(ctx).listFiles();
            if (files == null) {
                return;
            }
            long now = System.currentTimeMillis();
            List<File> survivors = new ArrayList<>();
            long total = 0;
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                if (now - f.lastModified() > MAX_AGE_MS) {
                    total -= f.length();
                    f.delete();
                } else {
                    total += f.length();
                    survivors.add(f);
                }
            }
            if (total <= MAX_BYTES) {
                return;
            }
            // Reports last: evict oldest sessions first.
            File[] ordered = survivors.toArray(new File[0]);
            Arrays.sort(ordered, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    boolean ra = isReport(a);
                    boolean rb = isReport(b);
                    if (ra != rb) {
                        return ra ? 1 : -1;
                    }
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File f : ordered) {
                if (total <= MAX_BYTES) {
                    return;
                }
                long size = f.length();
                if (f.delete()) {
                    total -= size;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "prune failed", e);
        }
    }

    private static boolean isReport(File file) {
        String name = file.getName();
        return name.startsWith("crash-") || name.startsWith("anr-");
    }

    public String crashFreeSummary() {
        File[] files = DiagnosticsDir.get(ctx).listFiles();
        if (files == null) {
            return "no diagnostics yet";
        }
        int sessions = 0;
        int crashes = 0;
        int anrs = 0;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith("session-") && name.endsWith(".jsonl")) {
                sessions++;
            } else if (name.startsWith("crash-") && name.endsWith(".txt")) {
                crashes++;
            } else if (name.startsWith("anr-") && name.endsWith(".txt")) {
                anrs++;
            }
        }
        if (sessions == 0) {
            return "no sessions recorded";
        }
        double free = (1.0d - (crashes / (double) sessions)) * 100.0d;
        return String.format(Locale.US,
                "crash-free sessions: %.1f%% (%d sessions, %d crashes, %d ANRs)",
                free, sessions, crashes, anrs);
    }

    /** Newest first. */
    public File[] allFiles() {
        File[] files = DiagnosticsDir.get(ctx).listFiles();
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return files;
    }

    /**
     * Process name, so a report says whether it came from the launcher or the
     * {@code :engine} process. The distinction matters because they are
     * separate processes with separate lifecycles and separate crash handlers.
     */
    private static String processName() {
        String name = android.app.Application.getProcessName();
        if (name == null || name.isEmpty()) {
            return "?";
        }
        return name.contains(":") ? name.substring(name.indexOf(':')) : "main";
    }
}
