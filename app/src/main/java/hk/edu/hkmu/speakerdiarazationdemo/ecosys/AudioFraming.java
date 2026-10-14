package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wire framing for the {@code audio_pcm16_16k} payload the glasses streams up to
 * the hub: one channel, 16-bit signed little-endian samples, 16 kHz, cut into
 * fixed {@value #FRAME_MS} ms frames.
 *
 * <p>The hub's convention (shared with the necklace's {@code audio.py}) is
 * {@value #SAMPLES_PER_FRAME} samples / {@value #BYTES_PER_FRAME} bytes per
 * frame. Sending any other frame size would desynchronise the hub's speech
 * pipeline, so the size is a hard constant here rather than a caller choice.
 *
 * <p>The downmix and framing math are deliberately Android-free so they are
 * unit-testable on a plain JVM without a device, emulator, or microphone. The
 * stateful {@link Framer} reassembles arbitrary {@code AudioRecord} read chunks
 * (which rarely land on a frame boundary) into exactly-sized frames.
 */
public final class AudioFraming {

    /** The one sample rate the wire contract fixes. */
    public static final int SAMPLE_RATE_HZ = 16_000;

    /** The contract's frame duration, in milliseconds. */
    public static final int FRAME_MS = 20;

    /** Mono samples in one wire frame: 16 kHz &times; 20 ms. */
    public static final int SAMPLES_PER_FRAME = SAMPLE_RATE_HZ * FRAME_MS / 1000; // 320

    /** Bytes in one PCM16 sample. */
    public static final int BYTES_PER_SAMPLE = 2;

    /** The wire is mono. */
    public static final int CHANNELS = 1;

    /** Bytes in one wire frame: 320 samples &times; 2 bytes. */
    public static final int BYTES_PER_FRAME = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE * CHANNELS; // 640

    private AudioFraming() {
        // Utility class.
    }

    /**
     * Downmixes interleaved little-endian PCM16 to mono by averaging the channels.
     *
     * <p>Mono input (or a single-channel request) is returned as a defensive copy,
     * a null input as an empty array, and a truncated trailing sample group is
     * dropped rather than thrown. The capture path is already mono, so this is
     * normally the identity; it exists so a stereo source could never reach the
     * wire unconverted.
     */
    public static byte[] downmixToMono(byte[] interleaved, int channels) {
        if (interleaved == null) {
            return new byte[0];
        }
        if (channels <= 1) {
            return interleaved.clone();
        }
        int frameBytes = channels * BYTES_PER_SAMPLE;
        int frames = interleaved.length / frameBytes;
        byte[] mono = new byte[frames * BYTES_PER_SAMPLE];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int index = (i * channels + c) * BYTES_PER_SAMPLE;
                int low = interleaved[index] & 0xFF;
                int high = interleaved[index + 1];
                sum += (short) (low | (high << 8));
            }
            int average = sum / channels;
            mono[i * BYTES_PER_SAMPLE] = (byte) (average & 0xFF);
            mono[i * BYTES_PER_SAMPLE + 1] = (byte) ((average >> 8) & 0xFF);
        }
        return mono;
    }

    /**
     * Splits a complete buffer into whole {@value #BYTES_PER_FRAME}-byte mono
     * frames, dropping any trailing partial frame. The result is never null.
     */
    public static List<byte[]> frames(byte[] pcm, int channels) {
        byte[] mono = downmixToMono(pcm, channels);
        int count = mono.length / BYTES_PER_FRAME;
        if (count == 0) {
            return Collections.emptyList();
        }
        List<byte[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] frame = new byte[BYTES_PER_FRAME];
            System.arraycopy(mono, i * BYTES_PER_FRAME, frame, 0, BYTES_PER_FRAME);
            out.add(frame);
        }
        return out;
    }

    /** {@link #frames(byte[], int)} for the mono wire format. */
    public static List<byte[]> frames(byte[] pcm) {
        return frames(pcm, CHANNELS);
    }

    /**
     * A streaming framer: feed it any-sized chunks, receive back only the complete
     * {@value #BYTES_PER_FRAME}-byte frames, with the short trailing remainder held
     * until the next chunk completes it.
     *
     * <p>Not thread-safe by design; a capture loop owns one instance from a single
     * thread.
     */
    public static final class Framer {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        /**
         * Appends {@code chunk} (downmixed to mono when {@code channels > 1}) and
         * returns every frame that is now complete, in order. A null or empty chunk
         * is a no-op.
         */
        public List<byte[]> add(byte[] chunk, int channels) {
            if (chunk != null && chunk.length > 0) {
                byte[] mono = downmixToMono(chunk, channels);
                buffer.write(mono, 0, mono.length);
            }
            byte[] pending = buffer.toByteArray();
            int count = pending.length / BYTES_PER_FRAME;
            List<byte[]> out;
            if (count == 0) {
                out = Collections.emptyList();
            } else {
                out = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    byte[] frame = new byte[BYTES_PER_FRAME];
                    System.arraycopy(pending, i * BYTES_PER_FRAME, frame, 0, BYTES_PER_FRAME);
                    out.add(frame);
                }
            }
            int consumed = count * BYTES_PER_FRAME;
            buffer.reset();
            if (consumed < pending.length) {
                buffer.write(pending, consumed, pending.length - consumed);
            }
            return out;
        }

        /** {@link #add(byte[], int)} for the mono wire format. */
        public List<byte[]> add(byte[] chunk) {
            return add(chunk, CHANNELS);
        }

        /** Bytes held back waiting for the next chunk to complete a frame. */
        public int bufferedBytes() {
            return buffer.size();
        }

        /** Discards any buffered remainder, e.g. when a new capture starts. */
        public void reset() {
            buffer.reset();
        }
    }
}
