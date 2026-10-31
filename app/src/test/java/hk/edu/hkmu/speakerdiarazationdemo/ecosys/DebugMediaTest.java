package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * Pure-JVM tests for the deterministic synthetic audio and bitmap math the
 * DEBUG page relies on. The real socket and the JPEG encoder are out of scope
 * here; these pin the payload shape a hub actually receives.
 */
public class DebugMediaTest {

    @Test
    public void primaryToneIsTwoSecondsOf16kMonoPcm16() {
        byte[] pcm = DebugMedia.primaryTonePcm16k();

        assertEquals(64_000, pcm.length);
        assertEquals(32_000, Pcm16.sampleCount(pcm));
        assertEquals(2_000L, Pcm16.durationMs(Pcm16.sampleCount(pcm)));
        assertTrue("PCM must be whole 16-bit samples", Pcm16.isPlayable(pcm));
    }

    @Test
    public void toneGenerationIsDeterministic() {
        assertArrayEquals(
                DebugMedia.tonePcm16k(440, 2_000),
                DebugMedia.tonePcm16k(440, 2_000));
        assertArrayEquals(
                DebugMedia.primaryTonePcm16k(),
                DebugMedia.tonePcm16k(DebugMedia.PRIMARY_TONE_FREQUENCY_HZ,
                        DebugMedia.TONE_DURATION_MS));
    }

    @Test
    public void toneIsAudibleAndStaysInsidePcm16Range() {
        short[] samples = Pcm16.toShorts(DebugMedia.primaryTonePcm16k());

        int peak = 0;
        for (short sample : samples) {
            peak = Math.max(peak, Math.abs((int) sample));
        }
        assertTrue("a sine tone must not be silent", peak > DebugMedia.TONE_AMPLITUDE / 2);
        assertTrue("amplitude must stay inside PCM16", peak <= DebugMedia.TONE_AMPLITUDE);
    }

    @Test
    public void differentFrequenciesProduceDifferentClips() {
        byte[] primary = DebugMedia.primaryTonePcm16k();
        byte[] secondary = DebugMedia.secondaryTonePcm16k();

        assertEquals(primary.length, secondary.length);
        assertFalse("the two debug tones must differ so the hub can tell them apart",
                Arrays.equals(primary, secondary));
    }

    @Test
    public void invalidToneInputYieldsEmptyPayloadInsteadOfThrowing() {
        assertEquals(0, DebugMedia.tonePcm16k(0, 2_000).length);
        assertEquals(0, DebugMedia.tonePcm16k(440, 0).length);
        assertEquals(0, DebugMedia.tonePcm16k(-440, -1).length);
    }

    @Test
    public void argbByteCountMatchesTestStillDimensions() {
        assertEquals(320 * 240 * 4, DebugMedia.argbByteCount(320, 240));
        assertEquals(320 * 240, DebugMedia.imagePixelCount());
        assertEquals(0, DebugMedia.argbByteCount(0, 240));
        assertEquals(0, DebugMedia.argbByteCount(320, -1));
    }
}
