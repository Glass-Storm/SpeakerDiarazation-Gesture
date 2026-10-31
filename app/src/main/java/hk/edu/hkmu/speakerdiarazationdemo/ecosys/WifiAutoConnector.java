package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.List;

/**
 * Silently joins the phone's hotspot with credentials baked into the app config.
 *
 * <p>The app targets SDK 28, which is below Android 10's consent enforcement, so
 * the legacy {@link WifiManager} add/enable path works without the user picking
 * the network by hand. The sequence is
 * {@code addNetwork(reuse existing) -> disconnect -> enableNetwork(netId, true)
 * -> reconnect}, followed by a bounded poll for the target SSID and a non-zero IP.
 *
 * <p>{@link #join(Context, String, String, long)} performs blocking Wi-Fi calls and
 * a polling sleep, so it MUST run off the main thread. It never throws: any
 * failure, including a missing permission or a timeout, returns {@code false} so
 * the caller can show manual guidance.
 */
public final class WifiAutoConnector {

    private static final String TAG = "WifiAutoConnector";

    /** How long to wait between connection-state samples. */
    static final long POLL_INTERVAL_MS = 500L;

    /** How long enabling Wi-Fi may take before the join is abandoned. */
    private static final long WIFI_ENABLE_TIMEOUT_MS = 5_000L;

    private WifiAutoConnector() {
    }

    /**
     * Enables Wi-Fi, adds or reuses the target SSID, and waits until the device is
     * associated with it and has an IP address. Blocking; call off the main thread.
     *
     * @return {@code true} only when the target SSID is connected with an IP,
     *         {@code false} on timeout or any error (never throws).
     */
    @SuppressWarnings("deprecation")
    public static boolean join(Context context, String ssid, String password, long timeoutMs) {
        String targetSsid = normalizeSsid(ssid);
        if (targetSsid == null || targetSsid.isEmpty()) {
            return false;
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        try {
            WifiManager wifiManager =
                    (WifiManager) context.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return false;
            }
            if (!ensureWifiEnabled(wifiManager, deadline)) {
                return false;
            }
            int networkId = findConfiguredNetworkId(wifiManager, targetSsid);
            if (networkId < 0) {
                networkId = addNetwork(wifiManager, targetSsid, password);
            }
            if (networkId < 0) {
                return false;
            }
            wifiManager.disconnect();
            wifiManager.enableNetwork(networkId, true);
            wifiManager.reconnect();
            return awaitConnection(wifiManager, targetSsid, deadline);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception error) {
            Log.w(TAG, "Automatic Wi-Fi join failed for SSID " + targetSsid, error);
            return false;
        }
    }

    /** The value in the double quotes a {@link WifiConfiguration} expects. */
    static String quoteWifiValue(String value) {
        String bare = normalizeSsid(value);
        return bare == null ? null : "\"" + bare + "\"";
    }

    /** Strips the surrounding quotes and whitespace from a reported SSID. */
    static String normalizeSsid(String rawSsid) {
        if (rawSsid == null) {
            return null;
        }
        String normalized = rawSsid.trim();
        if (normalized.length() >= 2
                && normalized.startsWith("\"")
                && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    /** True when the connection info reports the target SSID. */
    static boolean ssidMatches(String reportedSsid, String targetSsid) {
        String target = normalizeSsid(targetSsid);
        return target != null && !target.isEmpty() && target.equals(normalizeSsid(reportedSsid));
    }

    /** True when {@link WifiInfo#getIpAddress()} reports an assigned IPv4 address. */
    static boolean hasIpAddress(int ipAddress) {
        return ipAddress != 0;
    }

    private static boolean ensureWifiEnabled(WifiManager wifiManager, long deadline)
            throws InterruptedException {
        if (wifiManager.isWifiEnabled()) {
            return true;
        }
        wifiManager.setWifiEnabled(true);
        long enableDeadline = Math.min(deadline, System.currentTimeMillis() + WIFI_ENABLE_TIMEOUT_MS);
        while (!wifiManager.isWifiEnabled() && System.currentTimeMillis() < enableDeadline) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return wifiManager.isWifiEnabled();
    }

    @SuppressWarnings("deprecation")
    private static int findConfiguredNetworkId(WifiManager wifiManager, String targetSsid) {
        List<WifiConfiguration> configured = wifiManager.getConfiguredNetworks();
        if (configured == null) {
            return -1;
        }
        for (WifiConfiguration configuration : configured) {
            if (configuration != null && ssidMatches(configuration.SSID, targetSsid)) {
                return configuration.networkId;
            }
        }
        return -1;
    }

    @SuppressWarnings("deprecation")
    private static int addNetwork(WifiManager wifiManager, String ssid, String password) {
        int networkId = wifiManager.addNetwork(buildConfiguration(ssid, password, false));
        if (networkId >= 0) {
            return networkId;
        }
        networkId = wifiManager.addNetwork(buildConfiguration(ssid, password, true));
        if (networkId < 0) {
            networkId = findConfiguredNetworkId(wifiManager, ssid);
        }
        return networkId;
    }

    @SuppressWarnings("deprecation")
    private static WifiConfiguration buildConfiguration(
            String ssid, String password, boolean wpaPskOnly) {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = quoteWifiValue(ssid);
        configuration.preSharedKey = quoteWifiValue(password);
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        if (!wpaPskOnly) {
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE);
        }
        configuration.status = WifiConfiguration.Status.ENABLED;
        return configuration;
    }

    @SuppressWarnings("deprecation")
    private static boolean awaitConnection(
            WifiManager wifiManager, String targetSsid, long deadline) throws InterruptedException {
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            if (isConnectedTo(wifiManager, targetSsid)) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return isConnectedTo(wifiManager, targetSsid);
    }

    @SuppressWarnings("deprecation")
    private static boolean isConnectedTo(WifiManager wifiManager, String targetSsid) {
        WifiInfo info = wifiManager.getConnectionInfo();
        return info != null
                && ssidMatches(info.getSSID(), targetSsid)
                && hasIpAddress(info.getIpAddress());
    }
}
