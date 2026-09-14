package hk.edu.hkmu.speakerdiarazationdemo.models;

import java.util.Map;

public class AppConfig {
    private String api_key;
    private String language;
    private String region;
    private Double timeout_seconds;
    private String operating_point;
    private String max_delay_mode;
    private Boolean enable_partials;
    private String diarization;
    private Integer max_speakers;
    private Double speaker_sensitivity;
    private Map<String, String> speaker_names;
    private Double end_of_utterance_silence_trigger;
    private Double audio_filter_volume_threshold;

    public String getApiKey() {
        return api_key;
    }

    public void setApiKey(String api_key) {
        this.api_key = api_key;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public double getMaxDelaySeconds() {
        double value = timeout_seconds != null ? timeout_seconds : 4.0;
        if (value < 0.7) {
            value = 0.7;
        } else if (value > 4.0) {
            value = 4.0;
        }
        return value;
    }

    public String getRegion() {
        return region != null ? region : "us";
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getOperatingPoint() {
        return operating_point != null ? operating_point : "enhanced";
    }

    public String getMaxDelayMode() {
        return max_delay_mode != null ? max_delay_mode : "flexible";
    }

    public boolean isEnablePartials() {
        return enable_partials == null || enable_partials;
    }

    public String getDiarization() {
        return diarization != null ? diarization : "speaker";
    }

    public int getMaxSpeakers() {
        return max_speakers != null && max_speakers > 0 ? max_speakers : 10;
    }

    public double getSpeakerSensitivity() {
        double value = speaker_sensitivity != null ? speaker_sensitivity : 0.5;
        if (value < 0.0) {
            value = 0.0;
        } else if (value > 1.0) {
            value = 1.0;
        }
        return value;
    }

    public Map<String, String> getSpeakerNames() {
        return speaker_names;
    }

    public double getEndOfUtteranceSilenceTrigger() {
        if (end_of_utterance_silence_trigger == null) {
            return 0.0;
        }
        double value = end_of_utterance_silence_trigger;
        if (value < 0.0) {
            value = 0.0;
        } else if (value > 2.0) {
            value = 2.0;
        }
        // Keep trigger lower than or equal to max delay to avoid premature flush.
        double maxDelay = getMaxDelaySeconds();
        if (maxDelay > 0 && value > maxDelay) {
            value = Math.max(0.0, maxDelay - 0.1);
        }
        return value;
    }

    public Double getAudioFilterVolumeThreshold() {
        if (audio_filter_volume_threshold == null) {
            return null;
        }
        double clamped = audio_filter_volume_threshold;
        if (clamped < 0.0) {
            clamped = 0.0;
        } else if (clamped > 100.0) {
            clamped = 100.0;
        }
        return clamped;
    }

    public String getSpeakerName(String speakerId) {
        if (speaker_names != null && speaker_names.containsKey(speakerId)) {
            return speaker_names.get(speakerId);
        }
        return speakerId;
    }
}
