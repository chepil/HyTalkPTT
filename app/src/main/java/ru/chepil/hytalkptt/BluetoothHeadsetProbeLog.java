package ru.chepil.hytalkptt;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.Arrays;

/**
 * Debug logging for Retevis Ailunce HD2 and similar: proximity / vendor / AVRCP paths.
 * Filter: {@code adb logcat -v time 'HyTalkPTT-BtProbe:I' '*:S'} (quote for zsh).
 */
final class BluetoothHeadsetProbeLog {

    static final String TAG = "HyTalkPTT-BtProbe";

    private BluetoothHeadsetProbeLog() {}

    static void logVendorIntent(Context context, Intent intent, String source) {
        if (intent == null) {
            return;
        }
        String cmd = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD);
        int cmdType = intent.getIntExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE, Integer.MIN_VALUE);
        Object[] args = extractVendorArgs(intent);
        BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        // Avoid BluetoothDevice.getName(): requires BLUETOOTH_CONNECT on API 31+ (lint MissingPermission).
        String deviceStr = dev != null ? dev.getAddress() : "null";
        Log.i(TAG, "[" + source + "] categories=" + String.valueOf(intent.getCategories())
                + " device=" + deviceStr
                + " cmd=" + cmd
                + " cmdType=" + (cmdType == Integer.MIN_VALUE ? "?" : String.valueOf(cmdType))
                + " args=" + formatArgs(args));
        Bundle extras = intent.getExtras();
        if (extras != null) {
            Log.i(TAG, "[" + source + "] extras: " + bundleToString(extras));
        }
    }

    static void logMediaButtonIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        Bundle extras = intent.getExtras();
        Log.i(TAG, "[MEDIA_BUTTON] action=" + intent.getAction()
                + (extras != null ? (" | " + bundleToString(extras)) : " | (no extras)"));
    }

    static void logKeyEvent(String source, KeyEvent ev) {
        if (ev == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append("[KEY ").append(source).append("] ");
        sb.append(KeyEvent.keyCodeToString(ev.getKeyCode()));
        sb.append(" action=").append(ev.getAction());
        sb.append(" repeat=").append(ev.getRepeatCount());
        sb.append(" scan=").append(ev.getScanCode());
        sb.append(" flags=0x").append(Integer.toHexString(ev.getFlags()));
        sb.append(" source=").append(ev.getSource());
        InputDevice d = ev.getDevice();
        if (d != null) {
            sb.append(" dev=").append(d.getName());
        } else {
            sb.append(" dev=null");
        }
        Log.i(TAG, sb.toString());
    }

    private static Object[] extractVendorArgs(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return null;
        }
        Object raw = extras.get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS);
        if (raw instanceof Object[]) {
            return (Object[]) raw;
        }
        return null;
    }

    private static String formatArgs(Object[] args) {
        if (args == null) {
            return "null";
        }
        if (args.length == 0) {
            return "[]";
        }
        String[] parts = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            Object o = args[i];
            if (o == null) {
                parts[i] = "null";
            } else if (o instanceof byte[]) {
                parts[i] = "0x" + toHex((byte[]) o);
            } else {
                parts[i] = String.valueOf(o);
            }
        }
        return Arrays.toString(parts);
    }

    private static String toHex(byte[] b) {
        if (b == null || b.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02X", x & 0xff));
        }
        return sb.toString();
    }

    private static String bundleToString(Bundle b) {
        StringBuilder sb = new StringBuilder();
        for (String k : b.keySet()) {
            Object v = b.get(k);
            sb.append(k).append('=');
            if (v instanceof byte[]) {
                sb.append("0x").append(toHex((byte[]) v));
            } else {
                sb.append(String.valueOf(v));
            }
            sb.append("; ");
        }
        return sb.toString();
    }
}
