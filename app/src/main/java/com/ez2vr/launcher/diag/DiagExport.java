package com.ez2vr.launcher.diag;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

/**
 * Packs the diagnostics dir into a zip ({@code report.json} + session logs +
 * crash/ANR reports) and publishes it to Downloads — via MediaStore on current
 * Android, direct file copy as a fallback. Returns a short human-readable
 * status, which the recovery screen shows in a Toast because this APK packages
 * no string resources.
 *
 * <p>MediaStore is the only route that works on Quest 3 (API 32, where scoped
 * storage blocks the direct path); the direct copy remains for API 29.
 *
 * <p>Ported from the EZ1 Quest port's {@code diag/DiagExport.java}.
 */
public final class DiagExport {
    private static final String TAG = "EZ2VR-Diag";

    private DiagExport() {
    }

    public static String exportToDownloads(Context context) {
        File diagDir = DiagnosticsDir.get(context);
        String name = "ez2vr-diag-" + BuildInfo.buildHash(context)
                + "-" + (System.currentTimeMillis() / 1000) + ".zip";
        File staged = new File(context.getCacheDir(), name);
        try {
            long bytes = writeZip(context, diagDir, staged);
            if (bytes <= 0) {
                staged.delete();
                return "Nothing to export yet — no diagnostics recorded.";
            }
            String published = publishViaMediaStore(context, staged, name);
            if (published == null) {
                published = publishDirect(staged, name);
            }
            staged.delete();
            if (published == null) {
                return "Export failed: could not write to Downloads.";
            }
            return "Diagnostics exported (" + humanBytes(bytes) + "): " + published;
        } catch (Exception e) {
            Log.e(TAG, "zip failed", e);
            staged.delete();
            return "Export failed while packaging: " + e.getMessage();
        }
    }

    private static long writeZip(Context context, File diagDir, File out) throws Exception {
        EventLog log = EventLog.get(context);
        log.sessionEnd(true);
        long written;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            JSONObject report = new JSONObject();
            String[] fields = BuildInfo.reportFields(context);
            for (int i = 0; i + 1 < fields.length; i += 2) {
                report.put(fields[i], fields[i + 1]);
            }
            report.put("exported_at", System.currentTimeMillis());
            report.put("crash_free", log.crashFreeSummary());
            report.put("anr_stalls_this_session", AnrWatchdog.get(context).stallCount());
            report.put("privacy", "Contains only this app's own diagnostics. "
                    + "No audio, no camera or passthrough imagery, no pose streams, "
                    + "no guardian geometry, no account identifiers, no IP address, "
                    + "nothing about other applications. This app contains no networking code.");
            addBytes(zip, "report.json", report.toString(2).getBytes("UTF-8"));
            File[] files = diagDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String fileName = file.getName();
                    if (file.isFile() && !"heartbeat.bin".equals(fileName)
                            && !fileName.endsWith(".part")) {
                        addFile(zip, "diagnostics/" + fileName, file);
                    }
                }
            }
            zip.finish();
            written = out.length();
        }
        return written;
    }

    private static void addBytes(ZipOutputStream zip, String name, byte[] bytes)
            throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void addFile(ZipOutputStream zip, String name, File file)
            throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                zip.write(buf, 0, read);
            }
        }
        zip.closeEntry();
    }

    private static String publishViaMediaStore(Context context, File staged, String name) {
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put("is_pending", 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            try (OutputStream out = resolver.openOutputStream(uri);
                 FileInputStream in = new FileInputStream(staged)) {
                if (out == null) {
                    return null;
                }
                byte[] buf = new byte[65536];
                int read;
                while ((read = in.read(buf)) > 0) {
                    out.write(buf, 0, read);
                }
            }
            ContentValues done = new ContentValues();
            done.put("is_pending", 0);
            resolver.update(uri, done, null, null);
            return "Downloads/" + name;
        } catch (Exception e) {
            Log.w(TAG, "MediaStore export failed, falling back", e);
            return null;
        }
    }

    private static String publishDirect(File staged, String name) {
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            downloads.mkdirs();
            File dest = new File(downloads, name);
            try (FileInputStream in = new FileInputStream(staged);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                int read;
                while ((read = in.read(buf)) > 0) {
                    out.write(buf, 0, read);
                }
            }
            return dest.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "direct export failed", e);
            return null;
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1048576) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0d);
        }
        if (bytes < 1073741824) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0d);
        }
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0d);
    }
}
