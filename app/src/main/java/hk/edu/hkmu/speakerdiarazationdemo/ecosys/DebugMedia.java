package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

/**
 * Deterministic synthetic media for the DEBUG page.
 *
 * <p>The page proves the glasses &rarr; phone &rarr; processing &rarr; display
 * round trip without a person speaking or showing a hand, so the payloads it
 * uploads must be reproducible: the same call always yields byte-for-byte the
 * same samples, and the still is always {@value #IMAGE_WIDTH}&times;{@value
 * #IMAGE_HEIGHT} pixels.
 *
 * <p>The audio half is deliberately Android-free so it can be unit-tested on a
 * plain JVM. The JPEG encoding needs {@code android.graphics.Bitmap} and lives
 * in the activity instead; only the pure dimension math is kept here.
 */
public final class DebugMedia {

    /** The only sample rate the frozen contract carries. */
    public static final int SAMPLE_RATE_HZ = Pcm16.SAMPLE_RATE_HZ;

    /** The primary test tone, an audible 440 Hz sine. */
    public static final int PRIMARY_TONE_FREQUENCY_HZ = 440;

    /** A second tone so the hub's canned transcript can differ from the first. */
    public static final int SECONDARY_TONE_FREQUENCY_HZ = 660;

    /** Clip length the debug page sends; 16 kHz &times; 2 s &times; 2 bytes = 64 000 bytes. */
    public static final int TONE_DURATION_MS = 2_000;

    /** Peak sample amplitude: loud enough to be obvious, well inside PCM16 range. */
    public static final int TONE_AMPLITUDE = 12_000;

    /** Width of the deterministic test still. */
    public static final int IMAGE_WIDTH = 320;

    /** Height of the deterministic test still. */
    public static final int IMAGE_HEIGHT = 240;

    /** JPEG quality used by the activity's {@code Bitmap.compress}. */
    public static final int JPEG_QUALITY = 80;

    /** Bytes per ARGB_8888 pixel, used for the bitmap-size math and its test. */
    public static final int BYTES_PER_ARGB_PIXEL = 4;

    private DebugMedia() {
        // Utility class.
    }

    /** A 440 Hz, two-second deterministic mono PCM16 clip at 16 kHz. */
    public static byte[] primaryTonePcm16k() {
        return tonePcm16k(PRIMARY_TONE_FREQUENCY_HZ, TONE_DURATION_MS);
    }

    /** A 660 Hz, two-second deterministic mono PCM16 clip at 16 kHz. */
    public static byte[] secondaryTonePcm16k() {
        return tonePcm16k(SECONDARY_TONE_FREQUENCY_HZ, TONE_DURATION_MS);
    }

    /**
     * Generates a deterministic little-endian PCM16 mono sine clip at 16 kHz.
     *
     * <p>A non-positive frequency or duration yields an empty payload rather
     * than throwing: a debug button must never crash the page. The amplitude is
     * clamped to the PCM16 range for the same reason.
     */
    public static byte[] tonePcm16k(int frequencyHz, int durationMs) {
        return tonePcm16k(frequencyHz, durationMs, TONE_AMPLITUDE);
    }

    /** {@link #tonePcm16k(int, int)} with an explicit peak amplitude. */
    public static byte[] tonePcm16k(int frequencyHz, int durationMs, int amplitude) {
        if (frequencyHz <= 0 || durationMs <= 0) {
            return new byte[0];
        }
        int peak = Math.min(Math.abs(amplitude), Short.MAX_VALUE);
        long sampleCount = (long) SAMPLE_RATE_HZ * durationMs / 1_000L;
        if (sampleCount <= 0L) {
            return new byte[0];
        }
        if (sampleCount * Pcm16.BYTES_PER_SAMPLE > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("tone is too long for one frame");
        }
        byte[] pcm = new byte[(int) sampleCount * Pcm16.BYTES_PER_SAMPLE];
        double radiansPerSample = 2.0 * Math.PI * frequencyHz / SAMPLE_RATE_HZ;
        for (int i = 0; i < (int) sampleCount; i++) {
            short sample = (short) Math.round(Math.sin(radiansPerSample * i) * peak);
            pcm[i * Pcm16.BYTES_PER_SAMPLE] = (byte) (sample & 0xFF);
            pcm[i * Pcm16.BYTES_PER_SAMPLE + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return pcm;
    }

    /**
     * Byte size of an {@code ARGB_8888} bitmap buffer, or {@code 0} for a
     * non-positive dimension. Pure math: the activity allocates the real bitmap.
     */
    public static int argbByteCount(int width, int height) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        long bytes = (long) width * height * BYTES_PER_ARGB_PIXEL;
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("bitmap is too large");
        }
        return (int) bytes;
    }

    /** Pixel count of the deterministic test still. */
    public static int imagePixelCount() {
        return IMAGE_WIDTH * IMAGE_HEIGHT;
    }
}
