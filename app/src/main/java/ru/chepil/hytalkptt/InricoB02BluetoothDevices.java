package ru.chepil.hytalkptt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import java.util.Set;

/**
 * Bonded device lookup for Inrico B02PTT headsets.
 */
public final class InricoB02BluetoothDevices {

    private InricoB02BluetoothDevices() {}

    /** First bonded device whose name starts with {@link InricoB02PttFrames#DEFAULT_NAME_PREFIX}. */
    public static BluetoothDevice findBondedInricoB02PttOrNull(BluetoothAdapter adapter) {
        if (adapter == null) {
            return null;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            return null;
        }
        for (BluetoothDevice d : bonded) {
            String name = d.getName();
            if (name != null && name.startsWith(InricoB02PttFrames.DEFAULT_NAME_PREFIX)) {
                return d;
            }
        }
        return null;
    }
}
