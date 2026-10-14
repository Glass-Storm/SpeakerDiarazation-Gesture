package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps an incoming {@code ecosys.v1.StreamResult.speaker_label} to the colour its
 * subtitle tag is drawn in.
 *
 * <p>The hub sends an <b>empty</b> label for a speaker it could not match to an
 * enrolled voiceprint, and a <b>named</b> label otherwise. The rule is therefore:
 *
 * <ul>
 *   <li>empty, whitespace, or a generic "unknown" sentinel &rarr; {@link
 *       #UNKNOWN_SPEAKER_COLOUR}, the <b>one shared colour</b> every unregistered
 *       speaker uses (never a per-unknown colour);</li>
 *   <li>a labelled speaker &rarr; its own colour &mdash; the {@code S1..S5}
 *       palette the app already used, or {@link #NAMED_SPEAKER_COLOUR} for an
 *       enrolled display name.</li>
 * </ul>
 *
 * <p>Deliberately Android-free: it stores plain ARGB {@code int} values rather
 * than calling {@code android.graphics.Color}, so it is unit-testable on a plain
 * JVM without a device or emulator.
 */
public final class SpeakerColourMap {

    /** The single shared colour drawn for every unregistered/empty speaker label. */
    public static final int UNKNOWN_SPEAKER_COLOUR = 0xFFFFFFFF;

    /** The colour drawn for a named (enrolled) speaker label; the existing #2196F3. */
    public static final int NAMED_SPEAKER_COLOUR = 0xFF2196F3;

    private static final Map<String, Integer> PALETTE;

    static {
        Map<String, Integer> palette = new HashMap<>();
        put(palette, "S1", 0xFFFF6347); // Red
        put(palette, "S2", 0xFFFFA500); // Orange
        put(palette, "S3", 0xFFFFD700); // Gold
        put(palette, "S4", 0xFF32CD32); // Green
        put(palette, "S5", 0xFF1E90FF); // Blue
        PALETTE = Collections.unmodifiableMap(palette);
    }

    private SpeakerColourMap() {
        // Utility class.
    }

    private static void put(Map<String, Integer> palette, String label, int colour) {
        palette.put(label, colour);
        palette.put(label.toLowerCase(java.util.Locale.ROOT), colour);
    }

    /**
     * Resolves the colour for {@code speakerLabel}. Null-safe and empty-safe:
     * any missing label yields {@link #UNKNOWN_SPEAKER_COLOUR} rather than
     * throwing.
     */
    public static int colourFor(String speakerLabel) {
        if (speakerLabel == null) {
            return UNKNOWN_SPEAKER_COLOUR;
        }
        String trimmed = speakerLabel.trim();
        if (trimmed.isEmpty() || isUnknownSentinel(trimmed)) {
            return UNKNOWN_SPEAKER_COLOUR;
        }
        Integer paletteColour = PALETTE.get(trimmed);
        if (paletteColour != null) {
            return paletteColour;
        }
        return NAMED_SPEAKER_COLOUR;
    }

    /** True when {@code speakerLabel} resolves to the shared unknown colour. */
    public static boolean isUnknown(String speakerLabel) {
        return colourFor(speakerLabel) == UNKNOWN_SPEAKER_COLOUR;
    }

    private static boolean isUnknownSentinel(String label) {
        return "unknown".equalsIgnoreCase(label) || "unregistered".equalsIgnoreCase(label);
    }
}
