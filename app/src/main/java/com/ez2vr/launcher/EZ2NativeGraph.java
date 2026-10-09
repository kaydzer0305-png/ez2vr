package com.ez2vr.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parser for the native runtime graph, {@code assets/ez2/native/*.load}.
 *
 * <p>The format is intentionally boring: one library name per line, {@code #}
 * comments, blank lines ignored. It is the declaration of every native library
 * the EZ2 engine loads at runtime, and it is the source of truth for both
 * {@code :app:stageEngineLibs} (which copies the set into {@code jniLibs}) and
 * {@link EZ2EngineActivity#verifyNativeGraph} (which checks the set on device).
 *
 * <p><b>Why the launcher parses it at all.</b> The engine does not: it logs
 * {@code SOURCEVR_RUNTIME_GRAPH} and moves on, because it loads libraries by
 * ordinary {@code dlopen} out of the APK's native lib dir. So a graph that merely
 * <i>existed</i> was enough to satisfy the original gate, which meant the gate
 * could not fail. Parsing it here is what makes "a graph is present" mean
 * "this APK carries the engine it claims to".
 *
 * <p>Kept free of Android types so it can be unit tested on the JVM, like
 * {@link EZ2ContentImporter#plan}.
 */
public final class EZ2NativeGraph {

    /** One library name per line, the way {@code libfoo.so} is written. */
    private static final String SUFFIX = ".so";

    private EZ2NativeGraph() {
    }

    /**
     * Parses a graph, preserving file order and dropping duplicates.
     *
     * <p>Order is kept because a graph is also a human-readable inventory; the
     * caller compares it as a set, not a sequence. A line that is not a comment
     * and not blank but does not end in {@code .so} is a typo, not a library, so
     * it is skipped rather than silently becoming an unloadable dependency --
     * but see {@link #parseStrict} for the variant that refuses.
     */
    public static List<String> parse(String text) {
        if (text == null) {
            return Collections.emptyList();
        }
        List<String> libs = new ArrayList<String>();
        for (String raw : text.split("\n")) {
            String line = content(raw);
            if (line.isEmpty() || !line.endsWith(SUFFIX)) {
                continue;
            }
            if (!libs.contains(line)) {
                libs.add(line);
            }
        }
        return libs;
    }

    /**
     * Same as {@link #parse} but reports unparseable lines instead of dropping
     * them, so a malformed graph cannot quietly shrink the set.
     *
     * @return the library names
     * @throws IOException if a non-comment, non-blank line is not a library name
     */
    public static List<String> parseStrict(String text) throws IOException {
        if (text == null) {
            return Collections.emptyList();
        }
        List<String> libs = new ArrayList<String>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = content(lines[i]);
            if (line.isEmpty()) {
                continue;
            }
            if (!line.endsWith(SUFFIX)) {
                throw new IOException("native graph line " + (i + 1)
                        + " is not a library name: \"" + lines[i].trim() + "\"");
            }
            if (!libs.contains(line)) {
                libs.add(line);
            }
        }
        return libs;
    }

    /**
     * One line, stripped of any trailing {@code #} comment and trimmed.
     *
     * <p>Trailing comments have to go: the file is hand-maintained, so somebody
     * will annotate a line, and a name that kept its comment would both fail the
     * {@code .so} check and be looked for in the wrong form.
     */
    private static String content(String rawLine) {
        int hash = rawLine.indexOf('#');
        return (hash < 0 ? rawLine : rawLine.substring(0, hash)).trim();
    }

    /** Reads and parses a graph from a stream, as the asset arrives. */
    public static List<String> parse(InputStream in) throws IOException {
        return parseStrict(readAll(in));
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            r.close();
        }
        return sb.toString();
    }

    /**
     * Names declared by the graph that are absent from {@code installed}.
     *
     * <p>This is the check that gives the gate teeth: a build that staged some
     * of the set, or staged a stale set, reports exactly which libraries the
     * engine will fail to {@code dlopen}.
     */
    public static List<String> missingFrom(List<String> declared, List<String> installed) {
        List<String> missing = new ArrayList<String>();
        if (declared == null) {
            return missing;
        }
        List<String> have = installed == null ? Collections.<String>emptyList() : installed;
        for (String lib : declared) {
            if (!have.contains(lib)) {
                missing.add(lib);
            }
        }
        return missing;
    }
}
