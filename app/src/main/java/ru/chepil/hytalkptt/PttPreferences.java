package ru.chepil.hytalkptt;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * PTT keycode stored in app sandbox (SharedPreferences).
 * Default 228 (Motorola LEX F10), overridable via PttKeySetupActivity.
 */
public final class PttPreferences {

    private static final String PREFS_NAME = "ru.chepil.hytalkptt.ptt_prefs";
    private static final String KEY_PTT_KEYCODE = "ptt_keycode";
    private static final String KEY_PTT_SOURCE_HARDWARE = "ptt_source_hardware";
    private static final String KEY_PTT_SOURCE_BLUETOOTH = "ptt_source_bluetooth";
    private static final String KEY_PTT_SOURCE_BLUETOOTH_SPP = "ptt_source_bluetooth_spp";
    /** When false, {@link android.view.KeyEvent#KEYCODE_MEDIA_PLAY} is not treated as Bluetooth PTT (power/hook). */
    private static final String KEY_BT_INCLUDE_MEDIA_PLAY = "ptt_bt_include_media_play";
    /**
     * When true, each short AVRCP/media key press toggles TX on/off (for headsets that only send DOWN+UP in one burst).
     */
    private static final String KEY_BT_MEDIA_TOGGLE_LATCH = "ptt_bt_media_toggle_latch";
    /** Default PTT keycode for Motorola LEX F10. */
    public static final int DEFAULT_PTT_KEYCODE = 228;
    /** Preference key for {@link #isPttBluetoothSourceEnabled(Context)} — use with preference listeners. */
    public static final String PREF_KEY_PTT_SOURCE_BLUETOOTH = KEY_PTT_SOURCE_BLUETOOTH;
    public static final String PREF_KEY_PTT_BT_INCLUDE_MEDIA_PLAY = KEY_BT_INCLUDE_MEDIA_PLAY;
    public static final String PREF_KEY_PTT_BT_MEDIA_TOGGLE_LATCH = KEY_BT_MEDIA_TOGGLE_LATCH;
    public static final String PREF_KEY_PTT_BLUETOOTH_SPP = KEY_PTT_SOURCE_BLUETOOTH_SPP;

    private PttPreferences() {}

    /** Same file as other PTT settings; use application context for long-lived listeners. */
    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getPttKeyCode(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_PTT_KEYCODE, DEFAULT_PTT_KEYCODE);
    }

    public static void setPttKeyCode(Context context, int keyCode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_PTT_KEYCODE, keyCode)
                .apply();
    }

    /** When true, {@link #getPttKeyCode} is used for device-side PTT. Default true. */
    public static boolean isPttHardwareSourceEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PTT_SOURCE_HARDWARE, true);
    }

    public static void setPttHardwareSourceEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PTT_SOURCE_HARDWARE, enabled)
                .apply();
    }

    /**
     * When true, headset / Bluetooth mic PTT keys (HFP hook, AVRCP media) are handled.
     * Default false.
     */
    public static boolean isPttBluetoothSourceEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PTT_SOURCE_BLUETOOTH, false);
    }

    public static void setPttBluetoothSourceEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PTT_SOURCE_BLUETOOTH, enabled)
                .apply();
    }

    /**
     * When true, classic Bluetooth RFCOMM SPP is used for PTT (e.g. Inrico B02PTT-FF01 {@code +PTT=P}/{@code +PTT=R}).
     * Default false.
     */
    public static boolean isPttBluetoothSppEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PTT_SOURCE_BLUETOOTH_SPP, false);
    }

    public static void setPttBluetoothSppEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PTT_SOURCE_BLUETOOTH_SPP, enabled).apply();
    }

    /**
     * When false, Bluetooth PTT ignores {@link android.view.KeyEvent#KEYCODE_MEDIA_PLAY} only
     * (many headsets use the same code for power / hook and dedicated PTT may use another path).
     * Default true (legacy behavior).
     */
    public static boolean isBluetoothPttIncludeMediaPlay(Context context) {
        return prefs(context).getBoolean(KEY_BT_INCLUDE_MEDIA_PLAY, true);
    }

    public static void setBluetoothPttIncludeMediaPlay(Context context, boolean include) {
        prefs(context).edit().putBoolean(KEY_BT_INCLUDE_MEDIA_PLAY, include).apply();
    }

    /**
     * When true, Bluetooth media keys (PLAY/PAUSE/…) toggle transmit: first tap = PTT on (with hold refresh),
     * second tap = PTT off. Use when the device only sends a short pulse, not a real hold.
     */
    public static boolean isBluetoothPttMediaToggleLatch(Context context) {
        return prefs(context).getBoolean(KEY_BT_MEDIA_TOGGLE_LATCH, false);
    }

    /**
     * Saves sources and optional keycode using {@link android.content.SharedPreferences.Editor#commit()}.
     * Needed so values exist before {@link android.app.Activity#onPause()} runs ( {@code apply()} is async and
     * races with {@link PTTAccessibilityService#resumeBluetoothMediaForKeyLearning()}).
     */
    public static void commitPttConfiguration(Context context, boolean hardware, boolean bluetooth,
            boolean bluetoothSpp,
            boolean bluetoothIncludeMediaPlay, boolean bluetoothMediaToggleLatch, int lastKeyCodeOrMinusOne) {
        SharedPreferences.Editor ed = prefs(context).edit();
        if (hardware && lastKeyCodeOrMinusOne >= 0) {
            ed.putInt(KEY_PTT_KEYCODE, lastKeyCodeOrMinusOne);
        }
        ed.putBoolean(KEY_PTT_SOURCE_HARDWARE, hardware);
        ed.putBoolean(KEY_PTT_SOURCE_BLUETOOTH, bluetooth);
        ed.putBoolean(KEY_PTT_SOURCE_BLUETOOTH_SPP, bluetoothSpp);
        ed.putBoolean(KEY_BT_INCLUDE_MEDIA_PLAY, bluetoothIncludeMediaPlay);
        ed.putBoolean(KEY_BT_MEDIA_TOGGLE_LATCH, bluetoothMediaToggleLatch);
        ed.commit();
    }

    /**
     * Ensures default PTT keycode (228) is stored if none exists yet.
     * Call on app start.
     */
    public static void ensureDefault(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_PTT_KEYCODE)) {
            prefs.edit().putInt(KEY_PTT_KEYCODE, DEFAULT_PTT_KEYCODE).apply();
        }
    }
}
