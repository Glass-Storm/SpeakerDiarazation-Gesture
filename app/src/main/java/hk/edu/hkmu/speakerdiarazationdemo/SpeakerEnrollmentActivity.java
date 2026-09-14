package hk.edu.hkmu.speakerdiarazationdemo;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import hk.edu.hkmu.speakerdiarazationdemo.audio.EnrollmentRecorder;
import hk.edu.hkmu.speakerdiarazationdemo.EnrolledSpeakerStore;
import hk.edu.hkmu.speakerdiarazationdemo.ConfigManager;
import hk.edu.hkmu.speakerdiarazationdemo.models.AppConfig;
import hk.edu.hkmu.speakerdiarazationdemo.models.EnrolledSpeaker;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SpeakerEnrollmentActivity extends AppCompatActivity {
    public static final String EXTRA_LANGUAGE = "extra_language";
    public static final String EXTRA_EXISTING_NAME = "extra_existing_name";
    public static final String EXTRA_EXISTING_AUDIO_PATH = "extra_existing_audio_path";

    private static final String[] SAMPLE_SENTENCES_ZH = {
            "\u4eca\u5929\u7684\u5929\u6c23\u4e0d\u932f\uff0c\u6211\u5011\u51fa\u53bb\u8d70\u8d70\u5427\u3002",
            "\u8acb\u4fdd\u6301\u5fc3\u60c5\u8f15\u9b06\uff0c\u81ea\u7136\u5730\u8b80\u5b8c\u9019\u6bb5\u8a71\u3002",
            "\u5728\u9019\u88e1\u8a3b\u518a\u8072\u97f3\uff0c\u53ef\u4ee5\u8b93\u6211\u8a8d\u51fa\u4f60\u3002"
    };

    private static final String[] SAMPLE_SENTENCES_YUE = {
            "\u4eca\u65e5\u5605\u5929\u6c23\u5514\u932f\u558e\uff0c\u4e0d\u5982\u6211\u54cb\u51fa\u53bb\u884c\u4e0b\u5566\u3002",
            "\u653e\u8f15\u9b06\uff0c\u81ea\u7136\u5481\u8b1b\uff0c\u5514\u4f7f\u592a\u523b\u610f\u3002",
            "\u767b\u8a18\u8072\u7d0b\u5f8c\uff0c\u4e0b\u6b21\u5c31\u6703\u77e5\u9053\u4f60\u4fc2\u908a\u4f4d\u3002"
    };

    private static final String[] SAMPLE_SENTENCES_EN = {
            "The weather is great today, let's go for a walk.",
            "Relax, take a deep breath, and read this sentence clearly.",
            "Registering your voice helps me recognise you next time."
    };

    private TextView tvSample;
    private TextView tvStatus;
    private View btnHoldRecord;
    private View btnPlay;
    private View btnSubmit;
    private View btnDiscard;
    private ProgressBar progressBar;

    private EnrollmentRecorder recorder;
    private MediaPlayer mediaPlayer;
    private boolean hasRecording = false;
    private boolean isRecording = false;
    private boolean isSubmitting = false;
    private String languageCode;
    private String existingName;
    private String existingAudioPath;

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

        languageCode = getIntent().getStringExtra(EXTRA_LANGUAGE);
        if (languageCode == null) {
            languageCode = "yue";
        }
        existingName = getIntent().getStringExtra(EXTRA_EXISTING_NAME);
        existingAudioPath = getIntent().getStringExtra(EXTRA_EXISTING_AUDIO_PATH);

        initViews();
        ensureMicPermission();
    }

    private void initViews() {
        ImageButton btnBack = findViewById(R.id.btnClose);
        tvSample = findViewById(R.id.tvSampleSentence);
        tvStatus = findViewById(R.id.tvStatus);
        btnHoldRecord = findViewById(R.id.btnHoldToRecord);
        btnPlay = findViewById(R.id.btnPlayRecording);
        btnSubmit = findViewById(R.id.btnSubmitEnrollment);
        btnDiscard = findViewById(R.id.btnDiscardRecording);
        progressBar = findViewById(R.id.progressEnrollment);

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        updateSampleSentence();
        updateUiState();

        btnHoldRecord.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startRecording();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopRecording();
                    return true;
                default:
                    return false;
            }
        });

        btnPlay.setOnClickListener(v -> playRecording());
        btnDiscard.setOnClickListener(v -> discardRecording());
        btnSubmit.setOnClickListener(v -> submitEnrollment());
    }

    private void ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void updateSampleSentence() {
        String[] sentences = SAMPLE_SENTENCES_YUE;
        switch (languageCode) {
            case "cmn":
                sentences = SAMPLE_SENTENCES_ZH;
                break;
            case "en":
                sentences = SAMPLE_SENTENCES_EN;
                break;
        }
        int index = (int) (System.currentTimeMillis() % sentences.length);
        tvSample.setText(sentences[index]);
    }

    private void startRecording() {
        if (isRecording || isSubmitting) {
            return;
        }
        stopPlayback();

        try {
            if (recorder != null) {
                recorder.release();
            }
            recorder = new EnrollmentRecorder();
            recorder.start();
            isRecording = true;
            hasRecording = false;
            tvStatus.setText("\u9304\u97f3\u4e2d\uff0c\u8acb\u4fdd\u6301\u8aaa\u8a71\u2026");
            btnHoldRecord.setPressed(true);
        } catch (Exception e) {
            Toast.makeText(this, "無法開始錄音：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            isRecording = false;
            recorder = null;
        }

        updateUiState();
    }

    private void stopRecording() {
        if (!isRecording || recorder == null) {
            return;
        }
        recorder.stop();
        isRecording = false;
        hasRecording = recorder.getPcm16Data().length > 0;
        tvStatus.setText(hasRecording
                ? "\u9304\u97f3\u5b8c\u6210\uff0c\u53ef\u64ad\u653e\u6216\u9001\u51fa"
                : "\u672a\u6355\u6349\u5230\u97f3\u8a0a\uff0c\u8acb\u91cd\u65b0\u9304\u88fd");
        btnHoldRecord.setPressed(false);
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
            tvStatus.setText("\u64ad\u653e\u9304\u97f3\u4e2d\u2026");
        } catch (IOException e) {
            Toast.makeText(this, "無法播放錄音：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (!isRecording && !isSubmitting) {
            tvStatus.setText(hasRecording
                    ? "\u9304\u97f3\u5b8c\u6210\uff0c\u53ef\u64ad\u653e\u6216\u9001\u51fa"
                    : "\u8acb\u6309\u4f4f\u6309\u9215\u958b\u59cb\u9304\u97f3");
        }
    }

    private void discardRecording() {
        stopPlayback();
        hasRecording = false;
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        tvStatus.setText("\u8acb\u6309\u4f4f\u6309\u9215\u958b\u59cb\u9304\u97f3");
        updateUiState();
    }

    private void submitEnrollment() {
        if (!hasRecording || recorder == null) {
            Toast.makeText(this, "請先錄音", Toast.LENGTH_SHORT).show();
            return;
        }
        byte[] pcm = recorder.getPcm16Data();
        if (pcm.length < EnrollmentRecorder.SAMPLE_RATE) {
            Toast.makeText(this, "\u9304\u97f3\u592a\u77ed\uff0c\u8acb\u81f3\u5c11\u8aaa 1 \u79d2", Toast.LENGTH_SHORT).show();
            return;
        }

        tvStatus.setText("\u6b63\u5728\u4e0a\u50b3\u4e26\u8a3b\u518a\uff0c\u8acb\u7a0d\u5019..." );
        progressBar.setVisibility(View.VISIBLE);
        isSubmitting = true;
        updateUiState();

        byte[] floatData = recorder.getFloat32leData();
        AppConfig appConfig = ensureConfig();
        if (appConfig == null) {
            progressBar.setVisibility(View.GONE);
            tvStatus.setText("無法載入設定，請重試");
            isSubmitting = false;
            updateUiState();
            return;
        }

        if (!RuntimeConfigStore.hasApiKey(this)) {
            Toast.makeText(this, "請先於設定 → Speechmatics 連線設定輸入 Speechmatics API 金鑰", Toast.LENGTH_LONG).show();
            return;
        }

        SpeechmaticsEnrollmentClient client = new SpeechmaticsEnrollmentClient(appConfig);
        client.enroll(floatData, languageCode, new SpeechmaticsEnrollmentClient.Callback() {
            @Override
            public void onSuccess(List<String> identifiers) {
                runOnUiThread(() -> handleEnrollmentSuccess(identifiers, client));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("\u8a3b\u518a\u5931\u6557\uff0c\u8acb\u91cd\u8a66");
                    isSubmitting = false;
                    updateUiState();
                    Toast.makeText(SpeakerEnrollmentActivity.this, error, Toast.LENGTH_LONG).show();
                });
                client.close();
            }
        });
    }

    private void handleEnrollmentSuccess(List<String> identifiers, SpeechmaticsEnrollmentClient client) {
        progressBar.setVisibility(View.GONE);
        isSubmitting = false;
        updateUiState();
        client.close();

        if (identifiers == null || identifiers.isEmpty()) {
            Toast.makeText(this, "\u6c92\u6709\u53d6\u5f97\u8aaa\u8a71\u8005\u4ee3\u78bc\uff0c\u8acb\u91cd\u65b0\u9304\u88fd", Toast.LENGTH_SHORT).show();
            return;
        }

        showNameInputDialog(identifiers);
    }

    private void showNameInputDialog(List<String> identifiers) {
        final androidx.appcompat.widget.AppCompatEditText input =
                new androidx.appcompat.widget.AppCompatEditText(this);
        input.setText(existingName != null ? existingName : "");
        input.setTextColor(ContextCompat.getColor(this, android.R.color.black));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("\u8acb\u8f38\u5165\u8aaa\u8a71\u8005\u540d\u7a31")
                .setView(input)
                .setPositiveButton("儲存", null)
                .setNegativeButton("取消", (d, which) -> {})
                .create();

        dialog.setOnShowListener(dlg ->
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) {
                        Toast.makeText(this, "名稱不可為空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    persistEnrollment(name, identifiers);
                    dialog.dismiss();
                })
        );

        dialog.show();
    }

    private void persistEnrollment(String speakerName, List<String> identifiers) {
        try {
            File dir = new File(getFilesDir(), "enrollments");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("無法建立註冊資料夾");
            }
            String sanitizedName = speakerName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = sanitizedName + "_" + timeStamp + ".wav";
            File wavFile = recorder.writeWavFile(dir, fileName);

            long now = System.currentTimeMillis();
            long createdAt = now;
            boolean muted = false;
            List<EnrolledSpeaker> existing = EnrolledSpeakerStore.getSpeakers(this);
            for (EnrolledSpeaker enrolledSpeaker : existing) {
                if (enrolledSpeaker.getName().equalsIgnoreCase(speakerName)) {
                    createdAt = enrolledSpeaker.getCreatedAt();
                    muted = enrolledSpeaker.isMuted();
                    break;
                }
            }
            EnrolledSpeaker speaker = new EnrolledSpeaker(
                    speakerName,
                    languageCode,
                    new ArrayList<>(identifiers),
                    wavFile.getAbsolutePath(),
                    createdAt,
                    now,
                    muted
            );

            if (!TextUtils.isEmpty(existingAudioPath) && !existingAudioPath.equals(wavFile.getAbsolutePath())) {
                File oldFile = new File(existingAudioPath);
                if (oldFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    oldFile.delete();
                }
            }

            EnrolledSpeakerStore.addOrReplace(this, speaker);
            Toast.makeText(this, "已儲存說話者：" + speakerName, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK, new Intent().putExtra("updatedSpeaker", speakerName));
            finish();
        } catch (IOException e) {
            Toast.makeText(this, "\u5132\u5b58\u9304\u97f3\u5931\u6557\u003a\u0020" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private AppConfig ensureConfig() {
        AppConfig cfg = ConfigManager.getInstance().getConfig();
        if (cfg == null) {
            try {
                ConfigManager.getInstance().loadConfig(this);
                cfg = ConfigManager.getInstance().getConfig();
            } catch (Exception e) {
                Toast.makeText(this, "\u8b80\u53d6\u914d\u7f6e\u5931\u6557\u003a\u0020" + e.getMessage(), Toast.LENGTH_LONG).show();
                return null;
            }
        }
        return cfg;
    }

    private void updateUiState() {
        boolean controlsEnabled = !isRecording && !isSubmitting;
        btnPlay.setEnabled(hasRecording && controlsEnabled);
        btnSubmit.setEnabled(hasRecording && controlsEnabled);
        btnDiscard.setEnabled(hasRecording && controlsEnabled);
        btnHoldRecord.setEnabled(!isSubmitting);
        btnHoldRecord.setAlpha(isSubmitting ? 0.6f : 1f);

        if (!hasRecording) {
            if (isRecording) {
                tvStatus.setText("\u9304\u97f3\u4e2d\uff0c\u8acb\u4fdd\u6301\u8aaa\u8a71\u2026");
            } else if (!isSubmitting) {
                tvStatus.setText("\u8acb\u6309\u4f4f\u6309\u9215\u958b\u59cb\u9304\u97f3");
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPlayback();
        if (recorder != null && isRecording) {
            recorder.stop();
            isRecording = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
    }
}
