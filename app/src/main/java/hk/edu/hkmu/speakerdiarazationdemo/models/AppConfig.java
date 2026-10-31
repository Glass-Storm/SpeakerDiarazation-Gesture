package hk.edu.hkmu.speakerdiarazationdemo.models;

import java.util.Map;

/** App-level defaults parsed from {@code res/raw/config_default.json}. */
public class AppConfig {
    private String language;
    private Map<String, String> speaker_names;

    private String wifi_ssid;
    private String wifi_password;

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getWifiSsid() {
        return wifi_ssid;
    }

    public void setWifiSsid(String wifiSsid) {
        this.wifi_ssid = wifiSsid;
    }

    public String getWifiPassword() {
        return wifi_password;
    }

    public void setWifiPassword(String wifiPassword) {
        this.wifi_password = wifiPassword;
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
