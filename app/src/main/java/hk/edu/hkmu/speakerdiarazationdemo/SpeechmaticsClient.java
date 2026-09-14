package hk.edu.hkmu.speakerdiarazationdemo;

import android.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hk.edu.hkmu.speakerdiarazationdemo.models.AppConfig;
import hk.edu.hkmu.speakerdiarazationdemo.models.EnrolledSpeaker;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;

public class SpeechmaticsClient extends WebSocketListener {
    private static final String TAG = "SpeechmaticsClient";
    private static final String TOKEN_URL = "https://mp.speechmatics.com/v1/api_keys?type=rt";
    
    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private AppConfig config;
    private SpeechmaticsCallback callback;
    private TranscriptManager transcriptManager;
    private boolean isConnected = false;
    private final List<EnrolledSpeaker> enrolledSpeakers = new ArrayList<>();
    private String sessionId = null;

    public interface SpeechmaticsCallback {
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    public SpeechmaticsClient(AppConfig config, TranscriptManager transcriptManager, SpeechmaticsCallback callback) {
        this.config = config;
        this.transcriptManager = transcriptManager;
        this.callback = callback;
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    public void setEnrolledSpeakers(List<EnrolledSpeaker> speakers) {
        enrolledSpeakers.clear();
        if (speakers != null) {
            enrolledSpeakers.addAll(speakers);
        }
    }

    public void connect() {
        new Thread(() -> {
            try {
                String token = generateTempToken(config.getApiKey());
                Log.d(TAG, "JWT token generated successfully");
                
                String wsUrl = "wss://us.rt.speechmatics.com/v2?jwt=" + token;
                // TODO(T10): region map lands here — RuntimeConfigStore.regionToWsUrl(config.getRegion())
                Request request = new Request.Builder().url(wsUrl).build();
                webSocket = httpClient.newWebSocket(request, this);
                
            } catch (Exception e) {
                Log.e(TAG, "Connection error", e);
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
                throw new Exception("Token generation failed: " + response.code() + errorDetails);
            }
        }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        Log.d(TAG, "WebSocket connected");
        isConnected = true;
        
        try {
            JSONObject startMessage = new JSONObject();
            startMessage.put("message", "StartRecognition");
            
            JSONObject audioFormat = new JSONObject();
            audioFormat.put("type", "raw");
            audioFormat.put("encoding", "pcm_f32le");
            audioFormat.put("sample_rate", 16000);
            startMessage.put("audio_format", audioFormat);
            
            JSONObject transcriptionConfig = new JSONObject();
            transcriptionConfig.put("language", config.getLanguage());
            transcriptionConfig.put("enable_partials", config.isEnablePartials());
            transcriptionConfig.put("operating_point", config.getOperatingPoint());
            transcriptionConfig.put("max_delay", config.getMaxDelaySeconds());
            transcriptionConfig.put("max_delay_mode", config.getMaxDelayMode());
            double eouTrigger = config.getEndOfUtteranceSilenceTrigger();
            if (eouTrigger > 0.0) {
                JSONObject conversationConfig = new JSONObject();
                conversationConfig.put("end_of_utterance_silence_trigger", eouTrigger);
                transcriptionConfig.put("conversation_config", conversationConfig);
            }
            Double volumeThreshold = config.getAudioFilterVolumeThreshold();
            if (volumeThreshold != null) {
                JSONObject audioFilteringConfig = new JSONObject();
                audioFilteringConfig.put("volume_threshold", volumeThreshold);
                transcriptionConfig.put("audio_filtering_config", audioFilteringConfig);
            }

            String diarizationMode = config.getDiarization();
            if (diarizationMode != null && !diarizationMode.trim().isEmpty()) {
                transcriptionConfig.put("diarization", diarizationMode);
                if ("speaker".equalsIgnoreCase(diarizationMode)) {
                    JSONObject speakerConfig = new JSONObject();
                    speakerConfig.put("max_speakers", config.getMaxSpeakers());
                    speakerConfig.put("speaker_sensitivity", config.getSpeakerSensitivity());

                    if (!enrolledSpeakers.isEmpty()) {
                        JSONArray speakerArray = new JSONArray();
                        for (EnrolledSpeaker enrolled : enrolledSpeakers) {
                            if (enrolled == null) {
                                continue;
                            }
                            List<String> ids = enrolled.getSpeakerIdentifiers();
                            if (ids == null || ids.isEmpty()) {
                                continue;
                            }
                            JSONObject entry = new JSONObject();
                            entry.put("label", enrolled.getName());
                            JSONArray idArray = new JSONArray();
                            for (String id : ids) {
                                idArray.put(id);
                            }
                            entry.put("speaker_identifiers", idArray);
                            speakerArray.put(entry);
                        }
                        if (speakerArray.length() > 0) {
                            speakerConfig.put("speakers", speakerArray);
                        }
                    }

                    transcriptionConfig.put("speaker_diarization_config", speakerConfig);
                }
            }
            
            startMessage.put("transcription_config", transcriptionConfig);
            
            webSocket.send(startMessage.toString());
            Log.d(TAG, "Start recognition message sent");
            
            if (callback != null) {
                callback.onConnected();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending start message", e);
            if (callback != null) {
                callback.onError("啟動失敗: " + e.getMessage());
            }
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JsonObject data = JsonParser.parseString(text).getAsJsonObject();
            String messageType = data.get("message").getAsString();
            
            switch (messageType) {
                case "RecognitionStarted":
                    sessionId = data.has("session_id") ? data.get("session_id").getAsString() : null;
                    Log.d(TAG, "Recognition started");
                    break;
                    
                case "AddPartialTranscript":
                    if (data.has("metadata")) {
                        JsonObject metadata = data.getAsJsonObject("metadata");
                        if (metadata.has("transcript")) {
                            String transcript = metadata.get("transcript").getAsString();
                            if (transcriptManager != null) {
                                transcriptManager.addPartialTranscript(transcript);
                            }
                        }
                    }
                    break;
                    
                case "AddTranscript":
                    if (transcriptManager != null && data.has("results")) {
                        JsonArray results = data.getAsJsonArray("results");
                        for (int i = 0; i < results.size(); i++) {
                            JsonObject result = results.get(i).getAsJsonObject();
                            String resultType = result.has("type") ? result.get("type").getAsString() : "";

                            if (!result.has("alternatives")) {
                                continue;
                            }

                            JsonArray alternatives = result.getAsJsonArray("alternatives");
                            if (alternatives.size() == 0) {
                                continue;
                            }

                            JsonObject alt = alternatives.get(0).getAsJsonObject();

                            String speaker = alt.has("speaker") ? alt.get("speaker").getAsString() : "Unknown";
                            String content = alt.has("content") ? alt.get("content").getAsString() : "";
                            String display = null;
                            if (alt.has("display")) {
                                display = alt.get("display").getAsString();
                            } else if (alt.has("display_text")) {
                                display = alt.get("display_text").getAsString();
                            }
                            boolean isEos = alt.has("is_eos") && alt.get("is_eos").getAsBoolean();

                            transcriptManager.addToken(speaker, content, display, resultType, isEos, result);
                        }
                    }
                    break;
                    
                case "AudioAdded":
                    // Audio acknowledged
                    break;

                case "EndOfUtterance":
                    if (transcriptManager != null) {
                        transcriptManager.handleEndOfUtterance();
                    }
                    break;
                    
                case "Error":
                    String error = data.toString();
                    Log.e(TAG, "Speechmatics error: " + error);
                    if (callback != null) {
                        callback.onError("API錯誤: " + error);
                    }
                    break;
                    
                default:
                    Log.d(TAG, "Unknown message type: " + messageType);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing message", e);
        }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        Log.e(TAG, "WebSocket failure", t);
        isConnected = false;
        if (callback != null) {
            callback.onError("連接失敗: " + t.getMessage());
            callback.onDisconnected();
        }
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        Log.d(TAG, "WebSocket closing: " + reason);
        webSocket.close(1000, null);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        Log.d(TAG, "WebSocket closed: " + reason);
        isConnected = false;
        if (callback != null) {
            callback.onDisconnected();
        }
    }

    public void sendAudio(byte[] audioData) {
        if (webSocket != null && isConnected) {
            webSocket.send(ByteString.of(audioData));
        }
    }

    public void disconnect() {
        if (webSocket != null) {
            try {
                if (sessionId != null) {
                    JSONObject stopMessage = new JSONObject();
                    stopMessage.put("message", "StopRecognition");
                    stopMessage.put("session_id", sessionId);
                    webSocket.send(stopMessage.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending stop message", e);
            }

            webSocket.close(1000, "Client disconnect");
            webSocket = null;
        }
        sessionId = null;
        isConnected = false;
    }

    public boolean isConnected() {
        return isConnected;
    }
}
