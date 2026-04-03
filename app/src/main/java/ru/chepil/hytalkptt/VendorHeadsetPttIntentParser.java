package ru.chepil.hytalkptt;

import android.bluetooth.BluetoothHeadset;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.Arrays;

/**
 * Parses {@link BluetoothHeadset#ACTION_VENDOR_SPECIFIC_HEADSET_EVENT} for Inrico/B02-style
 * {@code +XEVENT} with {@code TALK,1} / {@code TALK,0} (company id 85).
 */
final class VendorHeadsetPttIntentParser {

    private static long sLastVendorDedupeAtMs;
    private static String sLastVendorDedupeKey;

    private VendorHeadsetPttIntentParser() {}

    /**
     * Same intent can be seen twice in quick succession on some stacks; drop the second.
     */
    static boolean shouldDropDuplicateVendorIntent(Intent intent) {
        synchronized (VendorHeadsetPttIntentParser.class) {
            long now = SystemClock.uptimeMillis();
            String key = buildDedupeKey(intent);
            if (now - sLastVendorDedupeAtMs < 50 && key.equals(sLastVendorDedupeKey)) {
                return true;
            }
            sLastVendorDedupeAtMs = now;
            sLastVendorDedupeKey = key;
            return false;
        }
    }

    private static String buildDedupeKey(Intent intent) {
        if (intent == null) {
            return "";
        }
        String cmd = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD);
        Object[] args = extractArgsArray(intent);
        return String.valueOf(intent.getCategories()) + "|" + cmd + "|" + formatArgs(args);
    }

    /**
     * @return {@link Boolean#TRUE} = PTT down, {@link Boolean#FALSE} = PTT up, {@code null} if not a TALK frame
     */
    static Boolean resolvePressRelease(Intent intent) {
        if (intent == null) {
            return null;
        }
        String cmd = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD);
        Object[] args = extractArgsArray(intent);

        if (args != null && args.length >= 2) {
            Object a0 = args[0];
            Object a1 = args[1];
            if (a0 instanceof String && a1 instanceof Integer) {
                if ("TALK".equalsIgnoreCase(((String) a0).trim())) {
                    int v = (Integer) a1;
                    if (v == 1) {
                        return Boolean.TRUE;
                    }
                    if (v == 0) {
                        return Boolean.FALSE;
                    }
                }
            }
            if (a0 instanceof String && a1 instanceof Long) {
                if ("TALK".equalsIgnoreCase(((String) a0).trim())) {
                    long v = (Long) a1;
                    if (v == 1L) {
                        return Boolean.TRUE;
                    }
                    if (v == 0L) {
                        return Boolean.FALSE;
                    }
                }
            }
            for (Object o : args) {
                if (o instanceof String) {
                    String s = ((String) o).toUpperCase();
                    if (s.contains("TALK,1")) {
                        return Boolean.TRUE;
                    }
                    if (s.contains("TALK,0")) {
                        return Boolean.FALSE;
                    }
                }
            }
        }

        if (cmd != null) {
            String c = cmd.toUpperCase();
            if (c.contains("TALK,1") || c.contains("=TALK,1")) {
                return Boolean.TRUE;
            }
            if (c.contains("TALK,0") || c.contains("=TALK,0")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static Object[] extractArgsArray(Intent intent) {
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
}
