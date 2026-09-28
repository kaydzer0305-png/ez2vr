package com.ez2vr.launcher;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SAF {@code DocumentFile} adapter for {@link EZ2SourceNode}.
 *
 * <p>The only Android-aware piece of the import path. Everything above it
 * ({@link EZ2ContentImporter}) stays plain Java so the discovery heuristics can
 * be tested on a desktop JVM.
 */
final class DocumentFileNode implements EZ2SourceNode {
    private final Context ctx;
    private final DocumentFile file;

    DocumentFileNode(Context ctx, DocumentFile file) {
        this.ctx = ctx;
        this.file = file;
    }

    /**
     * Wraps a persisted tree URI, or returns null if the provider no longer
     * grants access (revoked permission, deleted SD card, re-installed app).
     */
    static EZ2SourceNode fromTreeUri(Context ctx, Uri treeUri) {
        if (ctx == null || treeUri == null) {
            return null;
        }
        DocumentFile dir;
        try {
            dir = DocumentFile.fromTreeUri(ctx, treeUri);
        } catch (SecurityException e) {
            return null;
        }
        return (dir == null || !dir.canRead()) ? null : new DocumentFileNode(ctx, dir);
    }

    @Override
    public String name() {
        String n = file.getName();
        return n == null ? "" : n;
    }

    @Override
    public boolean isDirectory() {
        return file.isDirectory();
    }

    @Override
    public long size() {
        return file.length();
    }

    @Override
    public List<EZ2SourceNode> children() {
        DocumentFile[] kids = file.listFiles();
        if (kids == null || kids.length == 0) {
            return Collections.emptyList();
        }
        List<EZ2SourceNode> out = new ArrayList<>(kids.length);
        for (DocumentFile kid : kids) {
            if (kid != null) {
                out.add(new DocumentFileNode(ctx, kid));
            }
        }
        return out;
    }

    @Override
    public InputStream open() throws IOException {
        Uri uri = file.getUri();
        if (uri == null) {
            throw new IOException("no document URI for " + name());
        }
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("cannot open " + name());
        }
        return in;
    }
}
