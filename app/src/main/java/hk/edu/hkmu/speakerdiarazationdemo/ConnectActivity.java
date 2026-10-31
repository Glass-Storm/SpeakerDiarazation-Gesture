package hk.edu.hkmu.speakerdiarazationdemo;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hk.edu.hkmu.speakerdiarazationdemo.ecosys.BleCredentialClient;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.HubConnection;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.HubHostStore;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.WifiAutoConnector;
import hk.edu.hkmu.speakerdiarazationdemo.models.AppConfig;

/**
 * The connect step. The glasses cannot start the phone's hotspot, so on resume
 * this screen learns the hub's random hotspot SSID and passphrase over BLE,
 * silently joins it, then re-detects the default gateway (the phone, which is
 * the access point) and uses it as the hub host. If the BLE read fails it falls
 * back to the credentials baked into {@code config_default.json}. It shows the
 * detected {@code host:port} plus a live connection status, and "繼續" carries
 * the host into {@link MainActivity} through
 * {@link MainActivity#EXTRA_HUB_HOST}.
 *
 * <p>When the automatic join or the gateway detection fails the screen falls back
 * to clear manual "connect to the hotspot" guidance and keeps "繼續" disabled — it
 * never crashes and never blocks the main thread.
 */
public class ConnectActivity extends AppCompatActivity {

    /** Request code for the legacy location permission BLE scanning needs. */
    private static final int REQUEST_BLE_SCAN_PERMISSION = 41;

    /** Upper bound on the silent hotspot join before the manual fallback appears. */
    private static final long AUTO_JOIN_TIMEOUT_MS = 15_000L;

    /** Upper bound on the BLE credential read before falling back to app config. */
    private static final long BLE_CREDENTIAL_TIMEOUT_MS = BleCredentialClient.DEFAULT_TIMEOUT_MS;

    private static final String STATUS_RECEIVING_CREDENTIALS = "正在透過藍牙接收熱點資訊…";

    private static final String STATUS_NEED_LOCATION_PERMISSION =
            "需要位置權限，才能透過藍牙接收熱點資訊。";

    private TextView tvConnectStatus;
    private TextView tvConnectHost;
    private Button btnConnectContinue;
    private Button btnConnectRetry;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ExecutorService probeExecutor;
    private String detectedHost;
    private boolean autoJoinInFlight;
    private boolean scanPermissionRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connect);

        tvConnectStatus = findViewById(R.id.tvConnectStatus);
        tvConnectHost = findViewById(R.id.tvConnectHost);
        btnConnectContinue = findViewById(R.id.btnConnectContinue);
        btnConnectRetry = findViewById(R.id.btnConnectRetry);

        probeExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hub-reachability-probe");
            thread.setDaemon(true);
            return thread;
        });

        btnConnectRetry.setOnClickListener(v -> maybeAutoJoin());
        btnConnectContinue.setOnClickListener(v -> continueToSession());
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeAutoJoin();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (probeExecutor != null) {
            probeExecutor.shutdownNow();
        }
    }

    /**
     * Joins the configured hotspot when no gateway is visible, then refreshes the
     * screen. A join in flight suppresses further attempts so the screen cannot
     * start a tight retry loop.
     *
     * <p>Credentials come from the phone over BLE first (the hub's hotspot SSID
     * and passphrase are random per session); when the BLE read fails the app
     * falls back to the credentials baked into {@code config_default.json}.
     */
    private void maybeAutoJoin() {
        if (autoJoinInFlight) {
            return;
        }
        HubConnection.Detection detection = HubConnection.detect(this);
        if (detection.hasHost()) {
            detectAndShow();
            return;
        }
        if (!bleScanPermissionReady()) {
            tvConnectHost.setText(detection.isNetworkConnected()
                    ? "Hub 位址：尚未找到閘道"
                    : "Hub 位址：尚未連線任何 Wi-Fi");
            tvConnectStatus.setText(STATUS_NEED_LOCATION_PERMISSION);
            return;
        }

        autoJoinInFlight = true;
        detectedHost = null;
        btnConnectContinue.setEnabled(false);
        btnConnectRetry.setEnabled(false);
        tvConnectHost.setText(detection.isNetworkConnected()
                ? "Hub 位址：尚未找到閘道"
                : "Hub 位址：尚未連線任何 Wi-Fi");
        tvConnectStatus.setText(STATUS_RECEIVING_CREDENTIALS);

        probeExecutor.execute(this::autoJoinInBackground);
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
            return true;
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
            maybeAutoJoin();
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
        uiHandler.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            autoJoinInFlight = false;
            btnConnectRetry.setEnabled(true);
            detectAndShow();
            if (!joinedFinal && detectedHost == null) {
                tvConnectStatus.setText("自動連線熱點失敗。請在手機開啟個人熱點，"
                        + "並在眼鏡的 Wi-Fi 設定手動連線該熱點，然後返回本頁重新偵測。");
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
        uiHandler.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            tvConnectStatus.setText(text);
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

    /** Refreshes detection, updates the host/status text, and probes reachability. */
    private void detectAndShow() {
        HubConnection.Detection detection = HubConnection.detect(this);
        detectedHost = detection.hasHost() ? detection.host() : null;

        if (!detection.hasHost()) {
            tvConnectHost.setText(detection.isNetworkConnected()
                    ? "Hub 位址：尚未找到閘道"
                    : "Hub 位址：尚未連線任何 Wi-Fi");
            tvConnectStatus.setText("請在手機開啟個人熱點，並在眼鏡的 Wi-Fi 設定手動連線該熱點，"
                    + "然後返回本頁重新偵測。");
            btnConnectContinue.setEnabled(false);
            return;
        }

        tvConnectHost.setText("Hub 位址：" + detection.endpoint());
        btnConnectContinue.setEnabled(true);
        tvConnectStatus.setText("已偵測到閘道，正在檢查 Hub 連線…");
        probe(detection.host(), detection.port());
    }

    /** Runs the TCP probe off the main thread and posts the result back. */
    private void probe(String host, int port) {
        probeExecutor.execute(() -> {
            boolean reachable = HubConnection.isReachable(host, port, HubConnection.PROBE_TIMEOUT_MS);
            uiHandler.post(() -> {
                // Drop a stale result if a newer detection replaced the host.
                if (!host.equals(detectedHost)) {
                    return;
                }
                tvConnectStatus.setText(reachable
                        ? "已連線到 Hub ✓"
                        : "已找到閘道，但無法連線 Hub（請確認手機上的 Hub 已啟動）");
            });
        });
    }

    private void continueToSession() {
        if (detectedHost == null) {
            tvConnectStatus.setText("請先連線到手機熱點，再繼續。");
            return;
        }
        HubHostStore.save(this, detectedHost);
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_HUB_HOST, detectedHost);
        startActivity(intent);
        finish();
    }
}
