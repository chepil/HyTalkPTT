package ru.chepil.hytalkptt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Classic Bluetooth SPP (RFCOMM) reader for PTT devices such as Inrico B02PTT-FF01.
 */
final class BluetoothSppPttConnector {

    private static final String TAG = "HyTalkPTT-SPP";

    /** Serial Port Profile UUID (Bluetooth SIG). */
    static final UUID RFCOMM_SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int API_31 = 31;
    private static final String PERM_BT_CONNECT = "android.permission.BLUETOOTH_CONNECT";

    private final Context mApp;
    private final Handler mMainHandler;
    private final AtomicBoolean mStop = new AtomicBoolean(true);
    private Thread mThread;
    private volatile BluetoothSocket mSocket;

    private InricoB02SppStreamParser mParser;

    interface PttSink {
        void onSppPttDown();

        void onSppPttUp();
    }

    private final PttSink mPttSink;

    BluetoothSppPttConnector(Context appContext, PttSink pttSink) {
        mApp = appContext.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
        mPttSink = pttSink;
    }

    synchronized void start() {
        if (!mStop.getAndSet(false)) {
            return;
        }
        mParser = new InricoB02SppStreamParser(new InricoB02SppStreamParser.Listener() {
            @Override
            public void onPttPressed() {
                mPttSink.onSppPttDown();
            }

            @Override
            public void onPttReleased() {
                mPttSink.onSppPttUp();
            }

            @Override
            public void onUnknownBytes(byte[] data) {
                showUnknownSppToast(data);
            }
        });
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "hytalk-spp-read");
        mThread.start();
    }

    synchronized void stop() {
        mStop.set(true);
        closeSocketQuietly();
        if (mThread != null) {
            mThread.interrupt();
            mThread = null;
        }
        mParser = null;
    }

    private void runLoop() {
        while (!mStop.get()) {
            if (!PttPreferences.isPttBluetoothSppEnabled(mApp)) {
                sleepQuiet(500);
                continue;
            }
            if (Build.VERSION.SDK_INT >= API_31
                    && mApp.getPackageManager().checkPermission(PERM_BT_CONNECT, mApp.getPackageName())
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_CONNECT not granted — SPP disabled until permission is granted");
                sleepQuiet(3000);
                continue;
            }
            BluetoothAdapter adapter = bluetoothAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                sleepQuiet(2000);
                continue;
            }
            BluetoothDevice device = InricoB02BluetoothDevices.findBondedInricoB02PttOrNull(mApp, adapter);
            if (device == null) {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded != null && !bonded.isEmpty()) {
                    device = bonded.iterator().next();
                }
            }
            if (device == null) {
                Log.w(TAG, "No bonded Bluetooth devices for SPP");
                sleepQuiet(3000);
                continue;
            }
            BluetoothSocket socket = null;
            try {
                Log.i(TAG, "SPP connect → " + deviceLabelForLog(device));
                socket = device.createRfcommSocketToServiceRecord(RFCOMM_SPP_UUID);
                mSocket = socket;
                socket.connect();
                Log.i(TAG, "SPP connected");
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[4096];
                InricoB02SppStreamParser parser = mParser;
                while (!mStop.get() && parser != null) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    parser.accept(chunk);
                    parser = mParser;
                }
            } catch (IOException e) {
                Log.e(TAG, "SPP: " + e.getMessage());
            } finally {
                mSocket = null;
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
                if (!mStop.get()) {
                    sleepQuiet(2000);
                }
            }
        }
    }

    private BluetoothAdapter bluetoothAdapter() {
        BluetoothManager bm = (BluetoothManager) mApp.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            return BluetoothAdapter.getDefaultAdapter();
        }
        return bm.getAdapter();
    }

    private void closeSocketQuietly() {
        BluetoothSocket s = mSocket;
        mSocket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * {@link BluetoothDevice#getName()} and {@link BluetoothDevice#getAddress()} require
     * {@code BLUETOOTH_CONNECT} on API 31+; lint requires an explicit permission check.
     */
    private String deviceLabelForLog(BluetoothDevice d) {
        if (d == null) {
            return "null";
        }
        if (Build.VERSION.SDK_INT >= API_31) {
            if (mApp.getPackageManager().checkPermission(PERM_BT_CONNECT, mApp.getPackageName())
                    != PackageManager.PERMISSION_GRANTED) {
                return "<no BT_CONNECT permission>";
            }
        }
        try {
            String n = d.getName();
            String a = d.getAddress();
            return (n != null ? n : "<no name>") + " " + a;
        } catch (SecurityException ignored) {
            return "<device>";
        }
    }

    private void showUnknownSppToast(final byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        final String hex = toHexSpaceSeparated(data);
        final String utf8 = utf8Lossy(data);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                String msg = mApp.getString(R.string.ptt_spp_unknown_message, hex, utf8);
                if (msg.length() > 350) {
                    msg = msg.substring(0, 347) + "...";
                }
                Toast.makeText(mApp, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String toHexSpaceSeparated(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(java.util.Locale.US, "%02X", data[i] & 0xff));
        }
        return sb.toString();
    }

    private static String utf8Lossy(byte[] data) {
        String s = new String(data, Charset.forName("UTF-8"));
        return s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", ".");
    }
}
