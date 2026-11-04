package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Host precedence for screens that open their own hub stream (enrollment). */
public class HubHostResolverTest {

    private static final String DEFAULT_HOST = "192.168.43.1";

    @Test
    public void storedHostWinsOverIntentExtraAndDefault() {
        assertEquals("10.0.0.7",
                HubHostResolver.resolve("10.0.0.7", "10.0.0.9", DEFAULT_HOST));
    }

    @Test
    public void intentExtraIsUsedWhenNothingIsStored() {
        assertEquals("10.0.0.9",
                HubHostResolver.resolve(null, "10.0.0.9", DEFAULT_HOST));
        assertEquals("10.0.0.9",
                HubHostResolver.resolve("   ", "10.0.0.9", DEFAULT_HOST));
    }

    @Test
    public void defaultGatewayIsTheLastResort() {
        assertEquals(DEFAULT_HOST,
                HubHostResolver.resolve(null, null, DEFAULT_HOST));
        assertEquals(DEFAULT_HOST,
                HubHostResolver.resolve("", "  ", DEFAULT_HOST));
    }

    @Test
    public void hostsAreTrimmed() {
        assertEquals("10.0.0.7",
                HubHostResolver.resolve(" 10.0.0.7 ", " 10.0.0.9 ", DEFAULT_HOST));
    }
}
