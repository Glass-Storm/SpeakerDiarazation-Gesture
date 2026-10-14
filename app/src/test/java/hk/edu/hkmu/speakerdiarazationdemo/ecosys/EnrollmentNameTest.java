package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the hub-owned enrollment-name rule ({@code ^[\p{Han}A-Za-z0-9]+$},
 * 1–24 chars): Han, ASCII letters and digits only, never whitespace or
 * punctuation. A regression here would let a name through that the hub rejects
 * with {@code invalid-name}, so the boundary cases are asserted directly.
 */
public class EnrollmentNameTest {

    @Test
    public void rejectsNamesWithSpaces() {
        assertFalse(EnrollmentName.isValid("A B"));
        assertFalse(EnrollmentName.isValid("名字 1"));
    }

    @Test
    public void rejectsEmptyName() {
        assertFalse(EnrollmentName.isValid(""));
    }

    @Test
    public void rejectsNamesLongerThanTwentyFourCharacters() {
        // 25 ASCII letters is one past the limit.
        assertFalse(EnrollmentName.isValid("ABCDEFGHIJKLMNOPQRSTUVWXY"));
    }

    @Test
    public void rejectsNamesWithPunctuation() {
        assertFalse(EnrollmentName.isValid("a-b"));
    }

    @Test
    public void acceptsLatinHanAndAlphanumericNames() {
        assertTrue(EnrollmentName.isValid("Alice"));
        assertTrue(EnrollmentName.isValid("小明"));
        assertTrue(EnrollmentName.isValid("A1"));
    }

    @Test
    public void acceptsExactlyTwentyFourCharacters() {
        assertTrue(EnrollmentName.isValid("ABCDEFGHIJKLMNOPQRSTUVWX"));
    }

    @Test
    public void invalidReasonExplainsEachRejection() {
        assertEquals("名稱不可為空", EnrollmentName.invalidReason(""));
        assertEquals("名稱不可為空", EnrollmentName.invalidReason(null));
        assertEquals("名稱不可超過 24 個字",
                EnrollmentName.invalidReason("ABCDEFGHIJKLMNOPQRSTUVWXY"));
        assertEquals("名稱只可使用中文字、英文字母及數字，不可包含空格或標點",
                EnrollmentName.invalidReason("A B"));
        assertNull(EnrollmentName.invalidReason("Alice"));
    }
}
