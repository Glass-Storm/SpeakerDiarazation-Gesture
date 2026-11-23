package hk.edu.hkmu.speakerdiarazationdemo.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class EnrollmentRecorder {
    private static final String TAG = "EnrollmentRecorder";
    public static final int SAMPLE_RATE = 16_000;
    public static final int DURATION_SECONDS = 8;
    public static final int TARGET_SAMPLES = SAMPLE_RATE * DURATION_SECONDS;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int READ_SIZE = 1024;

    private AudioRecord audioRecord;
    private volatile boolean isRecording;
    private volatile boolean captureFailed;
    private Thread recordingThread;
    private final EnrollmentClip clip = new EnrollmentClip(TARGET_SAMPLES);

    @SuppressLint("MissingPermission")
    public void start() {
        if (isRecording) {
            return;
        }

        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuffer == AudioRecord.ERROR_BAD_VALUE || minBuffer == AudioRecord.ERROR) {
            throw new IllegalStateException("AudioRecord unsupported on this device");
        }

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                Math.max(minBuffer, READ_SIZE * 2)
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            throw new IllegalStateException("AudioRecord initialization failed");
        }

        audioRecord.startRecording();
        if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException("AudioRecord did not start");
        }
        isRecording = true;

        recordingThread = new Thread(this::captureLoop, "EnrollmentRecorderThread");
        recordingThread.start();
    }

    private void captureLoop() {
        short[] buffer = new short[READ_SIZE];
        while (isRecording) {
            int remaining = TARGET_SAMPLES - clip.sampleCount();
            if (remaining == 0) {
                return;
            }
            int read = audioRecord.read(buffer, 0, Math.min(remaining, buffer.length));
            if (read < 0) {
                captureFailed = isRecording;
                return;
            }
            clip.append(buffer, read);
        }
    }

    public int getCapturedSamples() {
        return clip.sampleCount();
    }

    public boolean hasCaptureFailed() {
        return captureFailed;
    }

    public void stop() {
        if (!isRecording) {
            return;
        }
        isRecording = false;

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException e) {
                Log.w(TAG, "AudioRecord stop failed", e);
            }
        }

        if (recordingThread != null) {
            try {
                recordingThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }
    }

    public void release() {
        stop();
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
    }

    public byte[] getPcm16Data() {
        return clip.pcm16();
    }

    public byte[] getFloat32leData() {
        byte[] pcm16 = getPcm16Data();
        ByteBuffer out = ByteBuffer.allocate(pcm16.length * 2);
        out.order(ByteOrder.LITTLE_ENDIAN);

        ByteBuffer in = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN);
        while (in.remaining() >= 2) {
            short sample = in.getShort();
            float normalized = sample / 32768f;
            out.putFloat(normalized);
        }
        return out.array();
    }

    public File writeWavFile(File outputDir, String fileName) throws IOException {
        byte[] pcm16 = getPcm16Data();
        if (pcm16.length == 0) {
            throw new IOException("No audio captured");
        }

        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Unable to create directory: " + outputDir);
        }

        File wavFile = new File(outputDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(wavFile)) {
            writeWavHeader(fos, pcm16.length);
            fos.write(pcm16);
            fos.flush();
        }
        return wavFile;
    }

    private void writeWavHeader(FileOutputStream out, int pcmSize) throws IOException {
        int byteRate = SAMPLE_RATE * 2;
        int totalDataLen = pcmSize + 36;

        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);

        header.put((byte) 'R');
        header.put((byte) 'I');
        header.put((byte) 'F');
        header.put((byte) 'F');
        header.order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataLen);
        header.put((byte) 'W');
        header.put((byte) 'A');
        header.put((byte) 'V');
        header.put((byte) 'E');
        header.put((byte) 'f');
        header.put((byte) 'm');
        header.put((byte) 't');
        header.put((byte) ' ');
        header.order(ByteOrder.LITTLE_ENDIAN).putInt(16);
        header.order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1); // PCM
        header.order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1); // Mono
        header.order(ByteOrder.LITTLE_ENDIAN).putInt(SAMPLE_RATE);
        header.order(ByteOrder.LITTLE_ENDIAN).putInt(byteRate);
        header.order(ByteOrder.LITTLE_ENDIAN).putShort((short) 2); // Block align
        header.order(ByteOrder.LITTLE_ENDIAN).putShort((short) 16); // Bits per sample
        header.put((byte) 'd');
        header.put((byte) 'a');
        header.put((byte) 't');
        header.put((byte) 'a');
        header.order(ByteOrder.LITTLE_ENDIAN).putInt(pcmSize);

        out.write(header.array(), 0, 44);
    }
}
