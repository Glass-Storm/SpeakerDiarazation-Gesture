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

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    public synchronized void loadConfig(Context context) throws Exception {
        try (InputStream inputStream = context.getResources().openRawResource(R.raw.config_default);
             InputStreamReader reader = new InputStreamReader(inputStream)) {
            Gson gson = new Gson();
            config = gson.fromJson(reader, AppConfig.class);
        }
        if (config == null) {
            throw new IllegalStateException("config_default.json is empty or malformed");
        }
        if (RuntimeConfigStore.hasApiKey(context)) {
            config.setApiKey(RuntimeConfigStore.getApiKey(context));
        }
        config.setRegion(RuntimeConfigStore.getRegion(context));
    }

    public AppConfig getConfig() {
        return config;
    }
}
