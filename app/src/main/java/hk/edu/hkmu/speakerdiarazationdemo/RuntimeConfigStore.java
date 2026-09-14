package hk.edu.hkmu.speakerdiarazationdemo;

import android.content.Context;
import android.content.SharedPreferences;

public final class RuntimeConfigStore {
    private static final String PREFS_NAME = "runtime_config";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_REGION = "region";
    private static final String DEFAULT_REGION = "us";
    private static final String PLACEHOLDER_API_KEY = "YOUR_SPEECHMATICS_API_KEY";

    private RuntimeConfigStore() {
        // Utility class
    }

    public static String getApiKey(Context context) {
        return getPrefs(context).getString(KEY_API_KEY, "");
    }

    public static void setApiKey(Context context, String apiKey) {
        String trimmed = (apiKey != null ? apiKey.trim() : "");
        if (trimmed.isEmpty()) {
            getPrefs(context).edit().remove(KEY_API_KEY).apply();
        } else {
            getPrefs(context).edit().putString(KEY_API_KEY, trimmed).apply();
        }
    }

    public static String getRegion(Context context) {
        return getPrefs(context).getString(KEY_REGION, DEFAULT_REGION);
    }

    public static void setRegion(Context context, String region) {
        if (region == null) {
            return;
        }
        String lower = region.trim().toLowerCase();
        if (lower.equals("global") || lower.equals("eu") || lower.equals("us") || lower.equals("au")) {
            getPrefs(context).edit().putString(KEY_REGION, lower).apply();
        }
        // silently ignore unknown values
    }

    public static boolean hasApiKey(Context context) {
        String key = getApiKey(context);
        return !key.isEmpty() && !key.equals(PLACEHOLDER_API_KEY);
    }

    public static String regionToWsUrl(String region) {
        if (region == null) {
            return "wss://us.rt.speechmatics.com/v2";
        }
        switch (region.toLowerCase()) {
            case "global":
                return "wss://global.rt.speechmatics.com/v2";
            case "eu":
                return "wss://eu.rt.speechmatics.com/v2";
            case "us":
                return "wss://us.rt.speechmatics.com/v2";
            case "au":
                return "wss://au.rt.speechmatics.com/v2";
            default:
                return "wss://us.rt.speechmatics.com/v2";
        }
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
