package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the framework-free helpers behind {@link WifiAutoConnector}: the WPA
 * quoting, the quote-stripping SSID comparison, and the "has an IP" test. The
 * real {@code WifiManager} calls are deliberately not exercised here.
 */
public class WifiAutoConnectorTest {

    @Test
    public void quoteWifiValueWrapsTheBareValue() {
        assertEquals("\"EcoSys-Hub\"", WifiAutoConnector.quoteWifiValue("EcoSys-Hub"));
        assertEquals("\"ecosys-demo-2026\"", WifiAutoConnector.quoteWifiValue("ecosys-demo-2026"));
    }

    @Test
    public void quoteWifiValueIsIdempotent() {
        assertEquals("\"EcoSys-Hub\"", WifiAutoConnector.quoteWifiValue("\"EcoSys-Hub\""));
    }

    @Test
    public void quoteWifiValueRejectsNull() {
        assertNull(WifiAutoConnector.quoteWifiValue(null));
    }

    @Test
    public void normalizeSsidStripsSurroundingQuotes() {
        assertEquals("EcoSys-Hub", WifiAutoConnector.normalizeSsid("\"EcoSys-Hub\""));
        assertEquals("EcoSys-Hub", WifiAutoConnector.normalizeSsid(" EcoSys-Hub "));
        assertEquals("EcoSys-Hub", WifiAutoConnector.normalizeSsid("EcoSys-Hub"));
        assertNull(WifiAutoConnector.normalizeSsid(null));
    }

    @Test
    public void ssidMatchesIgnoresOnlyTheReportingQuotes() {
        assertTrue(WifiAutoConnector.ssidMatches("\"EcoSys-Hub\"", "EcoSys-Hub"));
        assertTrue(WifiAutoConnector.ssidMatches("EcoSys-Hub", "\"EcoSys-Hub\""));
        assertFalse(WifiAutoConnector.ssidMatches("\"EcoSys-Other\"", "EcoSys-Hub"));
        assertFalse(WifiAutoConnector.ssidMatches("<unknown ssid>", "EcoSys-Hub"));
    }

    @Test
    public void emptyTargetSsidNeverMatches() {
        assertFalse(WifiAutoConnector.ssidMatches("\"\"", ""));
        assertFalse(WifiAutoConnector.ssidMatches(null, null));
    }

    @Test
    public void connectionNeedsANonZeroIpAddress() {
        assertFalse(WifiAutoConnector.hasIpAddress(0));
        assertTrue(WifiAutoConnector.hasIpAddress(0xC0A82B01));
    }
}
