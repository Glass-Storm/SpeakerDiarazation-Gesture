package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays the raw little-endian PCM16 mono @ 16 kHz bytes the hub sends in a
 * {@code tts_audio} frame.
 *
 * <p>Playback runs on a dedicated background thread: a frame arrives on the gRPC
 * listener thread, and blocking there would stall the stream (and touching the
 * UI thread would ANR). Frames are serialized on a single-thread executor so two
 * overlapping utterances never write to the same track.
 *
 * <p>Robustness is explicit: a null/empty/odd-length payload is dropped without
 * touching {@code AudioTrack}, and any platform failure (no output route, device
 * without an audio sink) is caught and logged. Playback can never crash the
 * stream.
 */
public final class PcmPlayer implements AutoCloseable {

    private static final String TAG = "PcmPlayer";

    /** Extra grace on top of the clip duration before a stalled head gives up. */
    private static final long PLAYBACK_GUARD_MS = 5_000L;
    private static final long POLL_INTERVAL_MS = 20L;

    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public PcmPlayer() {
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ecosys-pcm-playback");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Queues {@code pcm} for background playback. A null or malformed payload is
     * ignored silently; a closed player drops late frames.
     */
    public void play(byte[] pcm) {
        if (closed.get() || !Pcm16.isPlayable(pcm)) {
            return;
        }
        final byte[] payload = pcm.clone();
        try {
            executor.execute(() -> playBlocking(payload));
        } catch (RuntimeException rejected) {
            // The executor was shut down between the closed check and submission;
            // dropping the frame is the correct outcome.
            Log.w(TAG, "dropping tts_audio frame: player is closed", rejected);
        }
    }

    private void playBlocking(byte[] pcm) {
        AudioTrack track = null;
        try {
            int minBufferSize = AudioTrack.getMinBufferSize(
                    Pcm16.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(minBufferSize, pcm.length);

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Pcm16.SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(bufferSize)
                    .build();

            track.write(pcm, 0, pcm.length);
            track.play();

            int samples = Pcm16.sampleCount(pcm);
            long deadline = System.currentTimeMillis()
                    + Pcm16.durationMs(samples)
                    + PLAYBACK_GUARD_MS;
            while (!closed.get()
                    && track.getPlaybackHeadPosition() < samples
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable failure) {
            // A device with no usable output route simply drops the utterance;
            // the stream must keep running.
            Log.w(TAG, "tts_audio playback failed", failure);
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignored) {
                    // Best-effort teardown.
                }
                try {
                    track.release();
                } catch (Throwable ignored) {
                    // Best-effort teardown.
                }
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
    }
}
