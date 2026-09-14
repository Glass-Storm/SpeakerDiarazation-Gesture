package hk.edu.hkmu.speakerdiarazationdemo;

import android.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hk.edu.hkmu.speakerdiarazationdemo.models.AppConfig;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

public class SpeechmaticsEnrollmentClient extends WebSocketListener {
    private static final String TAG = "EnrollmentClient";
    private static final String TOKEN_URL = "https://mp.speechmatics.com/v1/api_keys?type=rt";
    private static final int AUDIO_CHUNK_SIZE = 8096;

    public interface Callback {
        void onSuccess(List<String> identifiers);
        void onError(String error);
    }

    private final OkHttpClient httpClient;
    private final AppConfig config;
    private WebSocket webSocket;
    private Callback callback;
    private byte[] pendingAudio;
    private String language;
    private String sessionId;
    private boolean speakersRequested = false;
    private boolean audioStreamed = false;
    private int lastSeqNo = -1;

    public SpeechmaticsEnrollmentClient(AppConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void enroll(byte[] audioFloat32, String language, Callback callback) {
        this.callback = callback;
        this.pendingAudio = audioFloat32;
        this.language = language;
        this.sessionId = null;
        this.speakersRequested = false;
        this.audioStreamed = false;
        this.lastSeqNo = -1;

        new Thread(() -> {
            try {
                String token = generateTempToken(config.getApiKey());
                String wsUrl = RuntimeConfigStore.regionToWsUrl(config.getRegion()) + "?jwt=" + token;
                Request request = new Request.Builder().url(wsUrl).build();
                webSocket = httpClient.newWebSocket(request, this);
            } catch (Exception e) {
                Log.e(TAG, "Enrollment connection error", e);
                if (callback != null) {
                    if (e instanceof UnknownHostException) {
                        callback.onError("無法連線至 Speechmatics 伺服器，請確認網路或 DNS 設定");
                    } else {
                        callback.onError("連接失敗: " + e.getMessage());
                    }
                }
            }
        }).start();
    }

    private String generateTempToken(String apiKey) throws Exception {
        JSONObject json = new JSONObject();
        json.put("ttl", 600);

        RequestBody body = RequestBody.create(
                json.toString(),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(TOKEN_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : null;
            if (response.isSuccessful() && responseBody != null) {
                JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
                return jsonObject.get("key_value").getAsString();
            } else {
                String errorDetails = responseBody != null ? (": " + responseBody) : "";
                throw new Exception("取得 Token 失敗: " + response.code() + errorDetails);
            }
        }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        Log.d(TAG, "Enrollment websocket opened");

        try {
            JSONObject startMessage = new JSONObject();
            startMessage.put("message", "StartRecognition");

            JSONObject audioFormat = new JSONObject();
            audioFormat.put("type", "raw");
            audioFormat.put("encoding", "pcm_f32le");
            audioFormat.put("sample_rate", 16000);
            startMessage.put("audio_format", audioFormat);

            JSONObject transcriptionConfig = new JSONObject();
            transcriptionConfig.put("language", language);
            transcriptionConfig.put("enable_partials", false);
            transcriptionConfig.put("operating_point", config.getOperatingPoint());
            transcriptionConfig.put("max_delay", config.getMaxDelaySeconds());
            transcriptionConfig.put("max_delay_mode", config.getMaxDelayMode());
            transcriptionConfig.put("diarization", "speaker");

            JSONObject speakerConfig = new JSONObject();
            speakerConfig.put("max_speakers", 2);
            speakerConfig.put("speaker_sensitivity", config.getSpeakerSensitivity());
            transcriptionConfig.put("speaker_diarization_config", speakerConfig);

            startMessage.put("transcription_config", transcriptionConfig);

            webSocket.send(startMessage.toString());
            Log.d(TAG, "Enrollment StartRecognition sent");
        } catch (Exception e) {
            Log.e(TAG, "Error sending enrollment start message", e);
            notifyError("啟動辨識失敗: " + e.getMessage());
        }
    }

    private void streamBufferedAudioIfReady() {
        if (audioStreamed) {
            return;
        }

        if (webSocket == null) {
            return;
        }

        if (pendingAudio == null || pendingAudio.length == 0) {
            notifyError("音訊緩衝區為空，無法開始註冊。");
            return;
        }

        int offset = 0;
        while (offset < pendingAudio.length) {
            int length = Math.min(AUDIO_CHUNK_SIZE, pendingAudio.length - offset);
            webSocket.send(ByteString.of(pendingAudio, offset, length));
            offset += length;
            lastSeqNo++;
        }

        audioStreamed = true;
        sendEndOfStream();
        requestSpeakers();
    }

    private void sendEndOfStream() {
        if (webSocket == null || lastSeqNo < 0) {
            return;
        }
        try {
            JSONObject endMessage = new JSONObject();
            endMessage.put("message", "EndOfStream");
            endMessage.put("last_seq_no", lastSeqNo);
            webSocket.send(endMessage.toString());
            Log.d(TAG, "Enrollment EndOfStream sent with last_seq_no=" + lastSeqNo);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send EndOfStream", e);
        }
    }

    private void requestSpeakers() {
        if (webSocket == null || speakersRequested) {
            return;
        }
        try {
            JSONObject getSpeakers = new JSONObject();
            getSpeakers.put("message", "GetSpeakers");
            if (sessionId != null) {
                getSpeakers.put("session_id", sessionId);
            }
            getSpeakers.put("final", true);
            webSocket.send(getSpeakers.toString());
            speakersRequested = true;
            Log.d(TAG, "Enrollment GetSpeakers(final=true) sent");
        } catch (Exception e) {
            Log.e(TAG, "Failed to request enrolled speakers", e);
            notifyError("取得說話者代碼失敗: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JsonObject data = JsonParser.parseString(text).getAsJsonObject();
            String messageType = data.get("message").getAsString();

            if ("RecognitionStarted".equals(messageType)) {
                sessionId = data.has("session_id") ? data.get("session_id").getAsString() : null;
                Log.d(TAG, "Enrollment session started: " + sessionId);
                streamBufferedAudioIfReady();
            } else if ("SpeakersResult".equals(messageType)) {
                List<String> identifiers = new ArrayList<>();
                if (data.has("speakers")) {
                    JsonArray speakers = data.getAsJsonArray("speakers");
                    for (int i = 0; i < speakers.size(); i++) {
                        JsonObject speaker = speakers.get(i).getAsJsonObject();
                        if (speaker.has("speaker_identifiers")) {
                            JsonArray ids = speaker.getAsJsonArray("speaker_identifiers");
                            for (int j = 0; j < ids.size(); j++) {
                                String id = ids.get(j).getAsString();
                                if (!identifiers.contains(id)) {
                                    identifiers.add(id);
                                }
                            }
                        }
                    }
                }
                notifySuccess(identifiers);
                close();
            } else if ("Error".equals(messageType)) {
                notifyError("API 錯誤: " + text);
                close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing enrollment message", e);
        }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        Log.e(TAG, "Enrollment websocket failure", t);
        notifyError("WebSocket 連線失敗: " + t.getMessage());
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        Log.d(TAG, "Enrollment websocket closed: " + reason);
    }

    public void close() {
        if (webSocket != null) {
            webSocket.close(1000, "enrollment finished");
            webSocket = null;
        }
        speakersRequested = false;
        audioStreamed = false;
        lastSeqNo = -1;
    }

    private void notifySuccess(List<String> identifiers) {
        if (callback != null) {
            callback.onSuccess(identifiers);
        }
    }

    private void notifyError(String message) {
        if (callback != null) {
            callback.onError(message);
        }
    }
}
