package com.ez2vr.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks down {@link EZ2ContentImporter#plan} discovery.
 *
 * <p>These are the judgement calls a user hits on a real Quest: which folder to
 * point the picker at, and what happens when they point it at the wrong one.
 * Each test names the user-visible failure it prevents.
 */
public class EZ2ContentImporterDiscoveryTest {

    private static final String EZ2_GAMEINFO =
            "\"GameInfo\"\n{\n\t\"game\"\t\t\"Entropy Zero 2\"\n"
                    + "\t\"title\"\t\"ENTROPY: ZERO 2\"\n\t\"developer\"\t\"Cryptic Sea\"\n}\n";

    private static final String EZ1_GAMEINFO =
            "\"GameInfo\"\n{\n\t\"game\"\t\t\"Entropy Zero\"\n"
                    + "\t\"title\"\t\"ENTROPY: ZERO\"\n\t\"developer\"\t\"Cryptic Sea\"\n}\n";

    private static final String HL2_GAMEINFO =
            "\"GameInfo\"\n{\n\t\"game\"\t\t\"Half-Life 2\"\n"
                    + "\t\"title\"\t\"HALF-LIFE 2\"\n\t\"developer\"\t\"Valve\"\n}\n";

    /** steamapps/common holding the canonical Windows install. */
    private static FakeSourceNode steamCommon() {
        return FakeSourceNode.dir("common",
                FakeSourceNode.dir(EZ2Profile.GAME_DIR_WINDOWS,
                        FakeSourceNode.file("gameinfo.txt", EZ2_GAMEINFO)),
                FakeSourceNode.dir("Half-Life 2",
                        FakeSourceNode.file("gameinfo.txt", HL2_GAMEINFO)),
                FakeSourceNode.dir("platform",
                        FakeSourceNode.file("platform.vdf", "p")));
    }

    @Test
    public void canonicalSteamLayoutResolves() {
        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(steamCommon());

        assertTrue("canonical layout should import: " + plan.describeProblems(),
                plan.isComplete());
        assertEquals(EZ2Profile.GAME_DIR_WINDOWS, plan.mod.name());
        assertEquals("Half-Life 2", plan.hl2.name());
        assertEquals("platform", plan.platform.name());
    }

    @Test
    public void discoveryReachesOneLevelDown() {
        FakeSourceNode steamapps = FakeSourceNode.dir("steamapps", steamCommon());

        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(steamapps);

        assertTrue(plan.isComplete());
        assertEquals(EZ2Profile.GAME_DIR_WINDOWS, plan.mod.name());
    }

    @Test
    public void linuxLowercaseModDirResolves() {
        FakeSourceNode root = FakeSourceNode.dir("common",
                FakeSourceNode.dir(EZ2Profile.GAME_DIR_LINUX,
                        FakeSourceNode.file("gameinfo.txt", EZ2_GAMEINFO)),
                FakeSourceNode.dir("hl2",
                        FakeSourceNode.file("gameinfo.txt", HL2_GAMEINFO)),
                FakeSourceNode.dir("platform",
                        FakeSourceNode.file("platform.vdf", "p")));

        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(root);

        assertTrue(plan.describeProblems(), plan.isComplete());
        assertEquals(EZ2Profile.GAME_DIR_LINUX, plan.mod.name());
    }

    @Test
    public void renamedModDirFallsBackToGameInfoContent() {
        FakeSourceNode root = FakeSourceNode.dir("common",
                FakeSourceNode.dir("SomeModFolder",
                        FakeSourceNode.file("gameinfo.txt", EZ2_GAMEINFO)),
                FakeSourceNode.dir("HL2 (win32)",
                        FakeSourceNode.file("gameinfo.txt", HL2_GAMEINFO)),
                FakeSourceNode.dir("platform",
                        FakeSourceNode.file("platform.vdf", "p")));

        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(root);

        assertTrue(plan.describeProblems(), plan.isComplete());
        assertEquals("SomeModFolder", plan.mod.name());
        assertEquals("HL2 (win32)", plan.hl2.name());
    }

    @Test
    public void platformInsideHl2DirIsAccepted() {
        FakeSourceNode root = FakeSourceNode.dir("common",
                FakeSourceNode.dir(EZ2Profile.GAME_DIR_WINDOWS,
                        FakeSourceNode.file("gameinfo.txt", EZ2_GAMEINFO)),
                FakeSourceNode.dir("Half-Life 2",
                        FakeSourceNode.file("gameinfo.txt", HL2_GAMEINFO),
                        FakeSourceNode.dir("platform",
                                FakeSourceNode.file("platform.vdf", "p"))));

        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(root);

        assertTrue(plan.describeProblems(), plan.isComplete());
        assertTrue("platform should be flagged as nested", plan.platformInsideHl2);
    }

    @Test
    public void ez1InstallIsNotMistakenForEz2() {
        FakeSourceNode root = FakeSourceNode.dir("common",
                FakeSourceNode.dir("EntropyZero",
                        FakeSourceNode.file("gameinfo.txt", EZ1_GAMEINFO)),
                FakeSourceNode.dir("Half-Life 2",
                        FakeSourceNode.file("gameinfo.txt", HL2_GAMEINFO)),
                FakeSourceNode.dir("platform",
                        FakeSourceNode.file("platform.vdf", "p")));

        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(root);

        assertFalse("EZ1 must not satisfy the EZ2 requirement", plan.isComplete());
        assertNull(plan.mod);
        assertTrue(plan.describeProblems(),
                plan.describeProblems().contains("Entropy: Zero 2 folder not found"));
    }

    @Test
    public void missingPlatformIsReportedSpecifically() {
        FakeSourceNode root = FakeSourceNode.dir("common",
                FakeSourceNode.dir(EZ2Profile.GAME_DIR_WINDOWS,
                        FakeSourceNode.file("gameinfo.txt", EZ2_GAMEINFO)),
                FakeSourceNode.dir("Half-Life 2",
                        FakeSourceNode.file("gameinfo.txt", HL2_GAMEINFO)));

        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(root);

        assertFalse(plan.isComplete());
        assertNotNull("mod and hl2 were found", plan.mod);
        assertNull(plan.platform);
        assertTrue(plan.describeProblems(),
                plan.describeProblems().contains("platform/ folder not found"));
    }

    @Test
    public void pickingTheModDirItselfTellsTheUserToPickTheParent() {
        FakeSourceNode modDir = FakeSourceNode.dir(EZ2Profile.GAME_DIR_WINDOWS,
                FakeSourceNode.file("gameinfo.txt", EZ2_GAMEINFO));

        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(modDir);

        assertFalse(plan.isComplete());
        assertTrue(plan.describeProblems(),
                plan.describeProblems().contains("Pick its PARENT"));
    }

    @Test
    public void unrelatedFolderExplainsWhatToPick() {
        EZ2ContentImporter.Plan plan =
                EZ2ContentImporter.plan(FakeSourceNode.dir("Documents",
                        FakeSourceNode.file("notes.txt", "hello")));

        assertFalse(plan.isComplete());
        assertNull(plan.mod);
        assertTrue(plan.describeProblems(),
                plan.describeProblems().contains("steamapps/common"));
    }

    @Test
    public void nullRootIsRejectedWithoutThrowing() {
        EZ2ContentImporter.Plan plan = EZ2ContentImporter.plan(null);

        assertFalse(plan.isComplete());
        assertTrue(plan.describeProblems(),
                plan.describeProblems().contains("could not be read"));
    }

    @Test
    public void aFilePickedAsTheRootIsRejected() {
        EZ2ContentImporter.Plan plan =
                EZ2ContentImporter.plan(FakeSourceNode.file("gameinfo.txt", EZ2_GAMEINFO));

        assertFalse(plan.isComplete());
    }
}
