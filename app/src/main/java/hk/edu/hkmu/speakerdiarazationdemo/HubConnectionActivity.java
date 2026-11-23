package hk.edu.hkmu.speakerdiarazationdemo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import hk.edu.hkmu.speakerdiarazationdemo.ecosys.BleCredentialClient;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.HubConnection;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.WifiAutoConnector;
import hk.edu.hkmu.speakerdiarazationdemo.models.AppConfig;

/** Manages hotspot discovery and Hub reachability for the session screen. */
abstract class HubConnectionActivity extends AppCompatActivity {

    private static final long PROBE_INTERVAL_MS = 3_000L;

    /** Request code for the legacy location permission BLE scanning needs. */
    private static final int REQUEST_BLE_SCAN_PERMISSION = 41;

    /** Upper bound on the silent hotspot join before the manual fallback appears. */
    private static final long AUTO_JOIN_TIMEOUT_MS = 15_000L;

    /** Upper bound on the BLE credential read before falling back to app config. */
    private static final long BLE_CREDENTIAL_TIMEOUT_MS = BleCredentialClient.DEFAULT_TIMEOUT_MS;

    private static final String STATUS_RECEIVING_CREDENTIALS = "正在透過藍牙接收熱點資訊…";

    private static final String STATUS_NEED_LOCATION_PERMISSION =
            "需要位置權限，才能透過藍牙接收熱點資訊。";

    private final Handler connectionHandler = new Handler(Looper.getMainLooper());
    private final Runnable nextProbe = () -> probe(false);
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hub-reachability-probe");
        thread.setDaemon(true);
        return thread;
    });
    private boolean monitoring;
    private boolean autoJoinInFlight;
    private boolean scanPermissionRequested;
    private int probeGeneration;
    private Future<?> probeJob;

    protected abstract String hubHostOverride();

    protected abstract void showHubConnection(String reachableHost, String status);

    protected final void startHubMonitoring() {
        monitoring = true;
        retryHubConnection();
    }

    protected final void stopHubMonitoring() {
        monitoring = false;
        cancelProbe();
        connectionHandler.removeCallbacks(nextProbe);
    }

    protected final void retryHubConnection() {
        connectionHandler.removeCallbacks(nextProbe);
        probe(true);
    }

    @Override
    protected void onDestroy() {
        stopHubMonitoring();
        probeExecutor.shutdownNow();
        super.onDestroy();
    }

    /**
     * Checks the gateway. An explicit retry or entrance to the screen can also
     * rejoin the phone hotspot when the gateway is absent or unreachable.
     *
     * <p>Credentials come from the phone over BLE first (the hub's hotspot SSID
     * and passphrase are random per session); when the BLE read fails the app
     * falls back to the credentials baked into {@code config_default.json}.
     */
    private void probe(boolean allowAutoJoin) {
        if (!monitoring || autoJoinInFlight) {
            return;
        }

        cancelProbe();
        int generation = probeGeneration;
        String host = hubHostOverride();
        if (host == null) {
            host = HubConnection.detect(this).host();
        }
        if (host == null) {
            if (allowAutoJoin) {
                autoJoin();
            } else {
                showHubConnection(null, "請連線到手機熱點，再重新偵測");
                scheduleProbe();
            }
            return;
        }

        String target = host;
        boolean tryHotspotOnFailure = allowAutoJoin && hubHostOverride() == null;
        probeJob = probeExecutor.submit(() -> {
            boolean reachable = HubConnection.isReachable(
                    target, HubConnection.DEFAULT_HUB_PORT, HubConnection.PROBE_TIMEOUT_MS);
            connectionHandler.post(() -> {
                if (!monitoring || generation != probeGeneration) {
                    return;
                }
                probeJob = null;
                if (!reachable && tryHotspotOnFailure) {
                    autoJoin();
                    return;
                }
                showHubConnection(reachable ? target : null, reachable
                        ? "Hub 可連線"
                        : "Hub 未連線 · 請確認手機已啟動 Hub");
                scheduleProbe();
            });
        });
    }

    private void autoJoin() {
        if (!bleScanPermissionReady()) {
            showHubConnection(null, STATUS_NEED_LOCATION_PERMISSION);
            scheduleProbe();
            return;
        }
        autoJoinInFlight = true;
        showHubConnection(null, STATUS_RECEIVING_CREDENTIALS);
        probeExecutor.execute(this::autoJoinInBackground);
    }

    private void cancelProbe() {
        probeGeneration++;
        if (probeJob != null) {
            probeJob.cancel(false);
            probeJob = null;
        }
    }

    private void scheduleProbe() {
        connectionHandler.removeCallbacks(nextProbe);
        connectionHandler.postDelayed(nextProbe, PROBE_INTERVAL_MS);
    }

    /**
     * True when the BLE scan may proceed. A missing legacy location permission
     * opens the system dialog once and returns false; the result callback resumes
     * the flow.
     */
    private boolean bleScanPermissionReady() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        if (scanPermissionRequested) {
            return false;
        }
        scanPermissionRequested = true;
        ActivityCompat.requestPermissions(this,
                new String[] {Manifest.permission.ACCESS_FINE_LOCATION},
                REQUEST_BLE_SCAN_PERMISSION);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLE_SCAN_PERMISSION) {
            retryHubConnection();
        }
    }

    /** BLE read plus the join attempts; never touches the UI directly. */
    private void autoJoinInBackground() {
        Context appContext = getApplicationContext();

        BleCredentialClient.Credentials bleCredentials =
                BleCredentialClient.read(appContext, BLE_CREDENTIAL_TIMEOUT_MS);
        boolean joined = attemptJoin(bleCredentials);
        if (!joined) {
            if (bleCredentials != null) {
                String fallbackSsid = configuredSsid();
                if (fallbackSsid != null) {
                    postStatus("藍牙熱點連線失敗，改試預設熱點「" + fallbackSsid + "」…");
                }
            }
            joined = attemptConfiguredJoin();
        }

        boolean joinedFinal = joined;
        connectionHandler.post(() -> {
            autoJoinInFlight = false;
            if (!monitoring) {
                return;
            }
            if (!joinedFinal && !HubConnection.detect(this).hasHost()) {
                showHubConnection(null, "請在眼鏡的 Wi-Fi 設定連線到手機熱點，再重新偵測");
                scheduleProbe();
            } else {
                probe(false);
            }
        });
    }

    /** Shows the join status and runs one bounded join with the BLE credentials. */
    private boolean attemptJoin(BleCredentialClient.Credentials credentials) {
        if (credentials == null) {
            return false;
        }
        postStatus("正在自動連線熱點「" + credentials.ssid + "」…");
        return WifiAutoConnector.join(
                getApplicationContext(), credentials.ssid, credentials.password, AUTO_JOIN_TIMEOUT_MS);
    }

    /** Runs one bounded join with the config-baked credentials, if any. */
    private boolean attemptConfiguredJoin() {
        AppConfig config = loadConfig();
        String ssid = config == null ? null : config.getWifiSsid();
        if (ssid == null || ssid.trim().isEmpty()) {
            return false;
        }
        String password = config.getWifiPassword();
        postStatus("正在自動連線熱點「" + ssid + "」…");
        return WifiAutoConnector.join(
                getApplicationContext(), ssid, password, AUTO_JOIN_TIMEOUT_MS);
    }

    /** The SSID baked into app config, or null when there is none. */
    private String configuredSsid() {
        AppConfig config = loadConfig();
        String ssid = config == null ? null : config.getWifiSsid();
        return ssid == null || ssid.trim().isEmpty() ? null : ssid;
    }

    /** Posts a status line unless the screen is already gone. */
    private void postStatus(String text) {
        connectionHandler.post(() -> {
            if (monitoring) {
                showHubConnection(null, text);
            }
        });
    }

    private AppConfig loadConfig() {
        ConfigManager manager = ConfigManager.getInstance();
        if (manager.getConfig() == null) {
            try {
                manager.loadConfig(this);
            } catch (Exception unreadable) {
                return null;
            }
        }
        return manager.getConfig();
    }
}
