package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Android-side hub address resolution: reads the active network's
 * {@link LinkProperties} and hands the default gateway to {@link GatewayDetector}.
 *
 * <p>It also offers a one-shot TCP reachability probe so the manual-connect screen
 * can show a real connection status instead of only "a gateway exists". Detection
 * never throws: an absent network, link, or gateway yields a not-connected result
 * the caller renders as guidance.
 */
public final class HubConnection {

    /** The hub's default port, shared with the stream client. */
    public static final int DEFAULT_HUB_PORT = EcosysStreamClient.DEFAULT_HUB_PORT;

    /** How long the reachability probe waits before giving up, in milliseconds. */
    public static final int PROBE_TIMEOUT_MS = 800;

    /**
     * The outcome the manual-connect screen renders: whether any network is
     * connected, and the hub host/port when a default gateway was found.
     */
    public static final class Detection {
        private final boolean networkConnected;
        private final String host;
        private final int port;

        Detection(boolean networkConnected, String host, int port) {
            this.networkConnected = networkConnected;
            this.host = host;
            this.port = port;
        }

        /** True when the device has an active network with a usable link. */
        public boolean isNetworkConnected() {
            return networkConnected;
        }

        /** True when a default gateway was resolved and the hub can be dialed. */
        public boolean hasHost() {
            return host != null && !host.isEmpty();
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        /** The dialable {@code host:port}, or an empty string when no host was found. */
        public String endpoint() {
            return GatewayDetector.endpoint(host, port);
        }
    }

    private HubConnection() {
    }

    /**
     * Resolves the hub host from the active network's default gateway. Never
     * throws; the not-connected/no-gateway case is a normal result.
     */
    public static Detection detect(Context context) {
        boolean connected = false;
        String host = null;
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            Network network = connectivityManager.getActiveNetwork();
            LinkProperties properties =
                    network == null ? null : connectivityManager.getLinkProperties(network);
            connected = properties != null;
            host = GatewayDetector.fromLinkProperties(properties);
        }
        return new Detection(connected, host, DEFAULT_HUB_PORT);
    }

    /**
     * One-shot TCP connect probe. Returns true when something accepts the
     * connection before the timeout. Must be called off the main thread.
     */
    public static boolean isReachable(String host, int port, int timeoutMs) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception unreachable) {
            return false;
        }
    }
}
