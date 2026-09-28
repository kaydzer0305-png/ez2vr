package com.ez2vr.launcher;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Single-game profile for Entropy: Zero 2.
 *
 * <p>Template counterpart of SourceVR's GameContentProfile/CONTENTComponent pair,
 * reduced to one game. Values grounded in the decompiled SourceVR EZ1 wiring
 * plus public EZ2 facts:
 *
 * <ul>
 *   <li>Steam AppID 1583720 (EZ1 was 714070 in SteamCatalog.java:18)
 *   <li>Game dir {@code EntropyZero2} on Windows / {@code entropyzero2} on Linux
 *       (SteamDB installdir; EZ1 used {@code EntropyZero} in ContentComponent.java:90)
 *   <li>Requires Half-Life 2 (220) only — no EP1/EP2 chain unlike EZ1's
 *       [ENTROPYZERO, EP2, EPISODIC, HL2, PLATFORM] depot list
 *   <li>Mapbase-based (Valve Developer Community), source open on the
 *       entropy-zero org ez2/mapbase branch
 * </ul>
 */
public final class EZ2Profile {
    public static final String PROFILE_ID = "entropyzero2";
    public static final String DISPLAY_NAME = "Entropy : Zero 2";
    public static final int STEAM_APP_ID = 1583720;
    public static final int STEAM_REQUIRED_APP_ID = 220; // Half-Life 2

    /** Canonical dir name shipped by Steam on Windows. Linux uses lowercase. */
    public static final String GAME_DIR_WINDOWS = "EntropyZero2";
    public static final String GAME_DIR_LINUX = "entropyzero2";

    /** Depots/content roots the launcher expects next to the mod dir. */
    public static final List<String> REQUIRED_CONTENT_DIRS =
            Collections.unmodifiableList(Arrays.asList("hl2", "platform"));

    /** Retail cfg leaves that must NOT be overwritten by VR defaults. */
    public static final List<String> RETAIL_CFG_LEAVES =
            Collections.unmodifiableList(Arrays.asList(
                    "chapter1.cfg",
                    "chapter2.cfg",
                    "chapter3.cfg",
                    "chapter4.cfg",
                    "skill.cfg",
                    "skill_manifest.cfg"));

    private EZ2Profile() {}
}
