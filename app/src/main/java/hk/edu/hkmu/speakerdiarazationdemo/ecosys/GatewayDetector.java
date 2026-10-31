package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import android.net.LinkProperties;
import android.net.RouteInfo;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the hub host from the phone hotspot the user joined by hand.
 *
 * <p>The glasses never toggles the hotspot and never uses BLE or mDNS. Instead the
 * user enables the phone's hotspot and joins it MANUALLY, and because the phone is
 * the access point the hub listens at that network's <b>default gateway</b>. This
 * class turns the live {@link LinkProperties} into a dialable host.
 *
 * <p>The decision core, {@link #parseRoutes(List)}, is deliberately framework-free
 * so a plain JVM unit test can pin it. {@link #fromLinkProperties(LinkProperties)}
 * is the thin Android projection, and {@link #endpoint(String, int)} formats the
 * {@code host:port} the client dials. When there is no usable default route both
 * return an empty/absent result and the caller shows the "connect to the hotspot"
 * guidance instead of dialing — never a crash.
 */
public final class GatewayDetector {

    /** Addresses that parse but are never a usable gateway. */
    private static final String IPV4_UNSPECIFIED = "0.0.0.0";
    private static final String IPV6_UNSPECIFIED = "::";

    /**
     * One route projected onto the two fields gateway resolution needs. Pure and
     * constructible from a test, with no Android dependency.
     */
    public static final class Route {
        private final boolean defaultRoute;
        private final String gateway;

        public Route(boolean defaultRoute, String gateway) {
            this.defaultRoute = defaultRoute;
            this.gateway = gateway;
        }

        public boolean isDefaultRoute() {
            return defaultRoute;
        }

        /** The gateway's canonical address, or {@code null} when the route has none. */
        public String gateway() {
            return gateway;
        }
    }

    private GatewayDetector() {
    }

    /**
     * The default route's gateway address, or {@code null} when the connected
     * network exposes no usable default route (for example, the phone hotspot has
     * not been joined yet).
     */
    public static String parseRoutes(List<Route> routes) {
        if (routes == null) {
            return null;
        }
        for (Route route : routes) {
            if (route == null || !route.isDefaultRoute()) {
                continue;
            }
            String gateway = route.gateway();
            if (isUsableGateway(gateway)) {
                return gateway;
            }
        }
        return null;
    }

    /**
     * Android projection: walk {@link LinkProperties#getRoutes()} and delegate the
     * decision to {@link #parseRoutes(List)}. Returns {@code null} when there is no
     * link (or no default route).
     */
    public static String fromLinkProperties(LinkProperties linkProperties) {
        if (linkProperties == null) {
            return null;
        }
        List<Route> routes = new ArrayList<>();
        for (RouteInfo info : linkProperties.getRoutes()) {
            if (info == null) {
                continue;
            }
            InetAddress gateway = info.getGateway();
            String host = gateway == null ? null : gateway.getHostAddress();
            routes.add(new Route(info.isDefaultRoute(), host));
        }
        return parseRoutes(routes);
    }

    /**
     * The {@code host:port} the client dials, or an empty string when no host was
     * detected. The empty string is the caller's signal to show the "connect to the
     * hotspot" guidance instead of dialing.
     */
    public static String endpoint(String host, int port) {
        if (!isUsableGateway(host)) {
            return "";
        }
        return host + ":" + port;
    }

    private static boolean isUsableGateway(String host) {
        return host != null
                && !host.isEmpty()
                && !IPV4_UNSPECIFIED.equals(host)
                && !IPV6_UNSPECIFIED.equals(host);
    }
}
