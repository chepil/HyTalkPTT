package ru.chepil.hytalkptt;

import android.content.Context;
import android.view.KeyEvent;

/** Single entry for headset / Bluetooth media keys after {@link BluetoothMediaButtonReceiver}. */
final class MediaButtonKeyDispatcher {

    private MediaButtonKeyDispatcher() {}

    static void deliver(Context appContext, KeyEvent ev) {
        PTTAccessibilityService.dispatchBluetoothMediaKey(appContext, ev);
    }
}
