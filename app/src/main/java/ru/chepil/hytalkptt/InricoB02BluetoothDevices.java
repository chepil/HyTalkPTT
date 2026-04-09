package ru.chepil.hytalkptt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Set;

/**
 * Bonded device lookup for Inrico B02PTT headsets.
 */
public final class InricoB02BluetoothDevices {

    private static final int API_31 = 31;
    private static final String PERM_BT_CONNECT = "android.permission.BLUETOOTH_CONNECT";

    private InricoB02BluetoothDevices() {}

    /**
     * First bonded device whose name starts with {@link InricoB02PttFrames#DEFAULT_NAME_PREFIX}.
     *
     * @param context used for {@code BLUETOOTH_CONNECT} check on API 31+ (lint)
     */
    public static BluetoothDevice findBondedInricoB02PttOrNull(Context context, BluetoothAdapter adapter) {
        if (adapter == null) {
            return null;
        }
        Context app = context != null ? context.getApplicationContext() : null;
        if (Build.VERSION.SDK_INT >= API_31) {
            if (app == null
                    || app.getPackageManager().checkPermission(PERM_BT_CONNECT, app.getPackageName())
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            return null;
        }
        for (BluetoothDevice d : bonded) {
            String name;
            try {
                name = d.getName();
            } catch (SecurityException ignored) {
                continue;
            }
            if (name != null && name.startsWith(InricoB02PttFrames.DEFAULT_NAME_PREFIX)) {
                return d;
            }
        }
        return null;
    }
}
