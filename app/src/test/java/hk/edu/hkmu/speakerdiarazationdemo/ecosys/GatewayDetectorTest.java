package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import hk.edu.hkmu.speakerdiarazationdemo.ecosys.GatewayDetector.Route;

/**
 * Pins the framework-free decision core of {@link GatewayDetector}: the hub host
 * is the connected hotspot's DEFAULT gateway, and a missing default route yields
 * no host (an empty endpoint) rather than a crash.
 */
public class GatewayDetectorTest {

    private static final int PORT = EcosysStreamClient.DEFAULT_HUB_PORT;

    @Test
    public void defaultRouteGatewayBecomesHostPort() {
        List<Route> routes = Arrays.asList(
                new Route(false, "192.168.1.5"),   // on-link, not the gateway
                new Route(true, "192.168.43.1"));  // default route

        assertEquals("192.168.43.1", GatewayDetector.parseRoutes(routes));
        assertEquals("192.168.43.1:50051",
                GatewayDetector.endpoint(GatewayDetector.parseRoutes(routes), PORT));
    }

    @Test
    public void noDefaultRouteYieldsNoHost() {
        List<Route> routes = Collections.singletonList(new Route(false, "192.168.1.5"));

        assertNull(GatewayDetector.parseRoutes(routes));
        assertEquals("", GatewayDetector.endpoint(GatewayDetector.parseRoutes(routes), PORT));
    }

    @Test
    public void emptyAndNullRoutesYieldNoHost() {
        assertNull(GatewayDetector.parseRoutes(Collections.<Route>emptyList()));
        assertNull(GatewayDetector.parseRoutes(null));
        assertEquals("", GatewayDetector.endpoint(null, PORT));
    }

    @Test
    public void defaultRouteWithoutUsableGatewayIsIgnored() {
        List<Route> routes = Arrays.asList(
                new Route(true, null),
                new Route(true, "0.0.0.0"),
                new Route(true, "::"),
                new Route(true, ""));

        assertNull(GatewayDetector.parseRoutes(routes));
    }

    @Test
    public void firstUsableDefaultRouteWins() {
        List<Route> routes = Arrays.asList(
                new Route(true, "0.0.0.0"),
                new Route(true, "10.42.0.1"));

        assertEquals("10.42.0.1", GatewayDetector.parseRoutes(routes));
        assertEquals("10.42.0.1:50051", GatewayDetector.endpoint("10.42.0.1", PORT));
    }
}
