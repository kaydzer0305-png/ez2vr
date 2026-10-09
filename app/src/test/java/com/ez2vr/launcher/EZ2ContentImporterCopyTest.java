package com.ez2vr.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Locks down {@link EZ2ContentImporter#copy}: the depot layout it produces, and
 * the resumability/cancellation behaviour a Quest user depends on when a 50k
 * file import is interrupted.
 */
public class EZ2ContentImporterCopyTest {

    private static final String EZ2_GAMEINFO =
            "\"GameInfo\" { \"game\" \"Entropy Zero 2\" }\n";
    private static final String HL2_GAMEINFO =
            "\"GameInfo\" { \"game\" \"Half-Life 2\" }\n";

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File depot;
    private long ez2InfoBytes;
    private long hl2InfoBytes;

    @Before
    public void setUp() throws IOException {
        depot = tmp.newFolder("depot");
    }

    private EZ2SourceNode source() {
        ez2InfoBytes = EZ2_GAMEINFO.getBytes(StandardCharsets.UTF_8).length;
        hl2InfoBytes = HL2_GAMEINFO.getBytes(StandardCharsets.UTF_8).length;
        return FakeSourceNode.dir("common",
                FakeSourceNode.dir(EZ2Profile.GAME_DIR_WINDOWS,
                        FakeSourceNode.file("gameinfo.txt", EZ2_GAMEINFO),
                        FakeSourceNode.dir("bin",
                                FakeSourceNode.fileOfSize("steam_api64.dll", 10))),
                FakeSourceNode.dir("Half-Life 2",
                        FakeSourceNode.file("gameinfo.txt", HL2_GAMEINFO)),
                FakeSourceNode.dir("platform",
                        FakeSourceNode.fileOfSize("platform.vdf", 5)),
                FakeSourceNode.dir("mapbase",
                        FakeSourceNode.fileOfSize("MAPBASE_CONTENT_USAGE.txt", 1)),
                FakeSourceNode.dir("episodic",
                        FakeSourceNode.file("gameinfo.txt", HL2_GAMEINFO)),
                FakeSourceNode.dir("ep2",
                        FakeSourceNode.file("gameinfo.txt", HL2_GAMEINFO)));
    }

    private long totalSourceBytes() {
        // gameinfo + dll + hl2 gameinfo + platform.vdf + mapbase txt + 2 episode gameinfos
        return ez2InfoBytes + 10 + hl2InfoBytes + 5 + 1 + 2 * hl2InfoBytes;
    }

    private static EZ2ContentImporter.CancelSignal never() {
        return () -> false;
    }

    private static EZ2ContentImporter.CancelSignal cancelledImmediately() {
        return () -> true;
    }

    private void assertReadable(File f, String expected) throws IOException {
        assertTrue("missing " + f, f.isFile());
        assertEquals(expected, new String(Files.readAllBytes(f.toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void producesTheDepotLayoutPresenceCheckExpects() throws IOException {
        EZ2ContentImporter.CopyStats stats = EZ2ContentImporter.copy(
                EZ2ContentImporter.plan(source()), depot, null, never());

        assertFalse(stats.cancelled);
        assertTrue(stats.isComplete());
        assertEquals(7, stats.filesTotal);
        assertEquals(7, stats.filesCopied);
        assertEquals(0, stats.filesSkipped);
        assertEquals(totalSourceBytes(), stats.bytesCopied);

        assertReadable(new File(depot, EZ2Profile.GAME_DIR_WINDOWS + "/gameinfo.txt"),
                EZ2_GAMEINFO);
        assertEquals(10, new File(depot,
                EZ2Profile.GAME_DIR_WINDOWS + "/bin/steam_api64.dll").length());
        assertReadable(new File(depot, "hl2/gameinfo.txt"), HL2_GAMEINFO);
        assertEquals(5, new File(depot, "platform/platform.vdf").length());
        assertTrue("mapbase depot staged",
                new File(depot, "mapbase/MAPBASE_CONTENT_USAGE.txt").isFile());
        assertReadable(new File(depot, "episodic/gameinfo.txt"), HL2_GAMEINFO);
        assertReadable(new File(depot, "ep2/gameinfo.txt"), HL2_GAMEINFO);
    }

    @Test
    public void reimportSkipsUnchangedFiles() throws IOException {
        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(source());
        EZ2ContentImporter.copy(plan, depot, null, never());

        EZ2ContentImporter.CopyStats second = EZ2ContentImporter.copy(plan, depot, null, never());

        assertEquals("second import should copy nothing", 0, second.filesCopied);
        assertEquals(7, second.filesSkipped);
        assertEquals(0, second.bytesCopied);
    }

    @Test
    public void changedFileOfDifferentLengthIsReimported() throws IOException {
        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(source());
        EZ2ContentImporter.copy(plan, depot, null, never());

        File steamDll = new File(depot, EZ2Profile.GAME_DIR_WINDOWS + "/bin/steam_api64.dll");
        Files.write(steamDll.toPath(), new byte[99]);

        EZ2ContentImporter.CopyStats second = EZ2ContentImporter.copy(plan, depot, null, never());

        assertEquals(1, second.filesCopied);
        assertEquals(6, second.filesSkipped);
        assertEquals(10, steamDll.length());
    }

    @Test
    public void zeroLengthSourceIsNotMistakenForAlreadyImported() throws IOException {
        EZ2SourceNode root = FakeSourceNode.dir("common",
                FakeSourceNode.dir(EZ2Profile.GAME_DIR_WINDOWS,
                        FakeSourceNode.zeroLengthFile("gameinfo.txt", EZ2_GAMEINFO)),
                FakeSourceNode.dir("Half-Life 2",
                        FakeSourceNode.zeroLengthFile("gameinfo.txt", HL2_GAMEINFO)),
                FakeSourceNode.dir("platform",
                        FakeSourceNode.fileOfSize("platform.vdf", 5)),
                FakeSourceNode.dir("mapbase",
                        FakeSourceNode.fileOfSize("MAPBASE_CONTENT_USAGE.txt", 1)),
                FakeSourceNode.dir("episodic",
                        FakeSourceNode.file("gameinfo.txt", HL2_GAMEINFO)),
                FakeSourceNode.dir("ep2",
                        FakeSourceNode.file("gameinfo.txt", HL2_GAMEINFO)));
        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(root);

        EZ2ContentImporter.copy(plan, depot, null, never());
        EZ2ContentImporter.CopyStats second = EZ2ContentImporter.copy(plan, depot, null, never());

        assertEquals("a 0-length report must not trigger the skip", 2, second.filesCopied);
        assertReadable(new File(depot, EZ2Profile.GAME_DIR_WINDOWS + "/gameinfo.txt"),
                EZ2_GAMEINFO);
    }

    @Test
    public void cancelStopsCleanlyAndLeavesNoPartFiles() throws IOException {
        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(source());

        EZ2ContentImporter.CopyStats stats = EZ2ContentImporter.copy(
                plan, depot, null, cancelledImmediately());

        assertTrue(stats.cancelled);
        assertFalse(stats.isComplete());
        assertEquals(0, stats.filesCopied);
        assertNoPartFiles();
    }

    @Test
    public void cancelPartWayThroughStillLeavesAResumableDepot() throws IOException {
        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(source());
        // Allow a few polls: one per job before it starts, then one per loop
        // iteration, so at least one file lands before the cancel takes effect.
        int[] polls = {3};
        EZ2ContentImporter.CancelSignal cancel = () -> --polls[0] < 0;

        EZ2ContentImporter.CopyStats stats =
                EZ2ContentImporter.copy(plan, depot, null, cancel);

        assertTrue(stats.cancelled);
        assertTrue("some progress should have been made", stats.filesCopied >= 1);
        assertTrue("cancel should not have finished the import",
                stats.filesCopied < stats.filesTotal);
        assertNoPartFiles();

        EZ2ContentImporter.CopyStats resumed =
                EZ2ContentImporter.copy(plan, depot, null, never());
        assertTrue(resumed.isComplete());
        assertReadable(new File(depot, EZ2Profile.GAME_DIR_WINDOWS + "/gameinfo.txt"),
                EZ2_GAMEINFO);
    }

    @Test
    public void progressCarriesAByteTotalSoTheBarHasADenominator() throws IOException {
        List<Long> totals = new ArrayList<>();
        List<Integer> fileCounts = new ArrayList<>();
        EZ2ContentImporter.ProgressListener listener = new EZ2ContentImporter.ProgressListener() {
            @Override
            public void onScanning() {
            }

            @Override
            public void onCopying(int filesDone, int filesTotal, long bytesDone,
                                  long bytesTotal, String currentName) {
                totals.add(bytesTotal);
                fileCounts.add(filesTotal);
            }
        };

        EZ2ContentImporter.copy(EZ2ContentImporter.plan(source()), depot, listener, never());

        assertFalse("listener should have fired", totals.isEmpty());
        assertTrue("bytesTotal should be the real denominator",
                totals.get(0) == totalSourceBytes());
        assertTrue(fileCounts.get(0) == 7);
    }

    @Test
    public void incompletePlanIsRefusedRatherThanHalfImporting() {
        EZ2ContentImporter.Plan broken =
                EZ2ContentImporter.plan(FakeSourceNode.dir("empty"));

        try {
            EZ2ContentImporter.copy(broken, depot, null, never());
            fail("expected IOException for an incomplete plan");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private void assertNoPartFiles() {
        File[] found = depot.listFiles();
        assertNotNull("depot should exist", found);
        List<String> strays = new ArrayList<>();
        collectStrays(depot, strays);
        assertTrue("temporary files left behind: " + strays, strays.isEmpty());
    }

    private static void collectStrays(File dir, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File kid : kids) {
            if (kid.isDirectory()) {
                collectStrays(kid, out);
            } else if (kid.getName().endsWith(".part") || kid.getName().endsWith(".tmp")) {
                out.add(kid.getPath());
            }
        }
    }
}
