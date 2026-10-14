package hk.edu.hkmu.speakerdiarazationdemo;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.ResolveInfo;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import hk.edu.hkmu.speakerdiarazationdemo.EnrolledSpeakerStore;
import hk.edu.hkmu.speakerdiarazationdemo.adapters.TranscriptListAdapter;
import hk.edu.hkmu.speakerdiarazationdemo.models.EnrolledSpeaker;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "app_settings";
    private static final String PREF_FONT_SIZE_SP = "font_size_sp";
    private static final String PREF_LANGUAGE_CODE = "language_code";
    public static final String PREF_TTS_ENGINE_PACKAGE = "tts_engine_package";
    private static final Pattern INVALID_FILENAME_PATTERN = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final float DEFAULT_FONT_SIZE_SP = 24f;
    private static final int MIN_FONT_SIZE_SP = 24;
    private static final int MAX_FONT_SIZE_SP = 48;
    private static final int MAX_FILENAME_LENGTH = 80;

    private SeekBar seekFontSize;
    private TextView tvFontSizeLabel;
    private TextView tvFontPreview;
    private Button btnTtsSetup;
    private Button btnTtsTest;
    private Spinner spinnerTtsEngine;
    private LinearLayout containerEnrolledSpeakers;
    private RecyclerView rvTranscripts;
    private TextView tvNoSpeakersHint;
    private TextView tvNoTranscriptsHint;
    private Button btnAddSpeaker;
    private Button btnLoadMoreTranscripts;
    private Button btnClearTranscripts;
    private TextView tvTranscriptPath;

    private SharedPreferences preferences;
    private MediaPlayer mediaPlayer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private AudioManager audioManager;
    private AudioManager.OnAudioFocusChangeListener ttsFocusListener;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private static final int TTS_STATE_NONE = 0;
    private static final int TTS_STATE_INITING = 1;
    private static final int TTS_STATE_READY = 2;
    private static final int TTS_STATE_FAILED = 3;
    private int ttsState = TTS_STATE_NONE;
    private long ttsInitStartedAtMs = 0L;
    private final List<String> ttsEngines = new ArrayList<>();
    private int ttsEngineIndex = 0;
    private final List<String> ttsEngineLabels = new ArrayList<>();
    private ActivityResultLauncher<Intent> enrollmentLauncher;
    private TranscriptListAdapter transcriptAdapter;
    private final List<File> allTranscriptFiles = new ArrayList<>();
    private static final int TRANSCRIPT_PAGE_SIZE = 20;
    private int loadedTranscriptCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        loadTtsEngines();
        initViews();
        initFontSizeControls();
        initEnrollmentSection();
        initTranscriptSection();
        // Kick off TTS init early so the test button won't feel "stuck".
        ensureTts();

        enrollmentLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        refreshSpeakerList();
                        refreshTranscriptList();
                    }
                }
        );
    }

    private void initViews() {
        ImageButton btnBack = findViewById(R.id.btnBack);
        seekFontSize = findViewById(R.id.seekFontSize);
        tvFontSizeLabel = findViewById(R.id.tvFontSizeLabel);
        tvFontPreview = findViewById(R.id.tvFontPreview);
        Button btnSaveSettings = findViewById(R.id.btnSaveSettings);
        btnTtsSetup = findViewById(R.id.btnTtsSetup);
        btnTtsTest = findViewById(R.id.btnTtsTest);
        spinnerTtsEngine = findViewById(R.id.spinnerTtsEngine);

        containerEnrolledSpeakers = findViewById(R.id.containerEnrolledSpeakers);
        rvTranscripts = findViewById(R.id.rvTranscripts);
        tvNoSpeakersHint = findViewById(R.id.tvNoSpeakersHint);
        tvNoTranscriptsHint = findViewById(R.id.tvNoAudioFilesHint);
        btnAddSpeaker = findViewById(R.id.btnAddSpeaker);
        btnLoadMoreTranscripts = findViewById(R.id.btnLoadMoreTranscripts);
        btnClearTranscripts = findViewById(R.id.btnClearTranscripts);
        tvTranscriptPath = findViewById(R.id.tvTranscriptPath);

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        btnSaveSettings.setOnClickListener(v -> saveFontSize());

        if (btnTtsSetup != null) {
            btnTtsSetup.setOnClickListener(v -> openTtsSetup());
        }
        if (btnTtsTest != null) {
            btnTtsTest.setOnClickListener(v -> testTts());
        }

        setupTtsEngineSpinner();
    }

    private void initFontSizeControls() {
        float savedSize = getSavedFontSize();
        int range = MAX_FONT_SIZE_SP - MIN_FONT_SIZE_SP;
        seekFontSize.setMax(range);
        int initialProgress = Math.round(savedSize - MIN_FONT_SIZE_SP);
        initialProgress = Math.max(0, Math.min(initialProgress, range));
        seekFontSize.setProgress(initialProgress);

        updateFontSizeDisplay(savedSize);

        seekFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float fontSize = MIN_FONT_SIZE_SP + progress;
                updateFontSizeDisplay(fontSize);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                persistFontSize(getSelectedFontSize());
            }
        });
    }

    @Override
    protected void onPause() {
        persistFontSize(getSelectedFontSize());
        super.onPause();
    }

    private float getSelectedFontSize() {
        if (seekFontSize == null) {
            return getSavedFontSize();
        }
        return MIN_FONT_SIZE_SP + seekFontSize.getProgress();
    }

    private void persistFontSize(float fontSize) {
        if (preferences == null) {
            return;
        }
        float value = fontSize;
        if (value < MIN_FONT_SIZE_SP) value = MIN_FONT_SIZE_SP;
        if (value > MAX_FONT_SIZE_SP) value = MAX_FONT_SIZE_SP;
        preferences.edit().putFloat(PREF_FONT_SIZE_SP, value).apply();
    }

    private void initEnrollmentSection() {
        btnAddSpeaker.setOnClickListener(v -> launchEnrollment(null));
        refreshSpeakerList();
    }

    private void initTranscriptSection() {
        setupTranscriptRecycler();
        refreshTranscriptList();
    }

    private void launchEnrollment(EnrolledSpeaker existingSpeaker) {
        Intent intent = new Intent(this, SpeakerEnrollmentActivity.class);
        intent.putExtra(SpeakerEnrollmentActivity.EXTRA_LANGUAGE, getCurrentLanguage());
        if (existingSpeaker != null) {
            intent.putExtra(SpeakerEnrollmentActivity.EXTRA_EXISTING_NAME, existingSpeaker.getName());
            intent.putExtra(SpeakerEnrollmentActivity.EXTRA_EXISTING_AUDIO_PATH, existingSpeaker.getAudioPath());
        }
        enrollmentLauncher.launch(intent);
    }

    private void refreshSpeakerList() {
        stopPlayback();
        containerEnrolledSpeakers.removeAllViews();

        List<EnrolledSpeaker> speakers = EnrolledSpeakerStore.getSpeakers(this);
        if (speakers.isEmpty()) {
            tvNoSpeakersHint.setVisibility(View.VISIBLE);
            return;
        }
        tvNoSpeakersHint.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        for (EnrolledSpeaker speaker : speakers) {
            View itemView = inflater.inflate(R.layout.item_enrolled_speaker, containerEnrolledSpeakers, false);
            TextView tvName = itemView.findViewById(R.id.tvSpeakerName);
            TextView tvDetail = itemView.findViewById(R.id.tvSpeakerDetails);
            CheckBox cbMute = itemView.findViewById(R.id.cbMuteSpeaker);
            Button btnPlay = itemView.findViewById(R.id.btnPlaySample);
            Button btnRerecord = itemView.findViewById(R.id.btnReRecord);
            Button btnDelete = itemView.findViewById(R.id.btnDeleteSpeaker);

            tvName.setText(speaker.getName());
            tvName.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light));

            String language = speaker.getLanguage() != null ? speaker.getLanguage() : "yue";
            String createdAt = sdf.format(new Date(speaker.getCreatedAt()));
            tvDetail.setText(String.format(Locale.getDefault(), "語言：%s  |  建立：%s", language, createdAt));

            btnPlay.setOnClickListener(v -> {
                if (!TextUtils.isEmpty(speaker.getAudioPath())) {
                    playEnrollmentSample(new File(speaker.getAudioPath()));
                } else {
                    Toast.makeText(this, "沒有可用的錄音樣本", Toast.LENGTH_SHORT).show();
                }
            });

            btnRerecord.setOnClickListener(v -> launchEnrollment(speaker));

            btnDelete.setOnClickListener(v -> showDeleteSpeakerDialog(speaker));

            if (cbMute != null) {
                cbMute.setOnCheckedChangeListener(null);
                cbMute.setChecked(speaker.isMuted());
                cbMute.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    speaker.setMuted(isChecked);
                    EnrolledSpeakerStore.addOrReplace(this, speaker);
                    String toastMessage = isChecked
                            ? "已隱藏「" + speaker.getName() + "」的輸出"
                            : "已恢復「" + speaker.getName() + "」的輸出";
                    Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
                });
            }

            containerEnrolledSpeakers.addView(itemView);
        }
    }

    private void refreshTranscriptList() {
        stopPlayback();
        File dir = getTranscriptDirectory();
        if (tvTranscriptPath != null) {
            tvTranscriptPath.setText(dir != null
                    ? getString(R.string.transcript_path_format, dir.getAbsolutePath())
                    : getString(R.string.transcript_path_unavailable));
        }
        File[] files = dir != null
                ? dir.listFiles((file, name) -> name != null && name.endsWith(".txt"))
                : null;
        allTranscriptFiles.clear();
        loadedTranscriptCount = 0;
        transcriptAdapter.clear();

        if (files != null && files.length > 0) {
            Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
            allTranscriptFiles.addAll(Arrays.asList(files));
            tvNoTranscriptsHint.setVisibility(View.GONE);
            loadNextTranscriptPage(true);
        } else {
            tvNoTranscriptsHint.setVisibility(View.VISIBLE);
            updateTranscriptControlsVisibility();
        }
    }

    private void showDeleteSpeakerDialog(EnrolledSpeaker speaker) {
        new AlertDialog.Builder(this)
                .setTitle("刪除說話者")
                .setMessage("確定要刪除「" + speaker.getName() + "」嗎？")
                .setPositiveButton("刪除", (dialog, which) -> {
                    EnrolledSpeakerStore.remove(this, speaker.getName());
                    if (!TextUtils.isEmpty(speaker.getAudioPath())) {
                        File file = new File(speaker.getAudioPath());
                        if (file.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            file.delete();
                        }
                    }
                    refreshSpeakerList();
                    refreshTranscriptList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showTranscriptDialog(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            if (builder.length() == 0) {
                builder.append("（空）");
            }
            new AlertDialog.Builder(this)
                    .setTitle(file.getName())
                    .setMessage(builder.toString())
                    .setPositiveButton("關閉", null)
                    .show();
        } catch (IOException e) {
            Toast.makeText(this, "讀取文字記錄失敗：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showDeleteTranscriptDialog(File file) {
        new AlertDialog.Builder(this)
                .setTitle("刪除文字記錄")
                .setMessage("確定要刪除「" + file.getName() + "」嗎？")
                .setPositiveButton("刪除", (dialog, which) -> {
                    stopPlayback();
                    if (!file.delete()) {
                        Toast.makeText(this, "刪除失敗", Toast.LENGTH_SHORT).show();
                    } else {
                        handleTranscriptDeleted(file);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void playEnrollmentSample(File file) {
        stopPlayback();
        if (!file.exists()) {
            Toast.makeText(this, "找不到錄音樣本", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setOnCompletionListener(mp -> stopPlayback());
            mediaPlayer.prepare();
            mediaPlayer.start();
            Toast.makeText(this, "正在播放錄音樣本…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "無法播放錄音：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            stopPlayback();
        }
    }

    private void saveFontSize() {
        persistFontSize(getSelectedFontSize());
        Toast.makeText(this, "字體大小已更新", Toast.LENGTH_SHORT).show();
        finish();
    }

    private float getSavedFontSize() {
        if (preferences != null) {
            float value = preferences.getFloat(PREF_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP);
            if (value < MIN_FONT_SIZE_SP) value = MIN_FONT_SIZE_SP;
            if (value > MAX_FONT_SIZE_SP) value = MAX_FONT_SIZE_SP;
            return value;
        }
        return DEFAULT_FONT_SIZE_SP;
    }

    private void openTtsSetup() {
        try {
            Intent intent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                // Some devices hide TTS settings; try opening the engine settings page.
                Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
                startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
                // Fall back to Play Store search.
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    // On many devices the actual TTS engine package is Google Text-to-Speech.
                    intent.setData(Uri.parse("market://details?id=com.google.android.tts"));
                    startActivity(intent);
                } catch (ActivityNotFoundException ignored2) {
                    Toast.makeText(this, "找不到 TTS 設定頁，請到 Play 商店安裝 Google Text-to-Speech（com.google.android.tts）", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void loadTtsEngines() {
        ttsEngines.clear();
        ttsEngineLabels.clear();
        try {
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            List<ResolveInfo> services = getPackageManager().queryIntentServices(intent, 0);
            Set<String> uniquePackages = new LinkedHashSet<>();
            if (services != null) {
                for (ResolveInfo info : services) {
                    if (info.serviceInfo != null && info.serviceInfo.packageName != null) {
                        uniquePackages.add(info.serviceInfo.packageName);
                    }
                }
            }
            // Prefer Google engine if present.
            if (uniquePackages.remove("com.google.android.tts")) {
                ttsEngines.add("com.google.android.tts");
            }
            ttsEngines.addAll(uniquePackages);

            for (String pkg : ttsEngines) {
                try {
                    CharSequence label = getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(pkg, 0)
                    );
                    ttsEngineLabels.add(label + " (" + pkg + ")");
                } catch (Throwable ignored) {
                    ttsEngineLabels.add(pkg);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void setupTtsEngineSpinner() {
        if (spinnerTtsEngine == null) {
            return;
        }
        List<String> items = new ArrayList<>();
        items.add("預設（系統）");
        items.addAll(ttsEngineLabels);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            items
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTtsEngine.setAdapter(adapter);

        String saved = preferences != null ? preferences.getString(PREF_TTS_ENGINE_PACKAGE, null) : null;
        int initialIndex = 0;
        if (saved != null) {
            int pkgIndex = ttsEngines.indexOf(saved);
            if (pkgIndex >= 0) {
                initialIndex = pkgIndex + 1; // +1 because "Default" is 0
            }
        }
        spinnerTtsEngine.setSelection(initialIndex, false);

        spinnerTtsEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String pkg = position <= 0 ? null : ttsEngines.get(position - 1);
                if (preferences != null) {
                    preferences.edit().putString(PREF_TTS_ENGINE_PACKAGE, pkg).apply();
                }
                // Re-init with the newly selected engine next time.
                resetTts();
                ensureTts();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void resetTts() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Throwable ignored) {}
            tts = null;
        }
        ttsReady = false;
        ttsState = TTS_STATE_NONE;
        ttsEngineIndex = 0;
        abandonTtsFocus();
    }

    private void ensureTts() {
        if (tts != null || ttsState == TTS_STATE_INITING || ttsState == TTS_STATE_READY) {
            return;
        }
        ttsReady = false;
        ttsState = TTS_STATE_INITING;
        ttsInitStartedAtMs = System.currentTimeMillis();
        uiHandler.postDelayed(() -> {
            if (ttsState == TTS_STATE_INITING && (System.currentTimeMillis() - ttsInitStartedAtMs) > 6000L) {
                failOrTryNextTtsEngine("TTS 初始化逾時");
            }
        }, 6500L);

        String engineName = null;
        String preferred = preferences != null ? preferences.getString(PREF_TTS_ENGINE_PACKAGE, null) : null;
        if (preferred != null && ttsEngines.contains(preferred)) {
            engineName = preferred;
        } else if (!ttsEngines.isEmpty() && ttsEngineIndex >= 0 && ttsEngineIndex < ttsEngines.size()) {
            engineName = ttsEngines.get(ttsEngineIndex);
        }
        final String selectedEngineName = engineName;
        try {
            tts = selectedEngineName == null
                ? new TextToSpeech(this, status -> onTtsInit(status, null))
                : new TextToSpeech(this, status -> onTtsInit(status, selectedEngineName), selectedEngineName);
        } catch (Throwable t) {
            failOrTryNextTtsEngine("TTS 初始化例外：" + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
        }
    }

    private void onTtsInit(int status, String engineName) {
        ttsReady = status == TextToSpeech.SUCCESS;
        if (!ttsReady) {
            failOrTryNextTtsEngine("TTS 初始化失敗" + (engineName != null ? "（" + engineName + "）" : ""));
            return;
        }
        ttsState = TTS_STATE_READY;
        tts.setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            );
            int result = tts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "TTS 語音資料未安裝或不支援，請先下載語音資料", Toast.LENGTH_LONG).show();
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {}

                @Override
                public void onDone(String utteranceId) {
                    abandonTtsFocus();
                }

                @Override
                public void onError(String utteranceId) {
                    abandonTtsFocus();
                }
            });

        String engineLabel = engineName != null ? engineName : tts.getDefaultEngine();
        Toast.makeText(this, "TTS 已就緒（" + engineLabel + "）", Toast.LENGTH_SHORT).show();
    }

    private void failOrTryNextTtsEngine(String message) {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Throwable ignored) {}
            tts = null;
        }
        ttsReady = false;
        abandonTtsFocus();

        String preferred = preferences != null ? preferences.getString(PREF_TTS_ENGINE_PACKAGE, null) : null;
        if (preferred != null) {
            ttsState = TTS_STATE_FAILED;
            Toast.makeText(this, message + "：目前選擇的引擎不可用，請更換 TTS 引擎", Toast.LENGTH_LONG).show();
            return;
        }

        if (!ttsEngines.isEmpty() && ttsEngineIndex + 1 < ttsEngines.size()) {
            ttsEngineIndex++;
            ttsState = TTS_STATE_NONE;
            Toast.makeText(this, message + "，改用其他引擎…", Toast.LENGTH_SHORT).show();
            uiHandler.post(this::ensureTts);
            return;
        }

        ttsState = TTS_STATE_FAILED;
        Toast.makeText(this, message + "：請先安裝/啟用 TTS 引擎（建議 Google Text-to-Speech）", Toast.LENGTH_LONG).show();
    }

    private boolean requestTtsFocus() {
        if (audioManager == null) {
            return true;
        }
        if (ttsFocusListener == null) {
            ttsFocusListener = focusChange -> {};
        }
        int result = audioManager.requestAudioFocus(
            ttsFocusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        );
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonTtsFocus() {
        if (audioManager == null || ttsFocusListener == null) {
            return;
        }
        try {
            audioManager.abandonAudioFocus(ttsFocusListener);
        } catch (Throwable ignored) {}
    }

    private void testTts() {
        ensureTts();
        if (ttsState == TTS_STATE_FAILED) {
            Toast.makeText(this, "TTS 無法使用：請先安裝/啟用 Google Text-to-Speech，或下載語音資料", Toast.LENGTH_LONG).show();
            openTtsSetup();
            return;
        }
        if (tts == null || !ttsReady || ttsState == TTS_STATE_INITING) {
            Toast.makeText(this, "TTS 正在初始化，請稍等再按一次", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!requestTtsFocus()) {
            Toast.makeText(this, "目前無法取得音訊輸出焦點，請檢查音量/勿擾/藍牙輸出", Toast.LENGTH_LONG).show();
            return;
        }
        String utterance = "Hi. Thank you.";
        tts.speak(utterance, TextToSpeech.QUEUE_FLUSH, null, "tts_test");
        Toast.makeText(this, "正在播報：" + utterance, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Throwable ignored) {
            }
            tts = null;
        }
        ttsState = TTS_STATE_NONE;
        abandonTtsFocus();
    }

    private String getCurrentLanguage() {
        if (preferences != null) {
            return preferences.getString(PREF_LANGUAGE_CODE, "yue");
        }
        return "yue";
    }

    private void updateFontSizeDisplay(float fontSize) {
        if (tvFontSizeLabel != null) {
            tvFontSizeLabel.setText(String.format(Locale.getDefault(), "%.0f sp", fontSize));
        }
        if (tvFontPreview != null) {
            tvFontPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        }
    }

    private File getTranscriptDirectory() {
        File dir = new File(getFilesDir(), "transcripts");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void setupTranscriptRecycler() {
        rvTranscripts.setLayoutManager(new LinearLayoutManager(this));
        rvTranscripts.setNestedScrollingEnabled(false);
        rvTranscripts.setHasFixedSize(false);
        transcriptAdapter = new TranscriptListAdapter(new TranscriptListAdapter.Callback() {
            @Override
            public void onViewTranscript(File file) {
                showTranscriptDialog(file);
            }

            @Override
            public void onDeleteTranscript(File file) {
                showDeleteTranscriptDialog(file);
            }

            @Override
            public void onRenameTranscript(File file) {
                promptRenameTranscript(file);
            }
        });
        rvTranscripts.setAdapter(transcriptAdapter);

        btnLoadMoreTranscripts.setOnClickListener(v -> loadNextTranscriptPage(false));
        btnClearTranscripts.setOnClickListener(v -> confirmClearAllTranscripts());
    }

    private void loadNextTranscriptPage(boolean firstPage) {
        if (loadedTranscriptCount >= allTranscriptFiles.size()) {
            updateTranscriptControlsVisibility();
            return;
        }
        int end = Math.min(loadedTranscriptCount + TRANSCRIPT_PAGE_SIZE, allTranscriptFiles.size());
        List<File> next = new ArrayList<>(allTranscriptFiles.subList(loadedTranscriptCount, end));
        if (firstPage) {
            transcriptAdapter.setItems(next);
        } else {
            transcriptAdapter.appendItems(next);
        }
        loadedTranscriptCount = end;
        updateTranscriptControlsVisibility();
    }

    private void updateTranscriptControlsVisibility() {
        boolean hasItems = !allTranscriptFiles.isEmpty();
        btnClearTranscripts.setVisibility(hasItems ? View.VISIBLE : View.GONE);
        boolean hasMore = loadedTranscriptCount < allTranscriptFiles.size();
        btnLoadMoreTranscripts.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        if (!hasItems) {
            transcriptAdapter.clear();
        }
    }

    private void handleTranscriptDeleted(File file) {
        allTranscriptFiles.remove(file);
        transcriptAdapter.removeItem(file);
        loadedTranscriptCount = Math.min(loadedTranscriptCount, allTranscriptFiles.size());
        Toast.makeText(this, "已刪除", Toast.LENGTH_SHORT).show();
        if (transcriptAdapter.getItemCount() == 0 && !allTranscriptFiles.isEmpty()) {
            loadNextTranscriptPage(true);
        }
        if (allTranscriptFiles.isEmpty()) {
            tvNoTranscriptsHint.setVisibility(View.VISIBLE);
        }
        updateTranscriptControlsVisibility();
    }

    private void confirmClearAllTranscripts() {
        if (allTranscriptFiles.isEmpty()) {
            Toast.makeText(this, "沒有可刪除的記錄", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("刪除全部記錄")
                .setMessage("確定要刪除全部文字記錄嗎？此動作無法復原。")
                .setPositiveButton("刪除", (dialog, which) -> clearAllTranscripts())
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearAllTranscripts() {
        stopPlayback();
        boolean hasFailure = false;
        for (File file : new ArrayList<>(allTranscriptFiles)) {
            if (!file.delete()) {
                hasFailure = true;
            }
        }
        if (hasFailure) {
            Toast.makeText(this, "部分檔案刪除失敗", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "已刪除所有記錄", Toast.LENGTH_SHORT).show();
        }
        refreshTranscriptList();
    }

    private void promptRenameTranscript(File file) {
        File dir = getTranscriptDirectory();
        if (dir == null) {
            Toast.makeText(this, "無法取得儲存路徑", Toast.LENGTH_SHORT).show();
            return;
        }

        final EditText input = new EditText(this);
        String originalName = file.getName();
        if (originalName.endsWith(".txt")) {
            originalName = originalName.substring(0, originalName.length() - 4);
        }
        input.setText(originalName);
        input.setSelection(originalName.length());
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_FILENAME_LENGTH)});

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("重新命名")
                .setView(input)
                .setPositiveButton("儲存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();

        Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positiveButton.setOnClickListener(v -> {
            String newName = input.getText().toString().trim();
            if (TextUtils.isEmpty(newName)) {
                input.setError("名稱不可為空");
                return;
            }
            if (INVALID_FILENAME_PATTERN.matcher(newName).find()) {
                input.setError("名稱含有不允許的符號 \\ / : * ? \" < > |");
                return;
            }

            String finalName = newName.endsWith(".txt") ? newName : newName + ".txt";
            File target = new File(dir, finalName);
            if (target.exists()) {
                input.setError("已存在同名記錄");
                return;
            }

            boolean success = file.renameTo(target);
            if (success) {
                // 將時間戳更新為目前時間，確保重新命名後維持在清單頂端
                target.setLastModified(System.currentTimeMillis());
                Toast.makeText(this, "已重新命名", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                refreshTranscriptList();
            } else {
                Toast.makeText(this, "重新命名失敗", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
