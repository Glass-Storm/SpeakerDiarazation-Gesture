package hk.edu.hkmu.speakerdiarazationdemo;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class AudioRecorder {
    private static final String TAG = "AudioRecorder";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int PREFERRED_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT;
    private static final int FALLBACK_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHUNK_SIZE = 1024;

    private final AudioDataCallback callback;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private int activeAudioFormat = PREFERRED_AUDIO_FORMAT;

    public interface AudioDataCallback {
        void onAudioData(byte[] data);
    }

    public AudioRecorder(AudioDataCallback callback) {
        this.callback = callback;
        initialiseRecorder();
    }

    private void initialiseRecorder() {
        // Try floating point audio first (best quality + matches Speechmatics format).
        audioRecord = createRecorderForFormat(PREFERRED_AUDIO_FORMAT);
        activeAudioFormat = PREFERRED_AUDIO_FORMAT;

        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "Floating point capture not supported, falling back to PCM 16-bit");
            releaseInternal();
            audioRecord = createRecorderForFormat(FALLBACK_AUDIO_FORMAT);
            activeAudioFormat = FALLBACK_AUDIO_FORMAT;
        }

        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            releaseInternal();
            throw new IllegalStateException("Unable to initialise AudioRecord with supported format");
        }
    }

    @SuppressLint("MissingPermission")
    private AudioRecord createRecorderForFormat(int audioFormat) {
        int bytesPerSample = audioFormat == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, audioFormat);
        if (minBuffer < 0) {
            Log.w(TAG, "getMinBufferSize returned " + minBuffer + " for format " + audioFormat);
            return null;
        }

        int bufferSize = Math.max(minBuffer, CHUNK_SIZE * bytesPerSample);
        try {
            return new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                audioFormat,
                bufferSize
            );
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to create AudioRecord for format " + audioFormat, e);
            return null;
        }
    }

    public void startRecording() {
        if (isRecording) return;
        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioRecord is not initialised");
        }

        isRecording = true;
        try {
            audioRecord.startRecording();
        } catch (IllegalStateException | SecurityException e) {
            isRecording = false;
            Log.e(TAG, "Unable to start audio recording", e);
            throw new IllegalStateException("Unable to start audio recording", e);
        }
        
        recordingThread = new Thread(() -> {
            ByteBuffer byteBuffer = ByteBuffer.allocate(CHUNK_SIZE * 4);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);

            if (activeAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
                readAsFloat(byteBuffer);
            } else {
                readAsShort(byteBuffer);
            }
        });
        
        recordingThread.start();
        Log.d(TAG, "Recording started");
    }

    public void stopRecording() {
        if (!isRecording) return;

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
                Log.e(TAG, "Error stopping recording thread", e);
            }
            recordingThread = null;
        }

        Log.d(TAG, "Recording stopped");
    }

    public void release() {
        stopRecording();
        releaseInternal();
    }

    private void readAsFloat(ByteBuffer byteBuffer) {
        float[] audioData = new float[CHUNK_SIZE];
        while (isRecording) {
            int read = audioRecord.read(audioData, 0, CHUNK_SIZE, AudioRecord.READ_BLOCKING);
            if (read > 0) {
                byteBuffer.clear();
                for (int i = 0; i < read; i++) {
                    byteBuffer.putFloat(audioData[i]);
                }
                dispatchAudio(byteBuffer.array(), read);
            }
        }
    }

    private void readAsShort(ByteBuffer byteBuffer) {
        short[] audioData = new short[CHUNK_SIZE];
        while (isRecording) {
            int read = audioRecord.read(audioData, 0, CHUNK_SIZE, AudioRecord.READ_BLOCKING);
            if (read > 0) {
                byteBuffer.clear();
                for (int i = 0; i < read; i++) {
                    float normalized = audioData[i] / 32768f;
                    byteBuffer.putFloat(normalized);
                }
                dispatchAudio(byteBuffer.array(), read);
            }
        }
    }

    private void dispatchAudio(byte[] buffer, int samplesRead) {
        if (callback != null && samplesRead > 0) {
            callback.onAudioData(Arrays.copyOfRange(buffer, 0, samplesRead * 4));
        }
    }

    private void releaseInternal() {
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing AudioRecord", e);
            }
            audioRecord = null;
        }
    }
}
