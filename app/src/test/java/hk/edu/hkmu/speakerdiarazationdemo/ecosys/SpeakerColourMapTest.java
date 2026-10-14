package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the pure speaker-label &rarr; colour mapping. No device, no
 * Android framework: {@link SpeakerColourMap} stores plain ARGB ints.
 */
public class SpeakerColourMapTest {

    @Test
    public void emptyLabelUsesTheSharedUnknownColour() {
        assertEquals(SpeakerColourMap.UNKNOWN_SPEAKER_COLOUR, SpeakerColourMap.colourFor(""));
        assertTrue(SpeakerColourMap.isUnknown(""));
    }

    @Test
    public void nullLabelUsesTheSharedUnknownColourAndDoesNotCrash() {
        assertEquals(SpeakerColourMap.UNKNOWN_SPEAKER_COLOUR, SpeakerColourMap.colourFor(null));
        assertTrue(SpeakerColourMap.isUnknown(null));
    }

    @Test
    public void whitespaceAndUnknownSentinelUseTheSharedUnknownColour() {
        assertEquals(SpeakerColourMap.UNKNOWN_SPEAKER_COLOUR, SpeakerColourMap.colourFor("   "));
        assertEquals(SpeakerColourMap.UNKNOWN_SPEAKER_COLOUR, SpeakerColourMap.colourFor("Unknown"));
    }

    @Test
    public void namedLabelUsesItsOwnColour() {
        assertEquals(SpeakerColourMap.NAMED_SPEAKER_COLOUR, SpeakerColourMap.colourFor("Alice"));
        assertEquals(SpeakerColourMap.NAMED_SPEAKER_COLOUR, SpeakerColourMap.colourFor("小明"));
        assertNotEquals(SpeakerColourMap.UNKNOWN_SPEAKER_COLOUR, SpeakerColourMap.colourFor("Alice"));
        assertFalse(SpeakerColourMap.isUnknown("Alice"));
    }

    @Test
    public void genericLabelUsesItsPaletteColour() {
        assertEquals(0xFFFF6347, SpeakerColourMap.colourFor("S1"));
        assertEquals(0xFFFFA500, SpeakerColourMap.colourFor("S2"));
        assertEquals(0xFFFFD700, SpeakerColourMap.colourFor("S3"));
        assertEquals(0xFF32CD32, SpeakerColourMap.colourFor("S4"));
        assertEquals(0xFF1E90FF, SpeakerColourMap.colourFor("S5"));
        // The palette is case-insensitive, matching the old speakerColors map.
        assertEquals(0xFFFF6347, SpeakerColourMap.colourFor("s1"));
    }
}
