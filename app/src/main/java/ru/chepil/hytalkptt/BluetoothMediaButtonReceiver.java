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
    /** Same timeline as {@code PTTAccessibilityService#TAG_PTT_TRACE}. */
    private static final String TAG_TRACE = "HyTalkPTT-PTTTrace";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }
        BluetoothHeadsetProbeLog.logMediaButtonIntent(intent);
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
        Log.i(TAG_TRACE, "MEDIA_BUTTON receiver -> deliver " + KeyEvent.keyCodeToString(ev.getKeyCode())
                + " act=" + ev.getAction() + " rep=" + ev.getRepeatCount());
        MediaButtonKeyDispatcher.deliver(context.getApplicationContext(), ev);
    }
}
