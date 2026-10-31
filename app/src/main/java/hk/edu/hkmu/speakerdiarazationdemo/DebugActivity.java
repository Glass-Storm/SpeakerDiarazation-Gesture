package hk.edu.hkmu.speakerdiarazationdemo;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ecosys.v1.DeviceRole;
import ecosys.v1.EnrollRequest;
import ecosys.v1.ImageFrame;
import ecosys.v1.StreamFrame;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.AudioFraming;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.DebugFrameText;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.DebugMedia;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.EcosysStreamClient;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.HubHostStore;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.WavPcm16;

/**
 * DEBUG-only screen that proves the glasses &rarr; phone &rarr; processing
 * &rarr; display round trip with synthetic media, so no real person has to
 * speak or show a hand.
 *
 * <p>It dials the last hub host remembered by {@link HubHostStore}, uploads a
 * deterministic 16 kHz PCM16 tone and a deterministic 320&times;240 JPEG, and
 * prints every inbound {@code StreamFrame} result (transcript + speaker label,
 * gesture text, TTS audio) into a readable log.
 *
 * <p>All socket and payload work runs on a background executor; the client's
 * callbacks arrive off the main thread and are marshalled with a main-looper
 * {@link Handler}.
 */
public class DebugActivity extends AppCompatActivity {

    private static final String TAG = "DebugActivity";

    /** Device identity bound by the first {@code stream_open} frame. */
    private static final String ECOSYS_DEVICE_ID = "glasses-debug";

    /** Keeps the log TextView bounded on a long-running debug session. */
    private static final int MAX_LOG_CHARS = 20_000;

    /** Bundled real Cantonese speech clip, 16 kHz mono PCM16 WAV. */
    private static final String REAL_SPEECH_ASSET = "debug/demo_yue.wav";

    /** Enroll needs at least 8 s of audio; the debug path pads to 9 s. */
    private static final int ENROLL_MIN_BYTES = 9 * 16_000 * 2;

    /** Upper bound for the repeated debug clip, so it stays near 12 s. */
    private static final int ENROLL_MAX_BYTES = 12 * 16_000 * 2;

    /** One frame per 20 ms of audio, matching the mic capture path's pacing. */
    private static final long SPEECH_FRAME_PACING_MS = AudioFraming.FRAME_MS;

    /**
     * Voiceprint name sent with the bundled clip. The hub accepts only
     * {@code ^[\p{Han}A-Za-z0-9]+$} (no spaces, 1..24 characters), so the name
     * is a plain Han string with no punctuation.
     */
    private static final String ENROLL_NAME = "測試講者";

    private static final String STATE_DISCONNECTED = "未連線";
    private static final String STATE_CONNECTING = "連線中…";
    private static final String STATE_CONNECTED = "已連線 ✓";
    private static final String STATE_DISCONNECTED_REMOTE = "已斷線";

    private TextView tvDebugHost;
    private TextView tvDebugConnection;
    private TextView tvDebugCounters;
    private TextView tvDebugLastError;
    private TextView tvDebugLog;
    private ScrollView scrollDebug;
    private Button btnDebugConnect;
    private Button btnDebugDisconnect;
    private Button btnDebugSendAudio;
    private Button btnDebugSendSpeech;
    private Button btnDebugSendRealSpeech;
    private Button btnDebugSendEnroll;
    private Button btnDebugSendImage;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timestampFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final StringBuilder logBuilder = new StringBuilder();

    private ExecutorService ioExecutor;
    private String hubHost;
    private EcosysStreamClient client;
    private EcosysStreamClient.Listener clientListener;
    private boolean connectionLive;
    private volatile boolean speechSendInProgress;
    private volatile boolean enrollSendInProgress;
    private int audioFramesSent;
    private int imageFramesSent;
    private int inboundFrames;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);

        hubHost = HubHostStore.load(this);

        tvDebugHost = findViewById(R.id.tvDebugHost);
        tvDebugConnection = findViewById(R.id.tvDebugConnection);
        tvDebugCounters = findViewById(R.id.tvDebugCounters);
        tvDebugLastError = findViewById(R.id.tvDebugLastError);
        tvDebugLog = findViewById(R.id.tvDebugLog);
        scrollDebug = findViewById(R.id.scrollDebug);
        btnDebugConnect = findViewById(R.id.btnDebugConnect);
        btnDebugDisconnect = findViewById(R.id.btnDebugDisconnect);
        btnDebugSendAudio = findViewById(R.id.btnDebugSendAudio);
        btnDebugSendSpeech = findViewById(R.id.btnDebugSendSpeech);
        btnDebugSendRealSpeech = findViewById(R.id.btnDebugSendRealSpeech);
        btnDebugSendEnroll = findViewById(R.id.btnDebugSendEnroll);
        btnDebugSendImage = findViewById(R.id.btnDebugSendImage);

        ImageButton btnBack = findViewById(R.id.btnBackDebug);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "debug-media-sender");
            thread.setDaemon(true);
            return thread;
        });

        tvDebugHost.setText("Hub：" + hubHost + ":" + EcosysStreamClient.DEFAULT_HUB_PORT);
        setConnectionState(STATE_DISCONNECTED,
                ContextCompat.getColor(this, android.R.color.holo_orange_light));
        refreshCounters();
        refreshActions();

        btnDebugConnect.setOnClickListener(v -> connectClient());
        btnDebugDisconnect.setOnClickListener(v -> disconnectClient());
        btnDebugSendAudio.setOnClickListener(v ->
                sendToneFrame(DebugMedia.PRIMARY_TONE_FREQUENCY_HZ, "測試音訊 440 Hz"));
        btnDebugSendSpeech.setOnClickListener(v ->
                sendToneFrame(DebugMedia.SECONDARY_TONE_FREQUENCY_HZ, "測試語音 660 Hz"));
        btnDebugSendRealSpeech.setOnClickListener(v -> sendRealSpeech());
        btnDebugSendEnroll.setOnClickListener(v -> sendEnrollRequest());
        btnDebugSendImage.setOnClickListener(v -> sendTestImage());

        appendLog("除錯頁面已就緒，Hub 位址為 " + hubHost);
    }

    @Override
    protected void onDestroy() {
        disconnectClient();
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
            ioExecutor = null;
        }
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Opens the stream once; a second press is ignored until the user disconnects. */
    private void connectClient() {
        if (client != null) {
            appendLog("已有連線，略過重複連線");
            return;
        }
        EcosysStreamClient newClient = new EcosysStreamClient(
                hubHost,
                EcosysStreamClient.DEFAULT_HUB_PORT,
                ECOSYS_DEVICE_ID,
                DeviceRole.DEVICE_ROLE_GLASS);
        clientListener = new EcosysStreamClient.Listener() {
            @Override
            public void onConnected() {
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    connectionLive = true;
                    setConnectionState(STATE_CONNECTED,
                            ContextCompat.getColor(DebugActivity.this,
                                    android.R.color.holo_green_light));
                    appendLog("已連線到 Hub " + hubHost + ":"
                            + EcosysStreamClient.DEFAULT_HUB_PORT);
                    refreshActions();
                });
            }

            @Override
            public void onFrame(StreamFrame frame) {
                final String line = DebugFrameText.describe(frame);
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    inboundFrames++;
                    appendLog("← " + line);
                    refreshCounters();
                });
            }

            @Override
            public void onDisconnected(Throwable cause) {
                final String detail = describeCause(cause);
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    connectionLive = false;
                    setConnectionState(STATE_DISCONNECTED_REMOTE,
                            ContextCompat.getColor(DebugActivity.this,
                                    android.R.color.holo_red_light));
                    appendLog("連線中斷：" + detail);
                    refreshActions();
                });
            }
        };
        client = newClient;
        newClient.addListener(clientListener);
        setConnectionState(STATE_CONNECTING,
                ContextCompat.getColor(this, android.R.color.holo_orange_light));
        appendLog("正在連線 " + hubHost + ":" + EcosysStreamClient.DEFAULT_HUB_PORT + " …");
        refreshActions();
        try {
            newClient.start();
        } catch (RuntimeException startFailure) {
            client = null;
            connectionLive = false;
            setConnectionState(STATE_DISCONNECTED,
                    ContextCompat.getColor(this, android.R.color.holo_red_light));
            reportError("連線失敗", startFailure);
            refreshActions();
        }
    }

    /** Closes the client and returns the page to a clean disconnected state. */
    private void disconnectClient() {
        EcosysStreamClient old = client;
        client = null;
        connectionLive = false;
        if (old != null) {
            if (clientListener != null) {
                old.removeListener(clientListener);
            }
            clientListener = null;
            old.close();
            appendLog("已手動斷線");
        }
        setConnectionState(STATE_DISCONNECTED,
                ContextCompat.getColor(this, android.R.color.holo_orange_light));
        refreshActions();
    }

    /** Generates the tone off-main, then uploads it as an {@code audio_pcm16_16k} frame. */
    private void sendToneFrame(final int frequencyHz, final String label) {
        final EcosysStreamClient active = client;
        if (active == null) {
            reportError("無法送出 " + label, new IllegalStateException("尚未連線，請先按「連線到 Hub」"));
            return;
        }
        ioExecutor.execute(() -> {
            final byte[] pcm = DebugMedia.tonePcm16k(frequencyHz, DebugMedia.TONE_DURATION_MS);
            try {
                active.send(StreamFrame.newBuilder()
                        .setAudioPcm1616K(ByteString.copyFrom(pcm))
                        .build());
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    audioFramesSent++;
                    appendLog("→ " + label + "：" + pcm.length + " 位元組 / "
                            + (DebugMedia.TONE_DURATION_MS / 1_000.0) + " 秒");
                    refreshCounters();
                });
            } catch (RuntimeException sendFailure) {
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    reportError("送出 " + label + " 失敗", sendFailure);
                });
            }
        });
    }

    /**
     * Reads the bundled Cantonese clip off-main, strips its WAV container, and
     * streams it in the same 20 ms / 640-byte frames the mic path sends, paced
     * so the hub's relay queue never overflows. The executor is shut down with
     * {@code shutdownNow()} on destroy, which interrupts the pacing sleep and
     * stops the upload mid-clip.
     */
    private void sendRealSpeech() {
        final EcosysStreamClient active = client;
        if (active == null) {
            reportError("無法送出真實語音",
                    new IllegalStateException("尚未連線，請先按「連線到 Hub」"));
            return;
        }
        if (speechSendInProgress) {
            appendLog("真實語音正在送出，略過重複請求");
            return;
        }
        speechSendInProgress = true;
        refreshActions();
        ioExecutor.execute(() -> {
            try {
                byte[] pcm = loadRealSpeechPcm();
                List<byte[]> frames = AudioFraming.frames(pcm);
                if (frames.isEmpty()) {
                    throw new IOException("WAV 沒有可送出的音訊");
                }
                for (int i = 0; i < frames.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    active.send(StreamFrame.newBuilder()
                            .setAudioPcm1616K(ByteString.copyFrom(frames.get(i)))
                            .build());
                    if (i + 1 < frames.size()) {
                        Thread.sleep(SPEECH_FRAME_PACING_MS);
                    }
                }
                final int sentBytes = frames.size() * AudioFraming.BYTES_PER_FRAME;
                final double seconds = (double) sentBytes
                        / (AudioFraming.SAMPLE_RATE_HZ * AudioFraming.BYTES_PER_SAMPLE);
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    audioFramesSent += frames.size();
                    appendLog("→ 真實語音：" + sentBytes + " 位元組 / "
                            + String.format(Locale.ROOT, "%.2f", seconds) + " 秒");
                    refreshCounters();
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException loadFailure) {
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    reportError("讀取真實語音失敗", loadFailure);
                });
            } catch (RuntimeException sendFailure) {
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    reportError("送出真實語音失敗", sendFailure);
                });
            } finally {
                speechSendInProgress = false;
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    refreshActions();
                });
            }
        });
    }

    /**
     * Reads the bundled Cantonese clip off-main and sends it as a single
     * {@code enroll_request} frame, so voiceprint enrollment is reproducible
     * without a person speaking. The hub answers with {@code enroll_response}.
     */
    private void sendEnrollRequest() {
        final EcosysStreamClient active = client;
        if (active == null) {
            reportError("無法送出聲紋註冊",
                    new IllegalStateException("尚未連線，請先按「連線到 Hub」"));
            return;
        }
        if (enrollSendInProgress) {
            appendLog("聲紋註冊正在送出，略過重複請求");
            return;
        }
        enrollSendInProgress = true;
        refreshActions();
        ioExecutor.execute(() -> {
            try {
                final byte[] pcm = padPcmForEnroll(loadRealSpeechPcm());
                active.send(StreamFrame.newBuilder()
                        .setEnrollRequest(EnrollRequest.newBuilder()
                                .setName(ENROLL_NAME)
                                .setAudioPcm1616K(ByteString.copyFrom(pcm))
                                .setPtsMs(0L)
                                .build())
                        .build());
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    appendLog("→ 聲紋註冊：" + ENROLL_NAME + " / " + pcm.length + " 位元組");
                });
            } catch (IOException loadFailure) {
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    reportError("讀取聲紋語音失敗", loadFailure);
                });
            } catch (RuntimeException sendFailure) {
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    reportError("送出聲紋註冊失敗", sendFailure);
                });
            } finally {
                enrollSendInProgress = false;
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    refreshActions();
                });
            }
        });
    }

    private static byte[] padPcmForEnroll(byte[] pcm) {
        if (pcm.length == 0 || pcm.length >= ENROLL_MIN_BYTES) {
            return pcm;
        }
        int repeats = (ENROLL_MIN_BYTES + pcm.length - 1) / pcm.length;
        if (repeats * pcm.length > ENROLL_MAX_BYTES) {
            return pcm;
        }
        byte[] padded = new byte[pcm.length * repeats];
        for (int i = 0; i < repeats; i++) {
            System.arraycopy(pcm, 0, padded, i * pcm.length, pcm.length);
        }
        return padded;
    }

    /** Loads the raw 16 kHz mono PCM16 payload, or fails with a clear reason. */
    private byte[] loadRealSpeechPcm() throws IOException {
        byte[] wav;
        try (InputStream in = getAssets().open(REAL_SPEECH_ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            wav = out.toByteArray();
        } catch (FileNotFoundException missing) {
            throw new IOException("找不到內建語音 asset：" + REAL_SPEECH_ASSET, missing);
        }
        return WavPcm16.extractPcm(wav);
    }

    /** Builds the deterministic still off-main, then uploads it as an {@code image} frame. */
    private void sendTestImage() {
        final EcosysStreamClient active = client;
        if (active == null) {
            reportError("無法送出測試影像",
                    new IllegalStateException("尚未連線，請先按「連線到 Hub」"));
            return;
        }
        ioExecutor.execute(() -> {
            final byte[] jpeg;
            try {
                jpeg = createTestJpeg(DebugMedia.IMAGE_WIDTH, DebugMedia.IMAGE_HEIGHT);
            } catch (IOException | RuntimeException encodeFailure) {
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    reportError("產生測試影像失敗", encodeFailure);
                });
                return;
            }
            try {
                active.send(StreamFrame.newBuilder()
                        .setImage(ImageFrame.newBuilder()
                                .setJpeg(ByteString.copyFrom(jpeg))
                                .setPtsMs(SystemClock.elapsedRealtime())
                                .setWidth(DebugMedia.IMAGE_WIDTH)
                                .setHeight(DebugMedia.IMAGE_HEIGHT)
                                .build())
                        .build());
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    imageFramesSent++;
                    appendLog("→ 測試影像：" + jpeg.length + " 位元組 / "
                            + DebugMedia.IMAGE_WIDTH + "×" + DebugMedia.IMAGE_HEIGHT);
                    refreshCounters();
                });
            } catch (RuntimeException sendFailure) {
                uiHandler.post(() -> {
                    if (isGone()) {
                        return;
                    }
                    reportError("送出測試影像失敗", sendFailure);
                });
            }
        });
    }

    /**
     * A deterministic ARGB_8888 checkerboard with a red centre marker, encoded
     * as JPEG. Pixel colours are a pure function of the coordinates, so repeated
     * presses upload the same visual content.
     */
    private static byte[] createTestJpeg(int width, int height) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    boolean dark = ((x / 20) + (y / 20)) % 2 == 0;
                    int red = dark ? 20 : 220;
                    int green = dark ? 40 : 220;
                    int blue = dark ? 80 : 220;
                    if (isCentreMarker(x, y, width, height)) {
                        red = 230;
                        green = 30;
                        blue = 30;
                    }
                    row[x] = Color.rgb(red, green, blue);
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, DebugMedia.JPEG_QUALITY, output)) {
                throw new IOException("Bitmap.compress(JPEG) returned false");
            }
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    /** True inside the 60&times;60 red square in the middle of the still. */
    private static boolean isCentreMarker(int x, int y, int width, int height) {
        int halfWidth = width / 2;
        int halfHeight = height / 2;
        return Math.abs(x - halfWidth) <= 30 && Math.abs(y - halfHeight) <= 30;
    }

    private void refreshActions() {
        boolean hasClient = client != null;
        boolean canSend = hasClient && connectionLive;
        btnDebugConnect.setEnabled(!hasClient);
        btnDebugDisconnect.setEnabled(hasClient);
        btnDebugSendAudio.setEnabled(canSend);
        btnDebugSendSpeech.setEnabled(canSend);
        btnDebugSendRealSpeech.setEnabled(canSend && !speechSendInProgress);
        btnDebugSendEnroll.setEnabled(canSend && !enrollSendInProgress);
        btnDebugSendImage.setEnabled(canSend);
    }

    private void refreshCounters() {
        tvDebugCounters.setText("已送出：音訊 " + audioFramesSent + " 幀 / 影像 "
                + imageFramesSent + " 幀　｜　已收到：" + inboundFrames + " 幀");
    }

    private void setConnectionState(String state, int color) {
        tvDebugConnection.setText("連線狀態：" + state);
        tvDebugConnection.setTextColor(color);
    }

    /** Appends one timestamped line to the bounded log and keeps it scrolled to the bottom. */
    private void appendLog(String line) {
        String stamped = timestampFormat.format(new Date()) + "  " + line + "\n";
        logBuilder.append(stamped);
        if (logBuilder.length() > MAX_LOG_CHARS) {
            int cut = logBuilder.indexOf("\n", logBuilder.length() - MAX_LOG_CHARS);
            logBuilder.delete(0, cut >= 0 ? cut + 1 : logBuilder.length());
        }
        tvDebugLog.setText(logBuilder.toString());
        scrollDebug.post(() -> scrollDebug.fullScroll(View.FOCUS_DOWN));
    }

    private void reportError(String prefix, Throwable failure) {
        String detail = describeCause(failure);
        String message = prefix + "：" + detail;
        tvDebugLastError.setText("最後錯誤：" + message);
        appendLog("✗ " + message);
        Log.w(TAG, message, failure);
    }

    private static String describeCause(Throwable cause) {
        if (cause == null) {
            return "遠端關閉串流";
        }
        String message = cause.getMessage();
        if (!TextUtils.isEmpty(message)) {
            return message;
        }
        return cause.getClass().getSimpleName();
    }

    private boolean isGone() {
        return isFinishing() || isDestroyed();
    }
}
