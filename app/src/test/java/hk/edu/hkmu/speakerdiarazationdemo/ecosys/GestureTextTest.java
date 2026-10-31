package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the pure gesture-frame &rarr; display-text mapping. No device,
 * no Android framework.
 */
public class GestureTextTest {

    @Test
    public void meaningTextWinsOverLabel() {
        assertEquals("Yes", GestureText.displayText("Yes", "Thumb_Up"));
    }

    @Test
    public void labelIsTheFallbackWhenMeaningIsBlank() {
        assertEquals("Thumb_Up", GestureText.displayText("", "Thumb_Up"));
        assertEquals("Thumb_Up", GestureText.displayText("   ", "Thumb_Up"));
        assertEquals("Thumb_Up", GestureText.displayText(null, "Thumb_Up"));
    }

    @Test
    public void bothBlankIsNotDisplayableAndNeverCrashes() {
        assertNull(GestureText.displayText(null, null));
        assertNull(GestureText.displayText("", "   "));
        assertFalse(GestureText.isDisplayable(null, null));
        assertFalse(GestureText.isDisplayable(" ", "\t"));
    }

    @Test
    public void returnedTextIsTrimmed() {
        assertEquals("Yes", GestureText.displayText("  Yes  ", "Thumb_Up"));
        assertTrue(GestureText.isDisplayable("Yes", null));
    }
}
