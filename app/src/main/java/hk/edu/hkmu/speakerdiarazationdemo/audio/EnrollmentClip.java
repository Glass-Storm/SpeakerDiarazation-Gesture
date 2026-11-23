package hk.edu.hkmu.speakerdiarazationdemo.audio;

import java.util.Arrays;

/** Stores only samples actually delivered by the microphone. */
final class EnrollmentClip {
    private final byte[] pcm;
    private int samples;

    EnrollmentClip(int targetSamples) {
        pcm = new byte[targetSamples * Short.BYTES];
    }

    synchronized void append(short[] input, int count) {
        int captured = Math.min(count, pcm.length / Short.BYTES - samples);
        for (int i = 0; i < captured; i++) {
            int offset = (samples + i) * Short.BYTES;
            pcm[offset] = (byte) input[i];
            pcm[offset + 1] = (byte) (input[i] >> 8);
        }
        samples += captured;
    }

    synchronized int sampleCount() {
        return samples;
    }

    synchronized byte[] pcm16() {
        return Arrays.copyOf(pcm, samples * Short.BYTES);
    }
}
