package com.ez2vr.launcher;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stages the launcher's own VR overlay + layer files into the shared depot.
 *
 * <p>SourceVR's ProfileAssetStage stages content-addressed blob manifests
 * (overlay/&lt;profile&gt;.manifest, vr/&lt;layer&gt;.manifest, native/&lt;profile&gt;.load)
 * with sha256 markers. This launcher ships only its OWN VR overlay files
 * (default cfgs, vignette/material helpers you author yourself) — never
 * SourceVR's blobs — and copies them idempotently.
 *
 * <p>VR layers reused by name for familiarity (hl2vr-style), but the content
 * must be your own or compatibly licensed.
 *
 * <p><b>Stamping.</b> Every layer directory gets a {@code .ez2vr_stamp} file
 * holding a sha256 over that layer's bundled assets. Two consequences that the
 * marker-file design has to get right:
 *
 * <ul>
 *   <li>A layer that ships NO files still gets a stamp and its destination
 *       directory is still created. An empty-but-mounted layer is legitimate:
 *       it appears in the engine's {@code EZ2_VR_CONTENT_PATH} and contributes
 *       nothing. Skipping the stamp for empty layers would leave
 *       {@link #isCurrent} permanently false and the launch button dead.
 *   <li>The stamp is content-derived, not a bare flag, so an APK update that
 *       changes an overlay cfg invalidates it and the next launch re-stages.
 *       A plain flag would report "staged" forever and silently run last
 *       version's cfgs.
 * </ul>
 */
public final class EZ2AssetStage {
    /** Layers mounted as VR_CONTENT_PATH. Same names SourceVR uses for EZ1's
     * default branch (ProfileAssetStage.java:310), content is yours. */
    public static final List<String> VR_LAYERS =
            Collections.unmodifiableList(Arrays.asList("hl2bodyhla", "hl2bodyvr", "hl2vr"));

    /** Marker written at each layer ROOT only — never into nested dirs, which
     * are on the engine's game search path. */
    private static final String STAMP_NAME = ".ez2vr_stamp";

    /** Bumped whenever the stamped content changes shape, so a stamp written
     * by an older launcher is treated as stale rather than compared. */
    private static final String STAMP_PREFIX = "ez2vr-stage-1 ";

    private static final int BUF = 8192;

    /**
     * Expected stamps, memoised per process.
     *
     * <p>{@link #isCurrent} is called from the launcher's {@code onResume}, on
     * the main thread, and hashing re-reads every bundled asset byte to do it.
     * That is invisible while the overlay is three small cfgs, but the VR
     * layers are about to ship materials, and a full re-hash of them on every
     * resume is a jank-to-ANR source on a Quest — the {@code AnrWatchdog} would
     * faithfully report a stall this class caused itself.
     *
     * <p>Memoising is safe rather than merely convenient: the digest is derived
     * from assets packaged inside the APK, and those cannot change while the
     * process lives. A new APK means a new process, which means a fresh cache.
     * Only the ASSET-side stamp is cached; the on-disk stamp is still read
     * every call, so a layer staged by an older APK still reports stale.
     */
    private static final Map<String, String> EXPECTED_STAMPS = new ConcurrentHashMap<>();

    private EZ2AssetStage() {}

    /** One staged tree: an assets/ subtree and the depot dir it expands into. */
    private static final class Layer {
        final String assetDir;
        final File dest;

        Layer(String assetDir, File dest) {
            this.assetDir = assetDir;
            this.dest = dest;
        }
    }

    private static List<Layer> layers(Context ctx) {
        List<Layer> out = new ArrayList<>();
        out.add(new Layer("ez2/overlay", EZ2ContentPaths.overlayRoot(ctx)));
        for (String layer : VR_LAYERS) {
            out.add(new Layer("ez2/vr/" + layer, EZ2ContentPaths.vrLayerRoot(ctx, layer)));
        }
        return out;
    }

    /**
     * True when every layer's on-disk stamp matches the assets bundled in the
     * CURRENT APK. Drives the "Ready" state in the launcher and the gate in
     * {@link EZ2EngineActivity}.
     */
    public static boolean isCurrent(Context ctx) {
        AssetManager assets = ctx.getAssets();
        for (Layer layer : layers(ctx)) {
            File stamp = new File(layer.dest, STAMP_NAME);
            if (!stamp.isFile()) {
                return false;
            }
            String expected;
            try {
                expected = expectedStamp(assets, layer.assetDir);
            } catch (RuntimeException e) {
                return false;
            }
            try {
                if (!expected.equals(readStamp(stamp))) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Copies every layer's bundled files into its depot dir and rewrites the
     * stamps. Idempotent: safe to call on every launch attempt.
     *
     * <p>Always creates each layer's destination directory and stamp, including
     * for layers that ship no files, so {@link #isCurrent} can reach true.
     */
    public static void stage(Context ctx) throws IOException {
        AssetManager assets = ctx.getAssets();
        for (Layer layer : layers(ctx)) {
            String stamp = expectedStamp(assets, layer.assetDir);
            copyTree(assets, layer.assetDir, layer.dest);
            writeStamp(new File(layer.dest, STAMP_NAME), stamp);
        }
    }

    /** Memoised {@link #computeStamp}; see {@link #EXPECTED_STAMPS}. */
    private static String expectedStamp(AssetManager assets, String assetDir) {
        String cached = EXPECTED_STAMPS.get(assetDir);
        if (cached != null) {
            return cached;
        }
        String computed = computeStamp(assets, assetDir);
        EXPECTED_STAMPS.put(assetDir, computed);
        return computed;
    }

    // ---- native load graph -------------------------------------------------

    private static final String NATIVE_ASSET_DIR = "ez2/native";

    /**
     * Copies the bundled {@code .load} manifest(s) out of the APK and returns
     * the staged file, or null when the build ships no native graph.
     *
     * <p>Separate from {@link #stage} on purpose: the overlay/VR layers are
     * content and belong under a stamp so {@link #isCurrent} can tell when an
     * APK update invalidated them, whereas the load graph is a build artifact
     * whose identity is the APK's, and gating the engine on a stale-but-present
     * graph would be worse than just re-copying it. The engine cannot read the
     * manifest out of assets either — {@code SOURCEVR_RUNTIME_GRAPH} is a
     * filesystem path.
     *
     * <p>Idempotent, and safe to call on every launch attempt.
     */
    public static File stageNativeGraph(Context ctx) throws IOException {
        AssetManager assets = ctx.getAssets();
        List<String> names = children(assets, NATIVE_ASSET_DIR);
        File staged = null;
        File destDir = EZ2ContentPaths.nativeGraphDir(ctx);
        for (String name : names) {
            if (!name.endsWith(".load")) {
                continue;
            }
            if (!destDir.isDirectory() && !destDir.mkdirs() && !destDir.isDirectory()) {
                throw new IOException("cannot create " + destDir);
            }
            File out = new File(destDir, name);
            copyFile(assets, NATIVE_ASSET_DIR + "/" + name, out);
            staged = out;
        }
        return staged;
    }

    // ---- assets traversal -------------------------------------------------

    /**
     * Lists an assets dir, treating "absent" and "unreadable" alike: an asset
     * subtree that cannot be listed stages as empty. AGP-packaged assets always
     * list, so this only absorbs the case of a layer with no bundled files yet.
     */
    private static List<String> children(AssetManager assets, String assetDir) {
        String[] names;
        try {
            names = assets.list(assetDir);
        } catch (IOException e) {
            return Collections.emptyList();
        }
        if (names == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(names.length);
        out.addAll(Arrays.asList(names));
        return out;
    }

    private static boolean isSubdir(AssetManager assets, String assetDir) {
        return !children(assets, assetDir).isEmpty();
    }

    // ---- stamping ---------------------------------------------------------

    /**
     * sha256 over the sorted (name, kind, bytes) of every entry under assetDir.
     * Sorting matters: AssetManager listing order is not guaranteed stable
     * across launches, and an unsorted walk would invalidate the stamp at random.
     */
    private static String computeStamp(AssetManager assets, String assetDir) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        hashTree(assets, assetDir, md);
        return STAMP_PREFIX + hex(md.digest());
    }

    private static void hashTree(AssetManager assets, String assetDir, MessageDigest md) {
        List<String> names = children(assets, assetDir);
        Collections.sort(names);
        for (String name : names) {
            String src = assetDir + "/" + name;
            md.update(name.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            if (isSubdir(assets, src)) {
                md.update((byte) 'D');
                hashTree(assets, src, md);
            } else {
                md.update((byte) 'F');
                try (InputStream in = assets.open(src)) {
                    byte[] buf = new byte[BUF];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        md.update(buf, 0, n);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("cannot hash asset " + src, e);
                }
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static String readStamp(File stamp) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(stamp)) {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    private static void writeStamp(File stamp, String value) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(stamp, false)) {
            fos.write(value.getBytes(StandardCharsets.UTF_8));
            fos.write('\n');
            fos.flush();
            fos.getFD().sync();
        }
    }

    // ---- copying ----------------------------------------------------------

    private static void copyTree(AssetManager assets, String assetDir, File dest)
            throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs() && !dest.isDirectory()) {
            throw new IOException("cannot create " + dest);
        }
        List<String> names = children(assets, assetDir);
        Collections.sort(names);
        for (String name : names) {
            String src = assetDir + "/" + name;
            File out = new File(dest, name);
            if (isSubdir(assets, src)) {
                copyTree(assets, src, out);
            } else {
                copyFile(assets, src, out);
            }
        }
    }

    private static void copyFile(AssetManager assets, String assetDir, File dest)
            throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        // Rename-then-delete: a cfg is never observed half-written, so an
        // interrupted stage cannot leave a truncated config on the game path.
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try (InputStream in = assets.open(assetDir);
             FileOutputStream fos = new FileOutputStream(tmp, false)) {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            fos.getFD().sync();
        }
        if (dest.exists() && !dest.delete()) {
            throw new IOException("cannot replace " + dest);
        }
        if (!tmp.renameTo(dest)) {
            throw new IOException("cannot rename " + tmp + " -> " + dest);
        }
    }
}
