package ru.chepil.hytalkptt;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.ParcelUuid;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * BLE PTT button PTT-Z01 (YPC21): scan by name, GATT connect, notify on FFE1 (0x01 pressed / 0x00 released).
 * Host: {@link PTTAccessibilityService} or {@link BluetoothPttService}.
 */
@SuppressLint("MissingPermission")
final class BlePttZ01Controller {

    private static final String TAG = "BlePttZ01";

    /** Advertising / GAP device name. */
    private static final String TARGET_DEVICE_NAME = "PTT-Z01";

    private static final UUID UUID_SVC_GAP = UUID.fromString("00001800-0000-1000-8000-00805F9B34FB");
    private static final UUID UUID_CHR_DEVICE_NAME = UUID.fromString("00002A00-0000-1000-8000-00805F9B34FB");
    private static final UUID UUID_SVC_FFE0 = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID UUID_CHR_FFE1 = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    private static final UUID UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private static final long SCAN_TIMEOUT_MS = 30000L;
    private static final long RECONNECT_DELAY_MS = 2500L;

    private static final String PERM_BT_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    private static final String PERM_BT_SCAN = "android.permission.BLUETOOTH_SCAN";
    private static final int ANDROID_12_API = 31;
    private static final int ANDROID_6_API = 23;

    private static BlePttZ01Controller sInstance;

    private final Context mApp;
    private Context mHost;

    private final Handler mMain = new Handler(Looper.getMainLooper());

    private BluetoothAdapter mAdapter;
    private BluetoothLeScanner mScanner;
    private boolean mScanning;
    private final Runnable mScanTimeout = new Runnable() {
        @Override
        public void run() {
            stopScanInternal();
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    PttKeySetupActivity.notifyBleScanFinished(mApp, false);
                }
            });
        }
    };

    private BluetoothGatt mGatt;
    private boolean mConnectingFromScan;
    /** Address of device we subscribed to this session (pending until Save writes prefs). */
    private volatile String mSessionReadyAddress;
    private boolean mReconnectScheduled;
    private final Runnable mReconnectRunnable = new Runnable() {
        @Override
        public void run() {
            mReconnectScheduled = false;
            if (!PttPreferences.isPttBleButtonEnabled(mApp)) {
                return;
            }
            String addr = PttPreferences.getBlePttDeviceAddress(mApp);
            if (addr == null || mHost == null) {
                return;
            }
            tryConnectByAddress(addr);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                    if (PttPreferences.PREF_KEY_PTT_BLE_BUTTON.equals(key)
                            || PttPreferences.PREF_KEY_BLE_PTT_DEVICE_ADDRESS.equals(key)) {
                        applyPrefs();
                    }
                }
            };

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT connected status=" + status);
                mConnectingFromScan = false;
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT disconnected status=" + status);
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    notifyConnectFailureFromSetupUi(status);
                }
                mConnectingFromScan = false;
                mSessionReadyAddress = null;
                clearGattReference(gatt);
                scheduleReconnectIfNeeded();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onServicesDiscovered failed status=" + status);
                gatt.disconnect();
                return;
            }
            BluetoothGattService gap = gatt.getService(UUID_SVC_GAP);
            BluetoothGattCharacteristic deviceNameChr =
                    gap != null ? gap.getCharacteristic(UUID_CHR_DEVICE_NAME) : null;
            if (deviceNameChr != null) {
                if (!gatt.readCharacteristic(deviceNameChr)) {
                    Log.w(TAG, "readCharacteristic(Device Name) failed");
                    gatt.disconnect();
                }
                return;
            }
            BlePttZ01Controller.this.subscribeFfe1Notify(gatt);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (UUID_CHR_DEVICE_NAME.equals(characteristic.getUuid())) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Device Name read failed status=" + status);
                    gatt.disconnect();
                    return;
                }
                String name = characteristicValueAsUtf8Trimmed(characteristic.getValue());
                if (name == null || !TARGET_DEVICE_NAME.equals(name)) {
                    Log.w(TAG, "GAP Device Name mismatch: \"" + name + "\"");
                    gatt.disconnect();
                    PttKeySetupActivity.notifyBleGapMismatch(mApp);
                    return;
                }
                BlePttZ01Controller.this.subscribeFfe1Notify(gatt);
                return;
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (!UUID_CCCD.equals(descriptor.getUuid())) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onDescriptorWrite failed status=" + status);
                gatt.disconnect();
                return;
            }
            BluetoothGattCharacteristic chr = descriptor.getCharacteristic();
            if (chr != null && UUID_CHR_FFE1.equals(chr.getUuid())) {
                BluetoothDevice dev = gatt.getDevice();
                if (dev != null) {
                    mSessionReadyAddress = dev.getAddress();
                    Log.i(TAG, "FFE1 notifications enabled, addr=" + mSessionReadyAddress);
                    final String addrCopy = mSessionReadyAddress;
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            PttKeySetupActivity.notifyBleGattReady(mApp, addrCopy);
                        }
                    });
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (!UUID_CHR_FFE1.equals(characteristic.getUuid())) {
                return;
            }
            byte[] v = characteristic.getValue();
            if (v == null || v.length < 1) {
                return;
            }
            boolean pressed = v[0] != 0;
            BluetoothPttRoutingManager.dispatchBlePttButton(mApp, pressed);
        }
    };

    @SuppressLint("MissingPermission")
    private void subscribeFfe1Notify(BluetoothGatt gatt) {
        BluetoothGattService ffe0 = gatt.getService(UUID_SVC_FFE0);
        if (ffe0 == null) {
            Log.w(TAG, "FFE0 service missing");
            gatt.disconnect();
            return;
        }
        BluetoothGattCharacteristic ffe1 = ffe0.getCharacteristic(UUID_CHR_FFE1);
        if (ffe1 == null) {
            Log.w(TAG, "FFE1 characteristic missing");
            gatt.disconnect();
            return;
        }
        int props = ffe1.getProperties();
        if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
            Log.w(TAG, "FFE1 has no NOTIFY");
            gatt.disconnect();
            return;
        }
        if (!gatt.setCharacteristicNotification(ffe1, true)) {
            Log.w(TAG, "setCharacteristicNotification failed");
            gatt.disconnect();
            return;
        }
        BluetoothGattDescriptor cccd = ffe1.getDescriptor(UUID_CCCD);
        if (cccd == null) {
            Log.w(TAG, "CCCD missing");
            gatt.disconnect();
            return;
        }
        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!gatt.writeDescriptor(cccd)) {
            Log.w(TAG, "writeDescriptor failed");
            gatt.disconnect();
        }
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) {
                return;
            }
            if (!isLikelyTargetFromScan(result)) {
                return;
            }
            if (mConnectingFromScan || mGatt != null) {
                return;
            }
            stopScanInternal();
            BluetoothDevice dev = result.getDevice();
            String name = deviceNameFromScan(result);
            Log.i(TAG, "Scan candidate addr=" + dev.getAddress() + " name=" + name);
            mConnectingFromScan = true;
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    PttKeySetupActivity.notifyBleScanFinished(mApp, true);
                }
            });
            connectGatt(dev, false);
        }
    };

    private BlePttZ01Controller(Context app) {
        mApp = app.getApplicationContext();
    }

    static synchronized void attachHost(Context host) {
        Context app = host.getApplicationContext();
        if (sInstance == null) {
            sInstance = new BlePttZ01Controller(app);
            PttPreferences.prefs(app).registerOnSharedPreferenceChangeListener(sInstance.mPrefsListener);
        } else if (sInstance.mHost != null && sInstance.mHost != host) {
            sInstance.mMain.removeCallbacks(sInstance.mReconnectRunnable);
            sInstance.mReconnectScheduled = false;
            sInstance.stopScanInternal();
            sInstance.disconnectGatt();
            sInstance.mHost = null;
        }
        sInstance.mHost = host;
        sInstance.ensureAdapter();
        sInstance.applyPrefs();
    }

    /** True when there is no active host and setup UI should attach one temporarily. */
    static synchronized boolean needsHostForSetupUi() {
        return sInstance == null || sInstance.mHost == null;
    }

    static synchronized void detachHost(Context host) {
        if (sInstance == null || sInstance.mHost != host) {
            return;
        }
        Context app = sInstance.mApp;
        sInstance.mMain.removeCallbacks(sInstance.mScanTimeout);
        sInstance.mMain.removeCallbacks(sInstance.mReconnectRunnable);
        sInstance.stopScanInternal();
        sInstance.disconnectGatt();
        sInstance.mReconnectScheduled = false;
        sInstance.mHost = null;
        PttPreferences.prefs(app).unregisterOnSharedPreferenceChangeListener(sInstance.mPrefsListener);
        sInstance = null;
    }

    /** Stop scan / GATT while on Configure PTT (checkbox off or leaving). */
    static void disconnectSessionFromSetupUi() {
        BlePttZ01Controller c = sInstance;
        if (c == null) {
            return;
        }
        c.stopScanInternal();
        c.disconnectGatt();
    }

    /** Pending MAC after successful subscribe (until user taps Save). */
    static String getSessionReadyAddress(Context context) {
        BlePttZ01Controller c = sInstance;
        if (c == null) {
            return null;
        }
        return c.mSessionReadyAddress;
    }

    /**
     * Starts BLE scan from UI (Configure PTT). Caller should request runtime permissions first.
     */
    static void startScanFromSetupActivity(Context activityContext) {
        if (needsHostForSetupUi()) {
            attachHost(activityContext);
        }
        BlePttZ01Controller c = sInstance;
        if (c == null || c.mHost == null) {
            Log.w(TAG, "startScan: no host");
            PttKeySetupActivity.notifyBleScanError(activityContext);
            return;
        }
        if (!hasBleScanAndConnect(activityContext)) {
            PttKeySetupActivity.notifyBleScanError(activityContext);
            return;
        }
        c.ensureAdapter();
        if (c.mAdapter == null || !c.mAdapter.isEnabled()) {
            PttKeySetupActivity.notifyBleScanError(activityContext);
            return;
        }
        c.stopScanInternal();
        c.mScanner = c.mAdapter.getBluetoothLeScanner();
        if (c.mScanner == null) {
            Log.w(TAG, "getBluetoothLeScanner null");
            PttKeySetupActivity.notifyBleScanError(activityContext);
            return;
        }
        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            c.mScanning = true;
            c.mScanner.startScan(null, settings, c.mScanCallback);
            c.mMain.removeCallbacks(c.mScanTimeout);
            c.mMain.postDelayed(c.mScanTimeout, SCAN_TIMEOUT_MS);
            PttKeySetupActivity.notifyBleScanStarted(activityContext);
            Log.d(TAG, "LE scan started");
        } catch (Exception e) {
            Log.e(TAG, "startScan failed", e);
            c.mScanning = false;
            PttKeySetupActivity.notifyBleScanError(activityContext);
        }
    }

    static boolean hasBleScanAndConnect(Context ctx) {
        if (Build.VERSION.SDK_INT >= ANDROID_12_API) {
            boolean scan = isRuntimePermissionGranted(ctx, PERM_BT_SCAN);
            boolean connect = isRuntimePermissionGranted(ctx, PERM_BT_CONNECT);
            boolean fine = isRuntimePermissionGranted(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION);
            boolean coarse = isRuntimePermissionGranted(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION);
            return scan && connect && (fine || coarse);
        }
        if (Build.VERSION.SDK_INT >= ANDROID_6_API) {
            boolean fine = isRuntimePermissionGranted(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION);
            boolean coarse = isRuntimePermissionGranted(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION);
            return fine || coarse;
        }
        return true;
    }

    private void applyPrefs() {
        if (mHost == null) {
            return;
        }
        if (!PttPreferences.isPttBleButtonEnabled(mApp)) {
            boolean configuringBeforeSave = PttKeySetupActivity.isSetupScreenVisible()
                    && PttKeySetupActivity.isBleCheckboxCheckedInSetup();
            if (!configuringBeforeSave) {
                mMain.removeCallbacks(mReconnectRunnable);
                mReconnectScheduled = false;
                stopScanInternal();
                disconnectGatt();
                mSessionReadyAddress = null;
                BluetoothPttRoutingManager.clearToggleLatchIfNeeded();
            }
            return;
        }
        String addr = PttPreferences.getBlePttDeviceAddress(mApp);
        if (addr != null && mGatt == null && !mScanning) {
            tryConnectByAddress(addr);
        }
    }

    private void scheduleReconnectIfNeeded() {
        if (!PttPreferences.isPttBleButtonEnabled(mApp)) {
            return;
        }
        if (PttPreferences.getBlePttDeviceAddress(mApp) == null) {
            return;
        }
        if (mHost == null || mReconnectScheduled) {
            return;
        }
        mReconnectScheduled = true;
        mMain.postDelayed(mReconnectRunnable, RECONNECT_DELAY_MS);
    }

    private void tryConnectByAddress(String address) {
        ensureAdapter();
        if (mAdapter == null || address == null || mHost == null) {
            return;
        }
        if (!hasBleScanAndConnect(mHost)) {
            return;
        }
        try {
            BluetoothDevice dev = mAdapter.getRemoteDevice(address);
            connectGatt(dev, true);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "bad BLE address: " + address, e);
        }
    }

    @SuppressLint("MissingPermission")
    private void connectGatt(BluetoothDevice device, boolean autoConnect) {
        if (device == null || mHost == null) {
            return;
        }
        disconnectGatt();
        try {
            mGatt = device.connectGatt(mApp, autoConnect, mGattCallback);
            Log.d(TAG, "connectGatt autoConnect=" + autoConnect);
        } catch (Exception e) {
            Log.e(TAG, "connectGatt", e);
            mGatt = null;
        }
    }

    private void disconnectGatt() {
        BluetoothGatt g = mGatt;
        mGatt = null;
        mSessionReadyAddress = null;
        if (g != null) {
            try {
                g.disconnect();
            } catch (Exception ignored) {
            }
            try {
                g.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void clearGattReference(BluetoothGatt gatt) {
        if (gatt == null) {
            return;
        }
        if (mGatt == gatt) {
            mGatt = null;
        }
        try {
            gatt.close();
        } catch (Exception ignored) {
        }
    }

    private void stopScanInternal() {
        mMain.removeCallbacks(mScanTimeout);
        if (!mScanning || mScanner == null) {
            mScanning = false;
            return;
        }
        try {
            mScanner.stopScan(mScanCallback);
        } catch (Exception e) {
            Log.w(TAG, "stopScan: " + e.getMessage());
        }
        mScanning = false;
    }

    private void ensureAdapter() {
        if (mAdapter != null) {
            return;
        }
        BluetoothManager bm = (BluetoothManager) mApp.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            mAdapter = bm.getAdapter();
        }
    }

    private static String deviceNameFromScan(ScanResult result) {
        if (result.getScanRecord() != null) {
            String n = result.getScanRecord().getDeviceName();
            if (n != null && !n.isEmpty()) {
                return n;
            }
        }
        if (result.getDevice() != null) {
            try {
                @SuppressLint("MissingPermission")
                String n = result.getDevice().getName();
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static boolean isLikelyTargetFromScan(ScanResult result) {
        String name = deviceNameFromScan(result);
        if (name != null) {
            String n = name.trim();
            if (TARGET_DEVICE_NAME.equals(n) || n.startsWith("PTT-Z")) {
                return true;
            }
        }
        if (result.getScanRecord() == null) {
            return false;
        }
        List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
        if (uuids == null) {
            return false;
        }
        for (ParcelUuid pu : uuids) {
            if (pu != null && UUID_SVC_FFE0.equals(pu.getUuid())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRuntimePermissionGranted(Context context, String permission) {
        if (Build.VERSION.SDK_INT < ANDROID_6_API) {
            return true;
        }
        try {
            Method m = Context.class.getMethod("checkSelfPermission", String.class);
            Object r = m.invoke(context, permission);
            return r instanceof Integer && ((Integer) r).intValue() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            // Fallback: if reflection fails, keep old behavior.
            return context.getPackageManager().checkPermission(permission, context.getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private static String characteristicValueAsUtf8Trimmed(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            return new String(raw, "UTF-8").trim();
        } catch (Exception e) {
            return null;
        }
    }

    private void notifyConnectFailureFromSetupUi(final int status) {
        final boolean likelyBusy = status == 8 || status == 133 || status == 257;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                PttKeySetupActivity.notifyBleConnectStatus(mApp, status, likelyBusy);
            }
        });
    }
}
