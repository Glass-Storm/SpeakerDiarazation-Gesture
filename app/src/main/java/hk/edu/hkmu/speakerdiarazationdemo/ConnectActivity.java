package hk.edu.hkmu.speakerdiarazationdemo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hk.edu.hkmu.speakerdiarazationdemo.ecosys.HubConnection;

/**
 * The manual-connect step. The glasses cannot start the phone's hotspot and uses
 * no BLE/mDNS, so the user enables the hotspot and joins it by hand. On every
 * resume this screen re-detects the default gateway (the phone, which is the
 * access point) and uses it as the hub host, then shows the detected
 * {@code host:port} plus a live connection status. "繼續" carries the detected
 * host into {@link MainActivity} through {@link MainActivity#EXTRA_HUB_HOST}.
 *
 * <p>When no gateway is found the screen shows clear "connect to the hotspot"
 * guidance and keeps "繼續" disabled — it never crashes.
 */
public class ConnectActivity extends AppCompatActivity {

    private TextView tvConnectStatus;
    private TextView tvConnectHost;
    private Button btnConnectContinue;
    private Button btnConnectRetry;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ExecutorService probeExecutor;
    private String detectedHost;

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

        btnConnectRetry.setOnClickListener(v -> detectAndShow());
        btnConnectContinue.setOnClickListener(v -> continueToSession());
    }

    @Override
    protected void onResume() {
        super.onResume();
        detectAndShow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (probeExecutor != null) {
            probeExecutor.shutdownNow();
        }
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
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_HUB_HOST, detectedHost);
        startActivity(intent);
        finish();
    }
}
