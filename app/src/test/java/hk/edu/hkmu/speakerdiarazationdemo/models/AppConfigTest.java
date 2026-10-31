package hk.edu.hkmu.speakerdiarazationdemo.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

import java.io.File;
import java.io.FileReader;

/**
 * Pins the config mapping the silent hotspot join depends on: {@code wifi_ssid}
 * and {@code wifi_password} parse into {@link AppConfig} without disturbing the
 * existing {@code language} handling.
 */
public class AppConfigTest {

    private static final String WIFI_CONFIG_JSON = "{"
            + "\"language\":\"yue\","
            + "\"wifi_ssid\":\"EcoSys-Hub\","
            + "\"wifi_password\":\"ecosys-demo-2026\""
            + "}";

    @Test
    public void parsesWifiCredentialsAndLanguage() {
        AppConfig config = new Gson().fromJson(WIFI_CONFIG_JSON, AppConfig.class);

        assertNotNull(config);
        assertEquals("EcoSys-Hub", config.getWifiSsid());
        assertEquals("ecosys-demo-2026", config.getWifiPassword());
        assertEquals("yue", config.getLanguage());
    }

    @Test
    public void settersRoundTripWifiCredentials() {
        AppConfig config = new AppConfig();

        config.setWifiSsid("EcoSys-Hub");
        config.setWifiPassword("ecosys-demo-2026");

        assertEquals("EcoSys-Hub", config.getWifiSsid());
        assertEquals("ecosys-demo-2026", config.getWifiPassword());
    }

    @Test
    public void shippedDefaultsCarryTheDemoHotspot() throws Exception {
        File defaults = new File("src/main/res/raw/config_default.json");
        assertTrue("missing " + defaults.getAbsolutePath(), defaults.isFile());

        try (FileReader reader = new FileReader(defaults)) {
            AppConfig config = new Gson().fromJson(reader, AppConfig.class);
            assertNotNull(config);
            assertEquals("EcoSys-Hub", config.getWifiSsid());
            assertEquals("ecosys-demo-2026", config.getWifiPassword());
            assertEquals("yue", config.getLanguage());
        }
    }
}
