package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Captures microphone audio at 16 kHz mono PCM16 and emits it as fixed
 * {@link AudioFraming#BYTES_PER_FRAME}-byte frames for the {@code audio_pcm16_16k}
 * wire payload.
 *
 * <p>It is the uplink counterpart of {@link PcmPlayer}: reads run on a dedicated
 * daemon thread so the caller's thread (and the gRPC listener thread) never
 * blocks, and the captured chunks are fed through {@link AudioFraming.Framer} so
 * only whole 20 ms frames reach the {@link FrameSink}.
 *
 * <p>Every failure is a return value, never a crash: a denied {@code RECORD_AUDIO}
 * permission surfaces as a {@code SecurityException} the constructor throws,
 * which {@link #start()} catches and reports as {@code false}; so does an
 * unsupported rate/format or an uninitialised {@code AudioRecord}. The caller
 * shows the message.
 */
public final class EcosysAudioCapture implements AutoCloseable {

    private static final String TAG = "EcosysAudioCapture";

    /** Samples per {@code AudioRecord} read; framed down to 320-sample frames. */
    private static final int READ_SAMPLES = 1024;

    /** Receives each complete wire frame; called on the capture thread. */
    public interface FrameSink {
        void onFrame(byte[] frame);
    }

    private final FrameSink sink;
    private final AudioFraming.Framer framer = new AudioFraming.Framer();

    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean capturing;

    public EcosysAudioCapture(FrameSink sink) {
        this.sink = sink;
    }

    /**
     * Opens the microphone and starts streaming frames. Returns {@code false}
     * (after logging) when the device cannot capture — permission denied,
     * unsupported format, or an initialisation failure — so the caller can show a
     * message instead of crashing.
     */
    @SuppressLint("MissingPermission")
    public boolean start() {
        if (capturing) {
            return true;
        }
        int minBuffer = AudioRecord.getMinBufferSize(
                AudioFraming.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            Log.w(TAG, "AudioRecord unsupported at 16 kHz mono PCM16");
            return false;
        }
        int bufferSize = Math.max(minBuffer, READ_SAMPLES * AudioFraming.BYTES_PER_SAMPLE);

        AudioRecord record;
        try {
            record = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    AudioFraming.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "Unable to create AudioRecord (permission denied or unsupported device)", e);
            return false;
        }

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            Log.e(TAG, "AudioRecord initialization failed");
            return false;
        }

        try {
            record.startRecording();
        } catch (IllegalStateException | SecurityException e) {
            record.release();
            Log.e(TAG, "Unable to start audio recording", e);
            return false;
        }

        audioRecord = record;
        framer.reset();
        capturing = true;
        captureThread = new Thread(this::captureLoop, "ecosys-audio-capture");
        captureThread.start();
        Log.d(TAG, "Microphone capture started");
        return true;
    }

    private void captureLoop() {
        short[] buffer = new short[READ_SAMPLES];
        while (capturing && audioRecord != null) {
            int read = audioRecord.read(buffer, 0, buffer.length);
            // A negative read is a persistent error that would busy-spin; stop capture.
            if (read < 0) {
                Log.w(TAG, "AudioRecord read failed: " + read);
                break;
            }
            if (read == 0) {
                // A transient zero-sample read is legal, but a persistent one
                // would spin this thread at 100% CPU. Yield briefly; honour an
                // interrupt (stop()) rather than swallowing it and spinning on.
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            byte[] pcm = toLittleEndianPcm16(buffer, read);
            List<byte[]> frames = framer.add(pcm, AudioFraming.CHANNELS);
            for (byte[] frame : frames) {
                FrameSink current = sink;
                if (current != null) {
                    current.onFrame(frame);
                }
            }
        }
    }

    private static byte[] toLittleEndianPcm16(short[] samples, int count) {
        ByteBuffer buffer = ByteBuffer.allocate(count * AudioFraming.BYTES_PER_SAMPLE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            buffer.putShort(samples[i]);
        }
        return buffer.array();
    }

    public boolean isCapturing() {
        return capturing;
    }

    /** Stops the read loop and releases the microphone; safe to call repeatedly. */
    public void stop() {
        if (!capturing) {
            return;
        }
        capturing = false;

        AudioRecord record = audioRecord;
        if (record != null) {
            try {
                record.stop();
            } catch (IllegalStateException e) {
                Log.w(TAG, "AudioRecord stop failed", e);
            }
        }

        Thread thread = captureThread;
        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }

        releaseRecord();
        Log.d(TAG, "Microphone capture stopped");
    }

    private void releaseRecord() {
        AudioRecord record = audioRecord;
        if (record != null) {
            try {
                record.release();
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord release failed", e);
            }
            audioRecord = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
