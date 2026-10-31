package hk.edu.hkmu.speakerdiarazationdemo.ecosys;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Reads the phone hub's hotspot credentials over BLE.
 *
 * <p>The hub advertises an extended, service-filtered peripheral that exposes a
 * single READ characteristic holding UTF-8 JSON:
 * {@code {"ssid":"...","password":"...","port":9000}}. Because the hub's hotspot
 * SSID and passphrase are random per session, this is the only way the glasses
 * can learn them without the user typing them in.
 *
 * <p>{@link #read(Context, long)} is BLOCKING (scan + connect + discover + read)
 * and MUST be called off the main thread. It enforces one overall timeout and
 * always stops the scan and closes the GATT client, so it cannot leak the
 * scanner or a connection. Any failure, timeout, or missing permission yields
 * {@code null}; it never throws to the caller.
 */
public final class BleCredentialClient {

    private static final String TAG = "BleCredentialClient";

    /** The hub's credential service, as advertised in its scan response. */
    public static final UUID SERVICE_UUID =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");

    /** The READ characteristic that holds the JSON payload. */
    public static final UUID CHARACTERISTIC_UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");

    /** Suggested overall budget for scan + connect + read. */
    public static final long DEFAULT_TIMEOUT_MS = 20_000L;

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;

    /** The hotspot credentials handed off to {@link WifiAutoConnector}. */
    public static final class Credentials {
        public final String ssid;
        public final String password;
        public final int port;

        Credentials(String ssid, String password, int port) {
            this.ssid = ssid;
            this.password = password;
            this.port = port;
        }
    }

    private BleCredentialClient() {
    }

    /**
     * Scans for the hub's credential service, connects, reads the credential
     * characteristic, and parses it. Blocking; call off the main thread.
     *
     * @param timeoutMs overall budget for the whole scan/connect/read sequence
     * @return the credentials, or {@code null} on any failure or timeout.
     */
    // The app targets SDK 28, so the legacy BLUETOOTH/BLUETOOTH_ADMIN plus
    // ACCESS_FINE_LOCATION model applies; the whole body is wrapped so a denied or
    // revoked permission becomes null instead of a crash.
    @SuppressLint("MissingPermission")
    public static Credentials read(Context context, long timeoutMs) {
        if (context == null) {
            return null;
        }
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }
        long budgetMs = Math.max(0L, timeoutMs);
        Session session = null;
        try {
            BluetoothManager manager = (BluetoothManager) appContext
                    .getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return null;
            }
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                return null;
            }

            session = new Session(appContext, scanner);
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    // Accept extended advertising so the hub's advertisement is seen.
                    .setLegacy(false)
                    .build();
            scanner.startScan(Collections.singletonList(filter), settings, session.scanCallback);

            return session.await(budgetMs);
        } catch (Exception error) {
            Log.w(TAG, "BLE credential read failed", error);
            return null;
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * Parses the hub's JSON payload without touching Android, so it is unit
     * testable on the JVM. Malformed JSON, missing fields, an empty SSID, or an
     * out-of-range port all yield {@code null}.
     */
    static Credentials parsePayload(String json) {
        if (json == null) {
            return null;
        }
        String trimmed = json.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(trimmed);
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            JsonObject object = root.getAsJsonObject();
            JsonElement ssidElement = object.get("ssid");
            JsonElement passwordElement = object.get("password");
            JsonElement portElement = object.get("port");
            if (!isPrimitive(ssidElement) || !isPrimitive(passwordElement)
                    || !isPrimitive(portElement)) {
                return null;
            }
            String ssid = ssidElement.getAsString();
            String password = passwordElement.getAsString();
            if (ssid == null || ssid.trim().isEmpty()) {
                return null;
            }
            int port;
            try {
                port = portElement.getAsInt();
            } catch (RuntimeException notANumber) {
                return null;
            }
            if (port < MIN_PORT || port > MAX_PORT) {
                return null;
            }
            return new Credentials(ssid, password, port);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static boolean isPrimitive(JsonElement element) {
        return element != null && element.isJsonPrimitive();
    }

    private static String utf8(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    @SuppressLint("MissingPermission")
    private static void closeGatt(BluetoothGatt gatt) {
        if (gatt == null) {
            return;
        }
        try {
            gatt.disconnect();
        } catch (Exception ignored) {
            // Best effort: the link may already be down.
        }
        try {
            gatt.close();
        } catch (Exception ignored) {
            // Best effort: close() is idempotent on the platform.
        }
    }

    /**
     * One scan/connect/read attempt. Owns the latch that unblocks the caller and
     * the single-shot guard that keeps callbacks from completing twice.
     */
    @SuppressLint("MissingPermission")
    private static final class Session {

        private final CountDownLatch done = new CountDownLatch(1);
        private final Context appContext;
        private final BluetoothLeScanner scanner;

        private final Object lock = new Object();
        private volatile boolean closed;
        private BluetoothGatt gatt;

        private boolean completed;
        private Credentials credentials;
        private boolean connectRequested;

        Session(Context appContext, BluetoothLeScanner scanner) {
            this.appContext = appContext;
            this.scanner = scanner;
        }

        final ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result == null ? null : result.getDevice();
                if (device == null) {
                    return;
                }
                synchronized (lock) {
                    if (closed || connectRequested) {
                        return;
                    }
                    connectRequested = true;
                }
                stopScanQuietly();
                try {
                    attach(device.connectGatt(appContext, false, gattCallback));
                } catch (Exception error) {
                    Log.w(TAG, "BLE connect failed", error);
                    complete(null);
                }
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                if (results == null) {
                    return;
                }
                for (ScanResult result : results) {
                    onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.w(TAG, "BLE scan failed: " + errorCode);
                complete(null);
            }
        };

        final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (isClosed()) {
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        if (!gatt.discoverServices()) {
                            complete(null);
                        }
                    } catch (Exception error) {
                        Log.w(TAG, "Service discovery failed", error);
                        complete(null);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    complete(null);
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (isClosed()) {
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    complete(null);
                    return;
                }
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                BluetoothGattCharacteristic characteristic =
                        service == null ? null : service.getCharacteristic(CHARACTERISTIC_UUID);
                if (characteristic == null) {
                    complete(null);
                    return;
                }
                try {
                    if (!gatt.readCharacteristic(characteristic)) {
                        complete(null);
                    }
                } catch (Exception error) {
                    Log.w(TAG, "Characteristic read failed", error);
                    complete(null);
                }
            }

            // API 28 path: the value rides on the characteristic.
            @Override
            public void onCharacteristicRead(BluetoothGatt gatt,
                    BluetoothGattCharacteristic characteristic, int status) {
                completeFromValue(status,
                        characteristic == null ? null : characteristic.getValue());
            }

            // API 33+ path: the value is passed directly.
            @Override
            public void onCharacteristicRead(BluetoothGatt gatt,
                    BluetoothGattCharacteristic characteristic, byte[] value, int status) {
                completeFromValue(status, value);
            }
        };

        private void completeFromValue(int status, byte[] value) {
            if (isClosed()) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                complete(null);
                return;
            }
            complete(parsePayload(utf8(value)));
        }

        private synchronized void complete(Credentials result) {
            if (completed) {
                return;
            }
            completed = true;
            credentials = result;
            done.countDown();
        }

        private boolean isClosed() {
            return closed;
        }

        private synchronized void attach(BluetoothGatt connected) {
            if (connected == null) {
                complete(null);
                return;
            }
            gatt = connected;
            if (closed) {
                closeGatt(connected);
            }
        }

        Credentials await(long timeoutMs) {
            boolean finished;
            try {
                finished = done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (!finished) {
                Log.w(TAG, "BLE credential read timed out after " + timeoutMs + " ms");
                return null;
            }
            return credentials;
        }

        private void stopScanQuietly() {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
                // The scan may already be stopped or the adapter turned off.
            }
        }

        void close() {
            BluetoothGatt openGatt;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                openGatt = gatt;
                gatt = null;
            }
            stopScanQuietly();
            closeGatt(openGatt);
        }
    }
}
