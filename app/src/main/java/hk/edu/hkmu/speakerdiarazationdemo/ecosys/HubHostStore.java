package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the last hub host the glasses connected to, so the DEBUG page can
 * dial the same phone without re-running gateway detection.
 *
 * <p>{@link #save(Context, String)} is called when a session starts (the connect
 * screen and the main screen); {@link #load(Context)} is called by the debug
 * page. The SoftAP gateway is the last-resort fallback, mirroring the main
 * screen's default.
 */
public final class HubHostStore {

    /** SoftAP gateway of the phone hub when nothing has been saved yet. */
    public static final String DEFAULT_HUB_HOST = "192.168.43.1";

    private static final String PREFS_NAME = "app_settings";
    private static final String PREF_LAST_HUB_HOST = "last_hub_host";

    private HubHostStore() {
        // Utility class.
    }

    /** Remembers {@code host} unless it is null/blank. */
    public static void save(Context context, String host) {
        if (context == null) {
            return;
        }
        String normalized = normalize(host);
        if (normalized == null) {
            return;
        }
        preferences(context).edit().putString(PREF_LAST_HUB_HOST, normalized).apply();
    }

    /** The last saved host, or {@link #DEFAULT_HUB_HOST} when none is usable. */
    public static String load(Context context) {
        String saved = null;
        if (context != null) {
            saved = preferences(context).getString(PREF_LAST_HUB_HOST, null);
        }
        String normalized = normalize(saved);
        return normalized == null ? DEFAULT_HUB_HOST : normalized;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String normalize(String host) {
        if (host == null) {
            return null;
        }
        String trimmed = host.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
