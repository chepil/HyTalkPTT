package ru.chepil.hytalkptt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Receives {@link Intent#ACTION_MEDIA_BUTTON} for Bluetooth / headset PTT.
 * Also used while {@link PttKeySetupActivity} is open ({@link MediaButtonHelper} targets this class).
 * A single exported receiver avoids duplicate delivery from two manifest filters.
 */
public class BluetoothMediaButtonReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "HyTalkPTT-MediaBtn";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }
        Log.i(LOG_TAG, "raw MEDIA_BUTTON delivered to app");
        KeyEvent ev = MediaButtonKeyEventParser.fromIntent(intent);
        if (ev == null) {
            ev = BareMediaButtonPtt.nextSyntheticKeyEvent(context);
        }
        if (ev == null) {
            return;
        }
        Log.i(LOG_TAG, "receiver: " + KeyEvent.keyCodeToString(ev.getKeyCode())
                + " action=" + ev.getAction() + " repeat=" + ev.getRepeatCount());
        MediaButtonKeyDispatcher.deliver(context.getApplicationContext(), ev);
    }
}
