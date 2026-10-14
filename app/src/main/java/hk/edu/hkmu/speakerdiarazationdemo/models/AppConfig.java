package hk.edu.hkmu.speakerdiarazationdemo.models;

import java.util.Map;

/** App-level defaults parsed from {@code res/raw/config_default.json}. */
public class AppConfig {
    private String language;
    private Map<String, String> speaker_names;

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Map<String, String> getSpeakerNames() {
        return speaker_names;
    }

    public String getSpeakerName(String speakerId) {
        if (speaker_names != null && speaker_names.containsKey(speakerId)) {
            return speaker_names.get(speakerId);
        }
        return speakerId;
    }
}
