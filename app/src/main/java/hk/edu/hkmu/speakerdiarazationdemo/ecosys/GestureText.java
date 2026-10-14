package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

/**
 * Pure mapping of an inbound {@code ecosys.v1.GestureResult} to the text the
 * gesture overlay shows.
 *
 * <p>The hub fills {@code text} with the spoken meaning of the recognized sign
 * and {@code label} with the raw recognizer class (for example {@code Thumb_Up}).
 * The meaning is what the user reads, so it wins; the label is only a fallback so
 * a partial/older hub still shows something instead of an empty overlay.
 *
 * <p>Deliberately Android-free, so the preference/blank-handling rules are
 * unit-testable on a plain JVM without a device or emulator.
 */
public final class GestureText {

    private GestureText() {
        // Utility class.
    }

    /**
     * The text to show for a gesture frame, or {@code null} when the frame
     * carries nothing displayable (both fields null/blank). The returned value is
     * trimmed, so a caller can treat null as "ignore this frame".
     */
    public static String displayText(String text, String label) {
        String meaning = normalize(text);
        if (meaning != null) {
            return meaning;
        }
        return normalize(label);
    }

    /** True when {@link #displayText(String, String)} would return a value. */
    public static boolean isDisplayable(String text, String label) {
        return displayText(text, label) != null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
