package hk.edu.hkmu.speakerdiarazationdemo;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.protobuf.ByteString;

import ecosys.v1.EnrollRequest;
import ecosys.v1.StreamFrame;
import hk.edu.hkmu.speakerdiarazationdemo.audio.EnrollmentRecorder;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.EcosysStreamClient;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.EnrollmentName;
import hk.edu.hkmu.speakerdiarazationdemo.models.EnrolledSpeaker;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Local voiceprint enrollment for the hub.
 *
 * <p>The screen records exactly {@value #RECORD_DURATION_MS} ms of 16 kHz mono
 * PCM16 audio (shown as a determinate progress bar), asks for a name validated
 * by {@link EnrollmentName} ({@code ^[\p{Han}A-Za-z0-9]+$}, 1–24 chars), and
 * sends an {@code enroll_request{name, audio_pcm16_16k}} over the plaintext
 * {@code ecosys.v1} stream. The hub owns the voiceprints: it answers with
 * {@code enroll_response{ok, reason}}, which this screen shows.
 *
 * <p>The previously-enrolled names are listed from the local mirror the hub's
 * accepted responses populate, so a name can be re-enrolled (replaced) by
 * entering it again. The voiceprints themselves live only on the hub.
 */
public class SpeakerEnrollmentActivity extends AppCompatActivity {

    public static final String EXTRA_LANGUAGE = "extra_language";
    public static final String EXTRA_EXISTING_NAME = "extra_existing_name";
    public static final String EXTRA_EXISTING_AUDIO_PATH = "extra_existing_audio_path";
    /** Optional hub-host override (tools/tests); defaults to the SoftAP gateway. */
    public static final String EXTRA_HUB_HOST = "extra_hub_host";

    /** The phone is the SoftAP, so the hub is reachable at the gateway until T22 auto-detects it. */
    private static final String DEFAULT_HUB_HOST = "192.168.43.1";
    private static final String ECOSYS_DEVICE_ID = "glasses";

    /** The hub minimum, and the exact length this screen records. */
    public static final int RECORD_DURATION_MS = 8000;
    private static final int RECORD_TICK_MS = 100;

    /** Bytes in an 8 s clip of 16 kHz mono PCM16; the hub rejects anything shorter. */
    private static final int MIN_ENROLLMENT_BYTES =
            (RECORD_DURATION_MS / 1000) * EnrollmentRecorder.SAMPLE_RATE * 2;

    private static final long ENROLL_TIMEOUT_MS = 15_000L;

    private EditText etSpeakerName;
    private TextView tvStatus;
    private TextView tvEnrolledNames;
    private View btnStartRecording;
    private View btnPlayRecording;
    private View btnDiscardRecording;
    private View btnSubmit;
    private ProgressBar progressRecord;
    private ProgressBar progressSubmitting;

    private EnrollmentRecorder recorder;
    private MediaPlayer mediaPlayer;
    private EcosysStreamClient ecosysClient;

    private boolean hasRecording = false;
    private boolean isRecording = false;
    private boolean isSubmitting = false;
    private long recordStartMs = 0L;
    private String languageCode = "yue";
    private String existingName;
    private String existingAudioPath;
    private String submissionName;
    private String hubHost;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable recordTick = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - recordStartMs;
            if (elapsed >= RECORD_DURATION_MS) {
                progressRecord.setProgress(100);
                stopRecording();
                return;
            }
            int percent = (int) (elapsed * 100 / RECORD_DURATION_MS);
            progressRecord.setProgress(percent);
            tvStatus.setText(String.format(Locale.getDefault(),
                    "錄音中… %.1f / %.1f 秒", elapsed / 1000.0, RECORD_DURATION_MS / 1000.0));
            handler.postDelayed(this, RECORD_TICK_MS);
        }
    };

    private final Runnable enrollTimeout = () -> {
        if (isSubmitting) {
            onEnrollmentFailed("中樞沒有回應，請確認已連上熱點後重試");
        }
    };

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, "需要麥克風權限", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_speaker_enrollment);

        languageCode = coalesce(getIntent().getStringExtra(EXTRA_LANGUAGE), "yue");
        existingName = getIntent().getStringExtra(EXTRA_EXISTING_NAME);
        existingAudioPath = getIntent().getStringExtra(EXTRA_EXISTING_AUDIO_PATH);
        hubHost = coalesce(getIntent().getStringExtra(EXTRA_HUB_HOST), DEFAULT_HUB_HOST);

        initViews();
        ensureMicPermission();
        connectEcosys();
    }

    private static String coalesce(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private void initViews() {
        ImageButton btnBack = findViewById(R.id.btnClose);
        etSpeakerName = findViewById(R.id.etSpeakerName);
        tvStatus = findViewById(R.id.tvStatus);
        tvEnrolledNames = findViewById(R.id.tvEnrolledNames);
        btnStartRecording = findViewById(R.id.btnStartRecording);
        btnPlayRecording = findViewById(R.id.btnPlayRecording);
        btnDiscardRecording = findViewById(R.id.btnDiscardRecording);
        btnSubmit = findViewById(R.id.btnSubmitEnrollment);
        progressRecord = findViewById(R.id.progressRecord);
        progressSubmitting = findViewById(R.id.progressSubmitting);

        if (existingName != null) {
            etSpeakerName.setText(existingName);
        }

        btnBack.setOnClickListener(v -> finish());
        btnStartRecording.setOnClickListener(v -> startRecording());
        btnPlayRecording.setOnClickListener(v -> playRecording());
        btnDiscardRecording.setOnClickListener(v -> discardRecording());
        btnSubmit.setOnClickListener(v -> submitEnrollment());

        progressRecord.setMax(100);
        progressRecord.setProgress(0);
        progressSubmitting.setVisibility(View.GONE);

        refreshEnrolledNames();
        updateUiState();
    }

    private void ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    /** Opens the enrollment stream; the hub answers an {@code enroll_request} on the same session. */
    private void connectEcosys() {
        EcosysStreamClient client =
                new EcosysStreamClient(hubHost, EcosysStreamClient.DEFAULT_HUB_PORT, ECOSYS_DEVICE_ID);
        client.addListener(new EcosysStreamClient.Listener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onFrame(StreamFrame frame) {
                if (frame == null
                        || frame.getPayloadCase() != StreamFrame.PayloadCase.ENROLL_RESPONSE) {
                    return;
                }
                boolean ok = frame.getEnrollResponse().getOk();
                String reason = frame.getEnrollResponse().getReason();
                runOnUiThread(() -> handleEnrollResponse(ok, reason));
            }

            @Override
            public void onDisconnected(Throwable cause) {
            }
        });
        ecosysClient = client;
        client.start();
    }

    private void startRecording() {
        if (isRecording || isSubmitting) {
            return;
        }
        stopPlayback();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            Toast.makeText(this, "需要麥克風權限才能錄音", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (recorder != null) {
                recorder.release();
            }
            recorder = new EnrollmentRecorder();
            recorder.start();
        } catch (Exception e) {
            recorder = null;
            Toast.makeText(this, "無法開始錄音：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        isRecording = true;
        hasRecording = false;
        recordStartMs = SystemClock.elapsedRealtime();
        progressRecord.setProgress(0);
        tvStatus.setText("錄音中… 0.0 / 8.0 秒");
        updateUiState();
        handler.post(recordTick);
    }

    private void stopRecording() {
        handler.removeCallbacks(recordTick);
        if (!isRecording || recorder == null) {
            return;
        }
        recorder.stop();
        isRecording = false;
        progressRecord.setProgress(100);

        byte[] pcm = recorder.getPcm16Data();
        hasRecording = pcm.length >= MIN_ENROLLMENT_BYTES;
        if (hasRecording) {
            int seconds = pcm.length / (EnrollmentRecorder.SAMPLE_RATE * 2);
            tvStatus.setText("錄音完成（" + seconds + " 秒），輸入名稱後按「完成註冊」");
        } else {
            tvStatus.setText("錄音不足 8 秒，請重新錄製");
        }
        updateUiState();
    }

    private void discardRecording() {
        handler.removeCallbacks(recordTick);
        if (isRecording) {
            isRecording = false;
            if (recorder != null) {
                recorder.stop();
            }
        }
        stopPlayback();
        hasRecording = false;
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        progressRecord.setProgress(0);
        tvStatus.setText("請按「開始錄音（8 秒）」重新錄製");
        updateUiState();
    }

    private void playRecording() {
        if (!hasRecording || recorder == null) {
            return;
        }
        stopPlayback();
        try {
            File tempDir = new File(getCacheDir(), "enrollment_preview");
            File wavFile = recorder.writeWavFile(tempDir, "preview.wav");
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(wavFile.getAbsolutePath());
            mediaPlayer.setOnCompletionListener(mp -> stopPlayback());
            mediaPlayer.prepare();
            mediaPlayer.start();
            tvStatus.setText("播放錄音中…");
        } catch (IOException e) {
            Toast.makeText(this, "無法播放錄音：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
                // The player may already be stopped; playback is best-effort.
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void submitEnrollment() {
        if (isSubmitting) {
            return;
        }
        if (!hasRecording || recorder == null) {
            Toast.makeText(this, "請先錄製 8 秒語音", Toast.LENGTH_SHORT).show();
            return;
        }

        String name = etSpeakerName.getText().toString();
        String invalidReason = EnrollmentName.invalidReason(name);
        if (invalidReason != null) {
            Toast.makeText(this, invalidReason, Toast.LENGTH_LONG).show();
            tvStatus.setText(invalidReason);
            return;
        }

        byte[] pcm = recorder.getPcm16Data();
        if (pcm.length < MIN_ENROLLMENT_BYTES) {
            Toast.makeText(this, "錄音不足 8 秒", Toast.LENGTH_SHORT).show();
            tvStatus.setText("錄音不足 8 秒，請重新錄製");
            hasRecording = false;
            updateUiState();
            return;
        }

        EcosysStreamClient client = ecosysClient;
        if (client == null) {
            Toast.makeText(this, "中樞連線未就緒", Toast.LENGTH_SHORT).show();
            return;
        }

        submissionName = name;
        isSubmitting = true;
        progressSubmitting.setVisibility(View.VISIBLE);
        tvStatus.setText("正在註冊「" + name + "」…");
        updateUiState();

        try {
            client.send(StreamFrame.newBuilder()
                    .setEnrollRequest(EnrollRequest.newBuilder()
                            .setName(name)
                            .setAudioPcm1616K(ByteString.copyFrom(pcm))
                            .setPtsMs(System.currentTimeMillis())
                            .build())
                    .build());
        } catch (IllegalStateException notConnected) {
            onEnrollmentFailed("尚未連上中樞，請確認已連上熱點");
            return;
        }
        handler.postDelayed(enrollTimeout, ENROLL_TIMEOUT_MS);
    }

    private void handleEnrollResponse(boolean ok, String reason) {
        handler.removeCallbacks(enrollTimeout);
        progressSubmitting.setVisibility(View.GONE);
        isSubmitting = false;

        if (!ok) {
            onEnrollmentFailed(messageForReason(reason));
            return;
        }

        persistEnrollment(submissionName);
        tvStatus.setText("註冊成功：" + submissionName);
        Toast.makeText(this, "已註冊：" + submissionName, Toast.LENGTH_SHORT).show();
        updateUiState();
        handler.postDelayed(() -> {
            setResult(RESULT_OK, new Intent().putExtra("updatedSpeaker", submissionName));
            finish();
        }, 800);
    }

    private void onEnrollmentFailed(String message) {
        handler.removeCallbacks(enrollTimeout);
        progressSubmitting.setVisibility(View.GONE);
        isSubmitting = false;
        tvStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        updateUiState();
    }

    /** Maps the hub's machine reason code to a short human message. */
    private static String messageForReason(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "註冊失敗，請重試";
        }
        switch (reason) {
            case "invalid-name":
                return "名稱無效：只可使用中文字、英文字母及數字（1–24 字）";
            case "audio-too-short":
                return "錄音不足 8 秒，請重新錄製";
            case "voiceprint-failed":
                return "無法產生聲紋，請再試一次";
            default:
                return "註冊失敗：" + reason;
        }
    }

    /**
     * Mirrors a hub-accepted name locally so the list and the Settings screen can
     * show it and offer re-enrollment. The VOICEPRINT itself lives only on the
     * hub; this is a display cache plus a local WAV sample for preview.
     */
    private void persistEnrollment(String name) {
        try {
            File dir = new File(getFilesDir(), "enrollments");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("無法建立註冊資料夾");
            }
            String sanitized = name.replaceAll("[^a-zA-Z0-9]", "_");
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            File wavFile = recorder.writeWavFile(dir, sanitized + "_" + timeStamp + ".wav");

            long now = System.currentTimeMillis();
            long createdAt = now;
            boolean muted = false;
            for (EnrolledSpeaker existing : EnrolledSpeakerStore.getSpeakers(this)) {
                if (existing.getName() != null && existing.getName().equalsIgnoreCase(name)) {
                    createdAt = existing.getCreatedAt();
                    muted = existing.isMuted();
                    break;
                }
            }
            EnrolledSpeaker speaker = new EnrolledSpeaker(
                    name,
                    languageCode,
                    new ArrayList<>(),
                    wavFile.getAbsolutePath(),
                    createdAt,
                    now,
                    muted);

            if (!TextUtils.isEmpty(existingAudioPath)) {
                File oldFile = new File(existingAudioPath);
                if (oldFile.exists() && !oldFile.getAbsolutePath().equals(wavFile.getAbsolutePath())) {
                    //noinspection ResultOfMethodCallIgnored
                    oldFile.delete();
                }
            }

            EnrolledSpeakerStore.addOrReplace(this, speaker);
        } catch (IOException e) {
            Toast.makeText(this, "無法儲存本機註冊資訊：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        refreshEnrolledNames();
    }

    private void refreshEnrolledNames() {
        List<EnrolledSpeaker> speakers = EnrolledSpeakerStore.getSpeakers(this);
        if (speakers.isEmpty()) {
            tvEnrolledNames.setText("已註冊：無");
            return;
        }
        StringBuilder builder = new StringBuilder("已註冊：");
        for (int i = 0; i < speakers.size(); i++) {
            if (i > 0) {
                builder.append("、");
            }
            builder.append(speakers.get(i).getName());
        }
        tvEnrolledNames.setText(builder.toString());
    }

    private void updateUiState() {
        boolean idle = !isRecording && !isSubmitting;
        btnStartRecording.setEnabled(idle);
        btnStartRecording.setAlpha(idle ? 1f : 0.6f);
        btnDiscardRecording.setEnabled(hasRecording && idle);
        btnPlayRecording.setEnabled(hasRecording && idle);
        btnSubmit.setEnabled(hasRecording && idle);
        etSpeakerName.setEnabled(idle);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPlayback();
        if (isRecording) {
            handler.removeCallbacks(recordTick);
            if (recorder != null) {
                recorder.stop();
            }
            isRecording = false;
            hasRecording = false;
            progressRecord.setProgress(0);
            updateUiState();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        stopPlayback();
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        if (ecosysClient != null) {
            ecosysClient.close();
            ecosysClient = null;
        }
    }
}
