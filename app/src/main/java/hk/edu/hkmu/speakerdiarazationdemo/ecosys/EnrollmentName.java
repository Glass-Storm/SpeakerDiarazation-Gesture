package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import java.util.regex.Pattern;

/**
 * The voiceprint-enrollment name policy: Han characters, ASCII letters and
 * digits only, with no whitespace or punctuation, 1–24 characters long.
 *
 * <p>This is a deliberately tiny, Android-free class so the rule is unit-testable
 * on a plain JVM without a device. It mirrors the HUB-OWNED policy exactly
 * ({@code ^[\p{Han}A-Za-z0-9]+$}, max 24): the hub rejects an invalid name with
 * {@code reason=invalid-name}, so the glasses validate first to fail fast with a
 * clear local message instead of a round trip.
 */
public final class EnrollmentName {

    /** Maximum accepted name length, matching the hub's policy. */
    public static final int MAX_LENGTH = 24;

    /** The hub's {@code ^[\p{Han}A-Za-z0-9]+$}, in the spelling {@code java.util.regex} accepts. */
    private static final Pattern ALLOWED = Pattern.compile("^[\\p{IsHan}A-Za-z0-9]+$");

    private EnrollmentName() {
        // Utility class.
    }

    /** Whether {@code name} is an acceptable display name. */
    public static boolean isValid(String name) {
        return name != null
                && name.length() >= 1
                && name.length() <= MAX_LENGTH
                && ALLOWED.matcher(name).matches();
    }

    /**
     * The human-readable rejection reason for {@code name}, or {@code null} when
     * it is valid. The caller shows this directly, so it stays a short sentence
     * rather than a machine code.
     */
    public static String invalidReason(String name) {
        if (name == null || name.isEmpty()) {
            return "名稱不可為空";
        }
        if (name.length() > MAX_LENGTH) {
            return "名稱不可超過 " + MAX_LENGTH + " 個字";
        }
        if (!ALLOWED.matcher(name).matches()) {
            return "名稱只可使用中文字、英文字母及數字，不可包含空格或標點";
        }
        return null;
    }
}
