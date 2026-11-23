package hk.edu.hkmu.speakerdiarazationdemo.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EnrollmentClipTest {
    @Test
    public void partialReadsAccumulateWithoutPaddingToEightSeconds() {
        EnrollmentClip clip = new EnrollmentClip(EnrollmentRecorder.TARGET_SAMPLES);
        short[] chunk = new short[1024];
        for (int i = 0; i < 124; i++) {
            clip.append(chunk, chunk.length);
        }
        clip.append(chunk, 1023);

        assertEquals(127_999, clip.sampleCount());
        assertEquals(255_998, clip.pcm16().length);

        clip.append(new short[] {(short) 0xFEDC, 1}, 2);
        byte[] pcm = clip.pcm16();
        assertEquals(128_000, clip.sampleCount());
        assertEquals(256_000, pcm.length);
        assertEquals((byte) 0xDC, pcm[pcm.length - 2]);
        assertEquals((byte) 0xFE, pcm[pcm.length - 1]);
    }
}
