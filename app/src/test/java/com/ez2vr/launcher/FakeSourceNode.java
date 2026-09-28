package com.ez2vr.launcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * In-memory {@link EZ2SourceNode} so {@link EZ2ContentImporter} can be tested on
 * a desktop JVM, exactly as its interface was designed for.
 *
 * <p>Mirrors the two behaviours that matter to the importer: a SAF provider
 * that reports {@code length() == 0} for a real file, and a tree whose
 * {@code gameinfo.txt} decides whether a directory counts as a game.
 */
final class FakeSourceNode implements EZ2SourceNode {
    private final String name;
    /** null means directory. */
    private final byte[] data;
    private final List<EZ2SourceNode> children;
    /** Reported by {@link #size()} when non-null, to mimic a lying provider. */
    private final Long sizeOverride;

    private FakeSourceNode(String name, byte[] data, List<EZ2SourceNode> children,
                           Long sizeOverride) {
        this.name = name;
        this.data = data;
        this.children = children;
        this.sizeOverride = sizeOverride;
    }

    static FakeSourceNode dir(String name, EZ2SourceNode... kids) {
        return new FakeSourceNode(name, null,
                Collections.unmodifiableList(new ArrayList<>(Arrays.asList(kids))), null);
    }

    static FakeSourceNode file(String name, String content) {
        return new FakeSourceNode(name, content.getBytes(StandardCharsets.UTF_8),
                Collections.emptyList(), null);
    }

    /** Synthetic file of a chosen length, for arithmetic on stats. */
    static FakeSourceNode fileOfSize(String name, int size) {
        byte[] bytes = new byte[Math.max(0, size)];
        Arrays.fill(bytes, (byte) 'x');
        return new FakeSourceNode(name, bytes, Collections.emptyList(), null);
    }

    /** File whose {@code length()} reports 0, as some SAF providers do. */
    static FakeSourceNode zeroLengthFile(String name, String content) {
        return new FakeSourceNode(name, content.getBytes(StandardCharsets.UTF_8),
                Collections.emptyList(), 0L);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isDirectory() {
        return data == null;
    }

    @Override
    public long size() {
        if (sizeOverride != null) {
            return sizeOverride;
        }
        return data == null ? 0L : data.length;
    }

    @Override
    public List<EZ2SourceNode> children() {
        return children;
    }

    @Override
    public InputStream open() throws IOException {
        if (data == null) {
            throw new IOException(name() + " is a directory");
        }
        return new ByteArrayInputStream(data);
    }

    @Override
    public String toString() {
        return name;
    }
}
