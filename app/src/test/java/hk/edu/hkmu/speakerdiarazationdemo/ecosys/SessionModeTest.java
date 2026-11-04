package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import ecosys.v1.StreamFrame;

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
        assertTrue(SessionMode.DEAF.showsGestureText());
        assertFalse(SessionMode.DEAF.playsGestureAudio());

        assertFalse(SessionMode.HEARING.sendsAudio());
        assertFalse(SessionMode.HEARING.showsSubtitles());
        assertTrue(SessionMode.HEARING.showsGestureText());
        assertTrue(SessionMode.HEARING.playsGestureAudio());
    }

    @Test
    public void onlyHearingModePlaysGestureAudio() {
        assertTrue(SessionMode.HEARING.playsGestureAudio());
        assertFalse(SessionMode.DEAF.playsGestureAudio());
    }

    @Test
    public void deafSessionRendersGestureTextButDropsGestureAudio() {
        assertTrue(consumes(SessionMode.DEAF, StreamFrame.PayloadCase.GESTURE));
        assertFalse(consumes(SessionMode.DEAF, StreamFrame.PayloadCase.TTS_AUDIO));
    }

    @Test
    public void hearingSessionRendersGestureTextAndPlaysGestureAudio() {
        assertTrue(consumes(SessionMode.HEARING, StreamFrame.PayloadCase.GESTURE));
        assertTrue(consumes(SessionMode.HEARING, StreamFrame.PayloadCase.TTS_AUDIO));
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

    /**
     * Mirrors the gates in {@code MainActivity.onFrame}: a {@code GESTURE} frame is
     * rendered only when {@code showsGestureText()} is true, and a {@code TTS_AUDIO}
     * frame is played only when {@code playsGestureAudio()} is true.
     */
    private static boolean consumes(SessionMode mode, StreamFrame.PayloadCase payload) {
        switch (payload) {
            case GESTURE:
                return mode.showsGestureText();
            case TTS_AUDIO:
                return mode.playsGestureAudio();
            default:
                return false;
        }
    }
}
