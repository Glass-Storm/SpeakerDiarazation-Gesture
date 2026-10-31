package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Pins the WAV container stripping used by the debug page's real-speech button:
 * the {@code data} payload must be found by walking chunks (never by assuming a
 * 44-byte header), and anything that is not 16 kHz mono 16-bit PCM must be
 * rejected instead of uploaded as speech.
 */
public class WavPcm16Test {

    private static final int RATE = 16_000;

    @Test
    public void extractsPayloadFromCanonicalHeader() throws Exception {
        byte[] pcm = ramp(640);
        byte[] wav = wav(1, 1, RATE, 16, pcm, null);

        assertEquals(44 + pcm.length, wav.length);
        assertArrayEquals(pcm, WavPcm16.extractPcm(wav));
    }

    @Test
    public void skipsExtraChunksBeforeData() throws Exception {
        byte[] pcm = ramp(320);
        byte[] listBody = "INFOISFTmockpeer 1.0".getBytes(StandardCharsets.US_ASCII);
        byte[] wav = wav(1, 1, RATE, 16, pcm, chunk("LIST", listBody));

        assertTrue(wav.length > 44 + pcm.length);
        assertArrayEquals(pcm, WavPcm16.extractPcm(wav));
    }

    @Test
    public void handlesListChunkWithOddBodyLength() throws Exception {
        byte[] pcm = ramp(200);
        byte[] wav = wav(1, 1, RATE, 16, pcm, chunk("LIST", new byte[7]));

        assertArrayEquals(pcm, WavPcm16.extractPcm(wav));
    }

    @Test
    public void clampsDataSizeThatOverrunsTheFile() throws Exception {
        byte[] pcm = ramp(100);
        byte[] wav = wav(1, 1, RATE, 16, pcm, null);
        // Streaming writers leave 0xFFFFFFFF in the data size field.
        writeUint32(wav, 40, 0xFFFFFFFFL);

        assertArrayEquals(pcm, WavPcm16.extractPcm(wav));
    }

    @Test
    public void rejectsWrongSampleRate() {
        expectReject(wav(1, 1, 44_100, 16, ramp(100), null), "44100");
    }

    @Test
    public void rejectsStereo() {
        expectReject(wav(1, 2, RATE, 16, ramp(100), null), "2 聲道");
    }

    @Test
    public void rejectsEightBitSamples() {
        expectReject(wav(1, 1, RATE, 8, ramp(100), null), "8-bit");
    }

    @Test
    public void rejectsNonPcmFormat() {
        expectReject(wav(3, 1, RATE, 16, ramp(100), null), "PCM 格式");
    }

    @Test
    public void rejectsMissingDataChunk() {
        expectReject(wav(1, 1, RATE, 16, null, null), "缺少 data");
    }

    @Test
    public void rejectsMissingFmtChunk() {
        byte[] wav = wav(1, 1, RATE, 16, ramp(100), null);
        // Overwrite the "fmt " chunk id so the walker skips it.
        System.arraycopy("junk".getBytes(StandardCharsets.US_ASCII), 0, wav, 12, 4);

        expectReject(wav, "缺少 fmt");
    }

    @Test
    public void rejectsNonRiffInput() {
        expectReject("not a wave file at all".getBytes(StandardCharsets.US_ASCII), "RIFF");
    }

    private static void expectReject(byte[] wav, String expectedFragment) {
        try {
            WavPcm16.extractPcm(wav);
            fail("expected the WAV to be rejected");
        } catch (IOException expected) {
            assertTrue("message was: " + expected.getMessage(),
                    expected.getMessage().contains(expectedFragment));
        }
    }

    /** Builds a RIFF/WAVE file with an optional extra chunk before {@code data}. */
    private static byte[] wav(
            int formatTag, int channels, int rate, int bits, byte[] data, byte[] extraChunk) {
        byte[] fmt = new byte[16];
        writeUint16(fmt, 0, formatTag);
        writeUint16(fmt, 2, channels);
        writeUint32(fmt, 4, rate);
        writeUint32(fmt, 8, (long) rate * channels * bits / 8); // byte rate
        writeUint16(fmt, 12, channels * bits / 8); // block align
        writeUint16(fmt, 14, bits);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeAll(body, chunk("fmt ", fmt));
        if (extraChunk != null) {
            writeAll(body, extraChunk);
        }
        if (data != null) {
            writeAll(body, chunk("data", data));
        }

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        writeAll(file, "RIFF".getBytes(StandardCharsets.US_ASCII));
        writeAll(file, uint32(4L + body.size() + 4L)); // WAVE tag + chunks
        writeAll(file, "WAVE".getBytes(StandardCharsets.US_ASCII));
        writeAll(file, body.toByteArray());
        return file.toByteArray();
    }

    private static byte[] chunk(String id, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAll(out, id.getBytes(StandardCharsets.US_ASCII));
        writeAll(out, uint32(body.length));
        writeAll(out, body);
        if ((body.length & 1) == 1) {
            out.write(0); // word alignment padding
        }
        return out.toByteArray();
    }

    private static byte[] uint32(long value) {
        byte[] bytes = new byte[4];
        writeUint32(bytes, 0, value);
        return bytes;
    }

    private static void writeUint16(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void writeUint32(byte[] target, int offset, long value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeAll(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] ramp(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i & 0xFF);
        }
        return bytes;
    }
}
