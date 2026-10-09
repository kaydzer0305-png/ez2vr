package com.ez2vr.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Locks down {@link EZ2BootMode} parsing.
 *
 * <p>The engine activity is {@code exported="false"}, so the boot mode cannot be
 * changed from adb and the headset button is the only control surface. That makes
 * the failure direction worth pinning: a typo must not drop a user into a boot
 * path they did not ask for.
 */
public class EZ2BootModeTest {

    @Test
    public void flatSelectsFlat() {
        assertEquals(EZ2BootMode.FLAT, EZ2BootMode.parse("flat"));
    }

    @Test
    public void vrSelectsVr() {
        assertEquals(EZ2BootMode.VR, EZ2BootMode.parse("vr"));
    }

    @Test
    public void parsingIsCaseInsensitiveAndTrims() {
        assertEquals(EZ2BootMode.FLAT, EZ2BootMode.parse("FLAT"));
        assertEquals(EZ2BootMode.FLAT, EZ2BootMode.parse("  Flat  "));
        assertEquals(EZ2BootMode.VR, EZ2BootMode.parse("Vr"));
    }

    /**
     * Fail-safe toward immersive, matching EZ1's LauncherActivity, where only an
     * exact "vr" takes the VR branch and everything else falls through to flat.
     * Here the safe direction is the other one: the flat path is the experimental
     * one, so an unrecognized value must not silently select it.
     */
    @Test
    public void unknownValuesStayOnVr() {
        assertEquals(EZ2BootMode.VR, EZ2BootMode.parse(null));
        assertEquals(EZ2BootMode.VR, EZ2BootMode.parse(""));
        assertEquals(EZ2BootMode.VR, EZ2BootMode.parse("   "));
        assertEquals(EZ2BootMode.VR, EZ2BootMode.parse("flats"));
        assertEquals(EZ2BootMode.VR, EZ2BootMode.parse("headset"));
        assertEquals(EZ2BootMode.VR, EZ2BootMode.parse("2d"));
    }

    @Test
    public void tokenRoundTripsThroughParse() {
        for (EZ2BootMode mode : EZ2BootMode.values()) {
            assertEquals(mode, EZ2BootMode.parse(mode.token()));
        }
    }

    @Test
    public void tokensAreLowerCase() {
        assertEquals("vr", EZ2BootMode.VR.token());
        assertEquals("flat", EZ2BootMode.FLAT.token());
    }
}
