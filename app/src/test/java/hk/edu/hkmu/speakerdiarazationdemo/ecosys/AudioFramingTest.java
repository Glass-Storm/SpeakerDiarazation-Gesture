package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Pins the wire framing the hub expects: 16 kHz mono PCM16 in 20 ms / 320-sample /
 * 640-byte frames. A regression here desynchronises the hub's speech pipeline, so
 * the constants and the splitter are asserted directly.
 */
public class AudioFramingTest {

    @Test
    public void constantsMatchTheWireContract() {
        assertEquals(16_000, AudioFraming.SAMPLE_RATE_HZ);
        assertEquals(20, AudioFraming.FRAME_MS);
        assertEquals(320, AudioFraming.SAMPLES_PER_FRAME);
        assertEquals(2, AudioFraming.BYTES_PER_SAMPLE);
        assertEquals(1, AudioFraming.CHANNELS);
        assertEquals(640, AudioFraming.BYTES_PER_FRAME);
    }

    @Test
    public void splitsExactMultipleInto640ByteFrames() {
        byte[] pcm = new byte[AudioFraming.BYTES_PER_FRAME * 2];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) i;
        }

        List<byte[]> frames = AudioFraming.frames(pcm);

        assertEquals(2, frames.size());
        assertEquals(640, frames.get(0).length);
        assertEquals(640, frames.get(1).length);
        // The frames reconstruct the input in order and without gaps.
        byte[] rebuilt = concat(frames);
        assertArrayEquals(pcm, rebuilt);
    }

    @Test
    public void dropsTrailingPartialFrame() {
        byte[] pcm = new byte[AudioFraming.BYTES_PER_FRAME + 37];

        List<byte[]> frames = AudioFraming.frames(pcm);

        assertEquals(1, frames.size());
        assertEquals(640, frames.get(0).length);
    }

    @Test
    public void shortBufferYieldsNoFrames() {
        assertTrue(AudioFraming.frames(new byte[100]).isEmpty());
        assertTrue(AudioFraming.frames(null).isEmpty());
    }

    @Test
    public void framerReassemblesAcrossArbitraryChunkBoundaries() {
        AudioFraming.Framer framer = new AudioFraming.Framer();

        // Half a frame cannot complete anything yet.
        assertTrue(framer.add(new byte[300]).isEmpty());
        assertEquals(300, framer.bufferedBytes());

        // The remaining 340 bytes complete exactly one frame.
        List<byte[]> first = framer.add(new byte[340]);
        assertEquals(1, first.size());
        assertEquals(640, first.get(0).length);
        assertEquals(0, framer.bufferedBytes());

        // 1000 bytes -> one frame, with 360 bytes held back.
        List<byte[]> second = framer.add(new byte[1000]);
        assertEquals(1, second.size());
        assertEquals(360, framer.bufferedBytes());

        // reset() discards the remainder.
        framer.reset();
        assertEquals(0, framer.bufferedBytes());
        assertTrue(framer.add(new byte[100]).isEmpty());
    }

    @Test
    public void framerPreservesByteStreamOrder() {
        AudioFraming.Framer framer = new AudioFraming.Framer();
        byte[] chunk = new byte[AudioFraming.BYTES_PER_FRAME * 3];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) i;
        }

        List<byte[]> frames = framer.add(chunk);

        assertEquals(3, frames.size());
        assertArrayEquals(chunk, concat(frames));
    }

    @Test
    public void downmixesStereoToMonoByAveragingChannels() {
        // Two stereo frames: L=[1000,2000], R=[3000,4000] -> mono=[2000,3000].
        short[] interleaved = {1000, 3000, 2000, 4000};
        byte[] pcm = new byte[interleaved.length * 2];
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer().put(interleaved);

        byte[] mono = AudioFraming.downmixToMono(pcm, 2);

        assertEquals(4, mono.length);
        short[] samples = Pcm16.toShorts(mono);
        assertArrayEquals(new short[] {2000, 3000}, samples);
    }

    @Test
    public void monoInputIsPassedThroughUnchanged() {
        byte[] pcm = {(byte) 0x10, (byte) 0x20, (byte) 0x30, (byte) 0x40};
        assertArrayEquals(pcm, AudioFraming.downmixToMono(pcm, 1));
    }

    private static byte[] concat(List<byte[]> frames) {
        int total = 0;
        for (byte[] frame : frames) {
            total += frame.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] frame : frames) {
            System.arraycopy(frame, 0, out, offset, frame.length);
            offset += frame.length;
        }
        return out;
    }
}
