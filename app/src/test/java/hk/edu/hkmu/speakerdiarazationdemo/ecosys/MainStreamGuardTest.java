package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The enrollment entry must leave exactly one GLASS session on the hub. */
public class MainStreamGuardTest {

    private int stopCalls;

    private final MainStreamGuard.MainStreamStopper countingStopper = () -> stopCalls++;

    @Test
    public void recordingStreamIsStoppedBeforeEnrollment() {
        boolean stopped = MainStreamGuard.stopBeforeEnrollment(true, true, countingStopper);

        assertTrue(stopped);
        assertEquals(1, stopCalls);
    }

    @Test
    public void openStreamWithoutRecordingIsStillStopped() {
        boolean stopped = MainStreamGuard.stopBeforeEnrollment(false, true, countingStopper);

        assertTrue(stopped);
        assertEquals(1, stopCalls);
    }

    @Test
    public void idleMainScreenDoesNotRequestAStop() {
        boolean stopped = MainStreamGuard.stopBeforeEnrollment(false, false, countingStopper);

        assertFalse(stopped);
        assertEquals(0, stopCalls);
    }
}
