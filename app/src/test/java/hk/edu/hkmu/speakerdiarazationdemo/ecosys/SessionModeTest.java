package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the role → payload-kind mapping and the two display capabilities the later
 * glasses tasks depend on. Pure JVM: no device, no Robolectric.
 */
public class SessionModeTest {

    @Test
    public void deafModeSendsAudio() {
        assertEquals(PayloadKind.AUDIO, SessionMode.payloadKindFor(SessionMode.DEAF));
        assertEquals(PayloadKind.AUDIO, SessionMode.DEAF.payloadKind());
    }

    @Test
    public void hearingModeSendsImages() {
        assertEquals(PayloadKind.IMAGE, SessionMode.payloadKindFor(SessionMode.HEARING));
        assertEquals(PayloadKind.IMAGE, SessionMode.HEARING.payloadKind());
    }

    @Test
    public void capabilitiesMatchTheRole() {
        assertTrue(SessionMode.DEAF.sendsAudio());
        assertTrue(SessionMode.DEAF.showsSubtitles());
        assertFalse(SessionMode.DEAF.sendsImages());
        assertFalse(SessionMode.DEAF.showsGestureText());

        assertTrue(SessionMode.HEARING.sendsImages());
        assertTrue(SessionMode.HEARING.showsGestureText());
        assertFalse(SessionMode.HEARING.sendsAudio());
        assertFalse(SessionMode.HEARING.showsSubtitles());
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
    public void nullModeHasNoPayloadKind() {
        assertNull(SessionMode.payloadKindFor(null));
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
