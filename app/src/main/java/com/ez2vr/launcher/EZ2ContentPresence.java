package com.ez2vr.launcher;

import java.io.File;

/**
 * Content presence check for a PC-Steam copy of EZ2.
 *
 * <p>Counterpart of SourceVR's ContentPresence check
 * (ContentPresence.java:227 — gameDir + "/gameinfo.txt is missing").
 * The user copies these folders from their own Steam install into the
 * shared depot:
 *
 * <pre>
 *   shared/EntropyZero2/gameinfo.txt   (or shared/entropyzero2/ on Linux copies)
 *   shared/hl2/gameinfo.txt
 *   shared/platform/ (optional loose files)
 * </pre>
 */
public final class EZ2ContentPresence {
    public final boolean readyToLaunch;
    public final String problem;

    private EZ2ContentPresence(boolean ready, String problem) {
        this.readyToLaunch = ready;
        this.problem = problem;
    }

    public static EZ2ContentPresence check(File sharedRoot) {
        File modDir = EZ2ContentPaths.modSourceDir(sharedRoot);
        if (!new File(modDir, "gameinfo.txt").isFile()) {
            return new EZ2ContentPresence(false,
                    modDir.getName() + "/gameinfo.txt is missing — copy EntropyZero2 from your Steam install");
        }
        for (String required : EZ2Profile.REQUIRED_CONTENT_DIRS) {
            File dir = new File(sharedRoot, required);
            if (!dir.isDirectory()) {
                return new EZ2ContentPresence(false,
                        required + "/ is missing — copy it from your HL2 Steam install");
            }
        }
        File hl2Info = new File(new File(sharedRoot, "hl2"), "gameinfo.txt");
        if (!hl2Info.isFile()) {
            return new EZ2ContentPresence(false, "hl2/gameinfo.txt is missing");
        }
        return new EZ2ContentPresence(true, null);
    }
}
