package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the pure PCM16 framing helpers. No device, no Android
 * framework: the byte math is pinned on a plain JVM.
 */
public class Pcm16Test {

    @Test
    public void emptyAndNullPayloadsAreNotPlayable() {
        assertFalse(Pcm16.isPlayable(null));
        assertFalse(Pcm16.isPlayable(new byte[0]));
        assertEquals(0, Pcm16.sampleCount(null));
        assertEquals(0, Pcm16.durationMs(0));
    }

    @Test
    public void oddLengthPayloadIsRejected() {
        assertFalse(Pcm16.isPlayable(new byte[] {0x01}));
        assertFalse(Pcm16.isPlayable(new byte[] {0x01, 0x02, 0x03}));
    }

    @Test
    public void wholeSamplesArePlayable() {
        assertTrue(Pcm16.isPlayable(new byte[] {0x01, 0x02}));
        assertTrue(Pcm16.isPlayable(new byte[640]));
        assertEquals(320, Pcm16.sampleCount(new byte[640]));
    }

    @Test
    public void toShortsDecodesLittleEndianSignedSamples() {
        byte[] pcm = {
                0x00, 0x00, // 0
                0x01, 0x00, // 1
                (byte) 0xFF, 0x7F, // 32767
                0x00, (byte) 0x80 // -32768
        };
        assertArrayEquals(new short[] {0, 1, 32767, -32768}, Pcm16.toShorts(pcm));
    }

    @Test
    public void toShortsDropsTrailingPartialSample() {
        assertArrayEquals(new short[] {1}, Pcm16.toShorts(new byte[] {0x01, 0x00, 0x7F}));
        assertArrayEquals(new short[0], Pcm16.toShorts(null));
    }

    @Test
    public void durationRoundsUp() {
        assertEquals(0L, Pcm16.durationMs(-1));
        assertEquals(63L, Pcm16.durationMs(1000)); // 1000 / 16000 s = 62.5 ms
        assertEquals(1000L, Pcm16.durationMs(16000));
    }
}
