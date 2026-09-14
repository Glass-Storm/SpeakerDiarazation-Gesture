package hk.edu.hkmu.speakerdiarazationdemo;

import android.content.Context;
import com.google.gson.Gson;
import hk.edu.hkmu.speakerdiarazationdemo.models.AppConfig;
import java.io.InputStream;
import java.io.InputStreamReader;

public class ConfigManager {
    private AppConfig config;
    private static ConfigManager instance;

    private ConfigManager() {}

    public static ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    public void loadConfig(Context context) throws Exception {
        InputStream inputStream = context.getResources().openRawResource(R.raw.config_default);
        InputStreamReader reader = new InputStreamReader(inputStream);
        Gson gson = new Gson();
        config = gson.fromJson(reader, AppConfig.class);
        reader.close();
    }

    public AppConfig getConfig() {
        return config;
    }
}
