package com.ez2vr.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * Minimal read-only tree node — the import logic's only view of a source tree.
 *
 * <p>This exists to keep {@link EZ2ContentImporter} free of {@code android.*}.
 * Discovery has to make judgement calls about what a PC Steam install looks
 * like (dir named "Half-Life 2" vs "hl2", the mod one level down from
 * steamapps, {@code gameinfo.txt} as the marker of a real game dir), and those
 * calls are exactly the part worth testing. On the device the tree arrives as
 * SAF {@code DocumentFile}s ({@code DocumentFileNode}); in a plain JVM unit test
 * it arrives as an in-memory map. Both satisfy this interface, so the
 * heuristics under test are the heuristics that ship.
 */
public interface EZ2SourceNode {
    /** Display name; may be null for SAF roots that do not report one. */
    String name();

    boolean isDirectory();

    /** Size in bytes. 0 when unknown. */
    long size();

    /** Child nodes. Never null; empty for files and unreadable dirs. */
    List<EZ2SourceNode> children();

    /** Opens the file for reading. Only valid when {@link #isDirectory()} is false. */
    InputStream open() throws IOException;

    default EZ2SourceNode child(String childName) {
        for (EZ2SourceNode child : children()) {
            if (childName.equalsIgnoreCase(child.name())) {
                return child;
            }
        }
        return null;
    }

    default boolean hasChildFile(String fileName) {
        EZ2SourceNode child = child(fileName);
        return child != null && !child.isDirectory();
    }
}
