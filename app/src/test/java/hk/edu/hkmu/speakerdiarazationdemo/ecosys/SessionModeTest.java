package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the display capabilities the later glasses tasks depend on. Pure JVM: no
 * device, no Robolectric.
 */
public class SessionModeTest {

    @Test
    public void capabilitiesMatchTheRole() {
        assertTrue(SessionMode.DEAF.sendsAudio());
        assertTrue(SessionMode.DEAF.showsSubtitles());
        assertFalse(SessionMode.DEAF.showsGestureText());

        assertFalse(SessionMode.HEARING.sendsAudio());
        assertFalse(SessionMode.HEARING.showsSubtitles());
        assertTrue(SessionMode.HEARING.showsGestureText());
    }

    @Test
    public void onlyHearingModeExpectsGestureAudioFrames() {
        assertTrue(SessionMode.HEARING.expectsGestureAudio());
        assertFalse(SessionMode.DEAF.expectsGestureAudio());
    }

    @Test
    public void bothModesExpectSomeInboundFrames() {
        assertTrue(SessionMode.DEAF.expectsInboundFrames());
        assertTrue(SessionMode.HEARING.expectsInboundFrames());
    }

    @Test
    public void labelsAreTheTwoChoices() {
        assertEquals("我是聽障人士", SessionMode.DEAF.label());
        assertEquals("我是健全人士", SessionMode.HEARING.label());
    }

    @Test
    public void fromStoredNameRoundTripsAndRejectsUnknown() {
        assertEquals(SessionMode.DEAF, SessionMode.fromStoredName("DEAF"));
        assertEquals(SessionMode.HEARING, SessionMode.fromStoredName("HEARING"));
        assertNull(SessionMode.fromStoredName(null));
        assertNull(SessionMode.fromStoredName(""));
        assertNull(SessionMode.fromStoredName("nonsense"));
    }
}
