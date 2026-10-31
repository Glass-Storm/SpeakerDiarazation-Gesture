package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the deaf/hearing {@link SessionMode} chosen on the first screen.
 *
 * <p>Kept separate from {@link SessionMode} so the enum stays pure and testable;
 * only this thin wrapper touches Android storage.
 */
public final class SessionModeStore {

    private static final String PREFS_NAME = "ecosys_session";
    private static final String KEY_MODE = "session_mode";

    private SessionModeStore() {
    }

    /** The stored mode, or {@code null} when the user has not chosen yet. */
    public static SessionMode load(Context context) {
        return SessionMode.fromStoredName(prefs(context).getString(KEY_MODE, null));
    }

    public static boolean hasMode(Context context) {
        return load(context) != null;
    }

    public static void save(Context context, SessionMode mode) {
        if (mode == null) {
            clear(context);
            return;
        }
        prefs(context).edit().putString(KEY_MODE, mode.name()).apply();
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_MODE).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
