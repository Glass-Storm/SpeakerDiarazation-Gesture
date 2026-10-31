package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Pins the framework-free half of {@link BleCredentialClient}: the JSON payload
 * the hub writes into its READ characteristic. The scan/connect/read path needs
 * a real Bluetooth stack and is deliberately not exercised here.
 */
public class BleCredentialClientTest {

    @Test
    public void parsesSsidPasswordAndPort() {
        BleCredentialClient.Credentials credentials = BleCredentialClient.parsePayload(
                "{\"ssid\":\"EcoSys-4f2a\",\"password\":\"s3cret-pass\",\"port\":9000}");

        assertNotNull(credentials);
        assertEquals("EcoSys-4f2a", credentials.ssid);
        assertEquals("s3cret-pass", credentials.password);
        assertEquals(9000, credentials.port);
    }

    @Test
    public void ignoresUnknownFieldsAndSurroundingWhitespace() {
        BleCredentialClient.Credentials credentials = BleCredentialClient.parsePayload(
                "  {\"extra\":\"ignored\",\"ssid\":\"EcoSys-4f2a\","
                        + "\"password\":\"s3cret-pass\",\"port\":9001}  ");

        assertNotNull(credentials);
        assertEquals("EcoSys-4f2a", credentials.ssid);
        assertEquals("s3cret-pass", credentials.password);
        assertEquals(9001, credentials.port);
    }

    @Test
    public void rejectsMalformedJson() {
        assertNull(BleCredentialClient.parsePayload(null));
        assertNull(BleCredentialClient.parsePayload(""));
        assertNull(BleCredentialClient.parsePayload("not json"));
        assertNull(BleCredentialClient.parsePayload("{"));
        assertNull(BleCredentialClient.parsePayload("{\"ssid\":}"));
        assertNull(BleCredentialClient.parsePayload("\"just a string\""));
        assertNull(BleCredentialClient.parsePayload("[]"));
    }

    @Test
    public void rejectsMissingFields() {
        assertNull(BleCredentialClient.parsePayload(
                "{\"password\":\"s3cret-pass\",\"port\":9000}"));
        assertNull(BleCredentialClient.parsePayload(
                "{\"ssid\":\"EcoSys-4f2a\",\"port\":9000}"));
        assertNull(BleCredentialClient.parsePayload(
                "{\"ssid\":\"EcoSys-4f2a\",\"password\":\"s3cret-pass\"}"));
    }

    @Test
    public void rejectsEmptyOrBlankSsid() {
        assertNull(BleCredentialClient.parsePayload(
                "{\"ssid\":\"\",\"password\":\"s3cret-pass\",\"port\":9000}"));
        assertNull(BleCredentialClient.parsePayload(
                "{\"ssid\":\"   \",\"password\":\"s3cret-pass\",\"port\":9000}"));
    }

    @Test
    public void rejectsInvalidPort() {
        assertNull(BleCredentialClient.parsePayload(
                "{\"ssid\":\"EcoSys-4f2a\",\"password\":\"s3cret-pass\",\"port\":0}"));
        assertNull(BleCredentialClient.parsePayload(
                "{\"ssid\":\"EcoSys-4f2a\",\"password\":\"s3cret-pass\",\"port\":70000}"));
        assertNull(BleCredentialClient.parsePayload(
                "{\"ssid\":\"EcoSys-4f2a\",\"password\":\"s3cret-pass\",\"port\":\"nine\"}"));
    }
}
