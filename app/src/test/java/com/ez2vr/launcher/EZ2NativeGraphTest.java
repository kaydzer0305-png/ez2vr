package com.ez2vr.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Locks down {@link EZ2NativeGraph}, the parse and the completeness check.
 *
 * <p>This is the logic behind the launcher's native gate, and the gate is the
 * only thing standing between a half-staged engine build and a Quest user
 * staring at an UnsatisfiedLinkError with no way to read a log. Each test names
 * the on-device failure it prevents.
 */
public class EZ2NativeGraphTest {

    private static final String GRAPH =
            "# ez2vr native runtime graph\n"
                    + "\n"
                    + "libengine.so\n"
                    + "liblauncher.so   # trailing comment\n"
                    + "\n"
                    + "# a section\n"
                    + "libserver.so\n"
                    + "libSDL2.so\n"
                    + "libc++_shared.so\n";

    @Test
    public void parsesEveryLibraryAndIgnoresProse() {
        List<String> libs = EZ2NativeGraph.parse(GRAPH);
        assertEquals(Arrays.asList(
                "libengine.so", "liblauncher.so", "libserver.so", "libSDL2.so",
                "libc++_shared.so"), libs);
    }

    /**
     * A manifest that repeated a name would stage the same library twice and
     * make the count in the failure message a lie.
     */
    @Test
    public void dropsDuplicates() {
        List<String> libs = EZ2NativeGraph.parse("libengine.so\nlibengine.so\n");
        assertEquals(Collections.singletonList("libengine.so"), libs);
    }

    @Test
    public void keepsFileOrder() {
        // The graph doubles as a human-readable inventory, so order is part of
        // the contract even though callers compare it as a set.
        List<String> libs = EZ2NativeGraph.parse("libb.so\nliba.so\n");
        assertEquals(Arrays.asList("libb.so", "liba.so"), libs);
    }

    @Test
    public void emptyGraphYieldsNoLibraries() {
        assertTrue(EZ2NativeGraph.parse("").isEmpty());
        assertTrue(EZ2NativeGraph.parse("# only comments\n\n").isEmpty());
        assertTrue(EZ2NativeGraph.parse((String) null).isEmpty());
    }

    /**
     * Lenient parse drops a line that is not a library name. That is the wrong
     * default for the on-device path: a truncated or mangled graph would quietly
     * shrink the set and the launcher would then pass a build that is missing
     * libraries, which is the exact failure the gate exists to catch.
     */
    @Test
    public void strictParseRefusesANonLibraryLine() {
        try {
            EZ2NativeGraph.parseStrict("libengine.so\nlibc++_s\nlibserver.so\n");
            fail("expected the malformed line to be rejected");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("line 2"));
            assertTrue(e.getMessage(), e.getMessage().contains("libc++_s"));
        }
    }

    @Test
    public void strictParseAgreesWithLenientParseOnAWellFormedGraph() throws IOException {
        assertEquals(EZ2NativeGraph.parse(GRAPH), EZ2NativeGraph.parseStrict(GRAPH));
    }

    @Test
    public void readsFromAStreamTheWayTheAssetArrives() throws IOException {
        List<String> libs = EZ2NativeGraph.parse(
                new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)));
        assertEquals(5, libs.size());
        assertTrue(libs.contains("libc++_shared.so"));
    }

    /**
     * The actual on-device failure: the graph shipped, the libs did not. The
     * message has to name the missing libraries, because "engine failed to
     * start" is useless to someone holding a headset.
     */
    @Test
    public void namesTheLibrariesThatAreMissing() {
        List<String> declared = Arrays.asList("libengine.so", "liblauncher.so", "libserver.so");
        List<String> installed = Arrays.asList("libengine.so", "liblauncher.so");
        assertEquals(Collections.singletonList("libserver.so"),
                EZ2NativeGraph.missingFrom(declared, installed));
    }

    @Test
    public void completeSetReportsNothingMissing() {
        List<String> declared = Arrays.asList("libengine.so", "liblauncher.so");
        List<String> installed = Arrays.asList("liblauncher.so", "libengine.so", "libextra.so");
        assertTrue(EZ2NativeGraph.missingFrom(declared, installed).isEmpty());
    }

    /**
     * Nothing installed at all is the launcher-only build. Must report every
     * declared library rather than an empty "missing" set.
     */
    @Test
    public void launcherOnlyBuildReportsEveryLibrary() {
        List<String> declared = Arrays.asList("libengine.so", "liblauncher.so");
        List<String> missing = EZ2NativeGraph.missingFrom(declared, Collections.<String>emptyList());
        assertEquals(declared, missing);
    }

    @Test
    public void nullsAreTolerated() {
        // nativeLibraryDir listing can return null on a weird filesystem; that
        // must read as "nothing installed", not as a crash on the boot path.
        assertEquals(Collections.singletonList("libengine.so"),
                EZ2NativeGraph.missingFrom(Collections.singletonList("libengine.so"), null));
        assertTrue(EZ2NativeGraph.missingFrom(null, Collections.<String>emptyList()).isEmpty());
    }

    /**
     * The shipped graph must agree with the engine build it was written
     * against, and must name liblauncher.so -- the NativeActivity's own
     * android.app.lib_name, so without it nothing loads at all.
     *
     * <p>Read from the source tree rather than the classpath: AGP packages
     * assets into the APK, not onto the unit test classpath, so
     * {@code getResourceAsStream} cannot see this file. That is also the more
     * useful thing to check, since the APK's copy is made from it.
     */
    @Test
    public void theBundledGraphIsUsable() throws IOException {
        java.io.File graph = findBundledGraph();
        assertNotNull("assets/ez2/native/ez2vr.load is missing from the source tree",
                graph);
        List<String> libs = EZ2NativeGraph.parseStrict(readAll(graph));
        assertEquals("graph should declare the 31 libraries the engine needs",
                31, libs.size());
        assertTrue("NativeActivity loads this one by name", libs.contains("liblauncher.so"));
        assertTrue("the engine dlopen()s this one by name", libs.contains("libengine.so"));
        assertTrue("the engine dlopen()s this one by name", libs.contains("libinputsystem.so"));
        assertTrue("dynamically linked libc++ runtime", libs.contains("libc++_shared.so"));
        assertTrue("platform layer", libs.contains("libSDL2.so"));
        assertTrue("OpenXR loader", libs.contains("libopenxr_loader.so"));
        assertTrue("built from a stub source", libs.contains("libhidapi.so"));
        assertTrue("the two game DLLs are the whole point",
                libs.contains("libserver.so") && libs.contains("libclient.so"));
    }

    /**
     * AGP runs unit tests with the module directory as the working directory,
     * but do not rely on that: walk up until the asset path resolves, so the
     * test survives a working-directory change or being run from the repo root.
     */
    private static java.io.File findBundledGraph() {
        java.io.File dir = new java.io.File("").getAbsoluteFile();
        for (int i = 0; i < 4 && dir != null; i++) {
            java.io.File direct =
                    new java.io.File(dir, "app/src/main/assets/ez2/native/ez2vr.load");
            if (direct.isFile()) {
                return direct;
            }
            java.io.File fromModule =
                    new java.io.File(dir, "src/main/assets/ez2/native/ez2vr.load");
            if (fromModule.isFile()) {
                return fromModule;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static String readAll(java.io.File f) throws IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
