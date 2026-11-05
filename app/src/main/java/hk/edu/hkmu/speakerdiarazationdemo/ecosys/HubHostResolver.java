package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

/**
 * Picks the hub host for a screen that talks to the hub on its own stream.
 *
 * <p>The main screen saves the host it actually connected to through
 * {@link HubHostStore}. Enrollment therefore prefers that stored host over any
 * stale intent extra, then the extra (used by tools/tests), then the SoftAP
 * gateway. Keeping this a pure function makes the precedence testable without
 * an Android runtime.
 */
public final class HubHostResolver {

    private HubHostResolver() {
        // Utility class.
    }

    /**
     * Resolves the host from highest to lowest priority: {@code storedHost},
     * {@code intentHost}, {@code fallbackHost}. Blank values are skipped and the
     * result is never null.
     */
    public static String resolve(String storedHost, String intentHost, String fallbackHost) {
        String resolved = normalize(storedHost);
        if (resolved != null) {
            return resolved;
        }
        resolved = normalize(intentHost);
        if (resolved != null) {
            return resolved;
        }
        resolved = normalize(fallbackHost);
        return resolved == null ? HubHostStore.DEFAULT_HUB_HOST : resolved;
    }

    private static String normalize(String host) {
        if (host == null) {
            return null;
        }
        String trimmed = host.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
