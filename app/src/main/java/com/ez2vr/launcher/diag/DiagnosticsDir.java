package com.ez2vr.launcher.diag;

import android.content.Context;
import java.io.File;

/**
 * The {@code diagnostics/} directory (created on demand): session logs,
 * crash/ANR reports, and the optional native frame-loop heartbeat file.
 *
 * <p>Sits beside the content depot under {@code getExternalFilesDir()} so a
 * crash report survives the user clearing app data in the launcher UI, and so
 * it can be pulled over adb by hand when export is unavailable.
 */
public final class DiagnosticsDir {
    private DiagnosticsDir() {
    }

    public static File get(Context context) {
        File root = context.getExternalFilesDir(null);
        if (root == null) {
            root = context.getFilesDir();
        }
        File dir = new File(root, "diagnostics");
        dir.mkdirs();
        return dir;
    }
}
