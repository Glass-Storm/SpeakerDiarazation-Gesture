package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

/**
 * Framing helpers for the raw little-endian PCM16 mono @ 16 kHz audio the hub
 * sends in {@code ecosys.v1.TtsAudio.pcm16_16k}.
 *
 * <p>The wire contract fixes the sample format: one channel, 16-bit signed
 * samples, 16 kHz. These helpers turn the opaque byte payload into the sample
 * count / duration / {@code short[]} an {@code AudioTrack} needs, and reject
 * empty or odd-length payloads so playback never hands a malformed buffer to the
 * platform.
 *
 * <p>Deliberately Android-free, so the framing math is unit-testable on a plain
 * JVM without a device or emulator.
 */
public final class Pcm16 {

    /** The one sample rate the hub ever sends, per the frozen contract. */
    public static final int SAMPLE_RATE_HZ = 16_000;

    /** Bytes per mono PCM16 sample. */
    public static final int BYTES_PER_SAMPLE = 2;

    private Pcm16() {
        // Utility class.
    }

    /** Number of whole mono samples in {@code pcm}; never negative. */
    public static int sampleCount(byte[] pcm) {
        return pcm == null ? 0 : pcm.length / BYTES_PER_SAMPLE;
    }

    /**
     * A payload is playable when it is non-null, holds at least one whole 16-bit
     * sample, and has no trailing half-sample. Empty and odd-length payloads are
     * rejected.
     */
    public static boolean isPlayable(byte[] pcm) {
        return pcm != null
                && pcm.length >= BYTES_PER_SAMPLE
                && (pcm.length % BYTES_PER_SAMPLE) == 0;
    }

    /**
     * Little-endian {@code byte[]} &rarr; signed {@code short[]}. A null input
     * yields an empty array; an odd trailing byte is dropped rather than throwing.
     */
    public static short[] toShorts(byte[] pcm) {
        if (pcm == null) {
            return new short[0];
        }
        int count = pcm.length / BYTES_PER_SAMPLE;
        short[] samples = new short[count];
        for (int i = 0; i < count; i++) {
            int low = pcm[i * BYTES_PER_SAMPLE] & 0xFF;
            int high = pcm[i * BYTES_PER_SAMPLE + 1] & 0xFF;
            samples[i] = (short) (low | (high << 8));
        }
        return samples;
    }

    /** Round-up duration of {@code sampleCount} mono samples, in milliseconds. */
    public static long durationMs(int sampleCount) {
        if (sampleCount <= 0) {
            return 0L;
        }
        return (sampleCount * 1000L + SAMPLE_RATE_HZ - 1) / SAMPLE_RATE_HZ;
    }
}
