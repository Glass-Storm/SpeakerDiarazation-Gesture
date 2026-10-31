package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Extracts the raw sample payload from a canonical 16 kHz mono PCM16 WAV file.
 *
 * <p>The debug page ships a real Cantonese speech clip so the hub's on-device
 * ASR can be exercised without a person speaking. The wire only carries raw
 * little-endian samples, so the container has to be stripped first. The
 * {@code data} chunk is located by walking the RIFF chunk list instead of
 * assuming the usual 44-byte header: a WAV may legitimately carry {@code LIST}
 * or {@code fact} chunks before the samples.
 *
 * <p>Only PCM ({@code fmt} tag 1) at exactly 16 000 Hz, one channel, 16 bits is
 * accepted. Anything else is rejected with an explanatory {@link IOException}
 * so the page never uploads a mis-declared byte stream as speech.
 *
 * <p>Deliberately Android-free, so the parser is unit-testable on a plain JVM.
 */
public final class WavPcm16 {

    /** {@code WAVEFORMATEX.wFormatTag} for uncompressed integer PCM. */
    public static final int FORMAT_PCM = 1;

    /** The one sample rate the ecosys wire contract fixes. */
    public static final int SAMPLE_RATE_HZ = AudioFraming.SAMPLE_RATE_HZ;

    /** The wire is mono. */
    public static final int CHANNELS = 1;

    /** PCM16 samples. */
    public static final int BITS_PER_SAMPLE = 16;

    private static final int RIFF_HEADER_BYTES = 12;
    private static final int CHUNK_HEADER_BYTES = 8;
    private static final int FMT_MIN_BYTES = 16;
    private static final int FMT_CHANNELS_OFFSET = 2;
    private static final int FMT_SAMPLE_RATE_OFFSET = 4;
    private static final int FMT_BITS_PER_SAMPLE_OFFSET = 14;

    private WavPcm16() {
        // Utility class.
    }

    /**
     * Returns the raw PCM bytes of the {@code data} chunk.
     *
     * @throws IOException when the input is not a RIFF/WAVE file, is missing
     *     {@code fmt } or {@code data}, or is not 16 kHz / mono / 16-bit PCM.
     */
    public static byte[] extractPcm(byte[] wav) throws IOException {
        if (wav == null || wav.length < RIFF_HEADER_BYTES) {
            throw new IOException("WAV 檔案太小，無法解析");
        }
        if (!hasTag(wav, 0, "RIFF") || !hasTag(wav, 8, "WAVE")) {
            throw new IOException("不是 RIFF/WAVE 檔案");
        }

        int formatTag = -1;
        int channels = -1;
        int sampleRate = -1;
        int bitsPerSample = -1;
        int dataOffset = -1;
        int dataSize = -1;

        int cursor = RIFF_HEADER_BYTES;
        while (cursor + CHUNK_HEADER_BYTES <= wav.length) {
            String chunkId = new String(wav, cursor, 4, StandardCharsets.US_ASCII);
            long chunkSize = readUint32(wav, cursor + 4);
            int body = cursor + CHUNK_HEADER_BYTES;

            if ("fmt ".equals(chunkId)) {
                if (chunkSize < FMT_MIN_BYTES || body + FMT_MIN_BYTES > wav.length) {
                    throw new IOException("WAV 的 fmt 區塊不完整");
                }
                formatTag = readUint16(wav, body);
                channels = readUint16(wav, body + FMT_CHANNELS_OFFSET);
                sampleRate = (int) readUint32(wav, body + FMT_SAMPLE_RATE_OFFSET);
                bitsPerSample = readUint16(wav, body + FMT_BITS_PER_SAMPLE_OFFSET);
            } else if ("data".equals(chunkId)) {
                long declared = chunkSize;
                // Streaming writers may leave the size at 0xFFFFFFFF or overrun
                // the file; trust whichever is smaller.
                long usable = Math.min(declared, (long) wav.length - body);
                if (usable <= 0L) {
                    throw new IOException("WAV 的 data 區塊是空的");
                }
                if (usable > Integer.MAX_VALUE) {
                    throw new IOException("WAV 的 data 區塊太大，無法一次載入");
                }
                dataOffset = body;
                dataSize = (int) usable;
                break;
            }

            long next = body + chunkSize + (chunkSize & 1L); // chunks are word-aligned
            if (next <= cursor || next > wav.length) {
                break;
            }
            cursor = (int) next;
        }

        if (formatTag < 0) {
            throw new IOException("WAV 缺少 fmt 區塊");
        }
        if (dataOffset < 0) {
            throw new IOException("WAV 缺少 data 區塊");
        }
        if (formatTag != FORMAT_PCM) {
            throw new IOException("WAV 不是 PCM 格式（format tag " + formatTag + "）");
        }
        if (sampleRate != SAMPLE_RATE_HZ || channels != CHANNELS
                || bitsPerSample != BITS_PER_SAMPLE) {
            throw new IOException("WAV 格式不符：需 " + SAMPLE_RATE_HZ + " Hz / 單聲道 / "
                    + BITS_PER_SAMPLE + "-bit PCM，實際為 " + sampleRate + " Hz / "
                    + channels + " 聲道 / " + bitsPerSample + "-bit");
        }

        byte[] pcm = new byte[dataSize];
        System.arraycopy(wav, dataOffset, pcm, 0, dataSize);
        return pcm;
    }

    private static boolean hasTag(byte[] source, int offset, String tag) {
        if (offset + 4 > source.length) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if ((source[offset + i] & 0xFF) != tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int readUint16(byte[] source, int offset) {
        return (source[offset] & 0xFF) | ((source[offset + 1] & 0xFF) << 8);
    }

    private static long readUint32(byte[] source, int offset) {
        return (source[offset] & 0xFFL)
                | ((source[offset + 1] & 0xFFL) << 8)
                | ((source[offset + 2] & 0xFFL) << 16)
                | ((source[offset + 3] & 0xFFL) << 24);
    }
}
