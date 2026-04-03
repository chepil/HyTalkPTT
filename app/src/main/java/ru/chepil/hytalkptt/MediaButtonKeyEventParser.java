package ru.chepil.hytalkptt;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;

/** Resolves {@link KeyEvent} from {@link Intent#ACTION_MEDIA_BUTTON} (OEMs vary). */
final class MediaButtonKeyEventParser {

    private static final String TAG = "HyTalkPTT-MediaBtn";

    private MediaButtonKeyEventParser() {}

    static KeyEvent fromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (ev != null) {
            return ev;
        }
        Bundle ex = intent.getExtras();
        if (ex == null) {
            return null;
        }
        for (String key : ex.keySet()) {
            Object val = ex.get(key);
            if (val instanceof KeyEvent) {
                Log.i(TAG, "KeyEvent in extra \"" + key + "\" (non-standard)");
                return (KeyEvent) val;
            }
        }
        if (!ex.isEmpty()) {
            logExtras(intent);
        }
        return null;
    }

    private static void logExtras(Intent intent) {
        Bundle ex = intent.getExtras();
        if (ex == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String key : ex.keySet()) {
            sb.append(key).append('=').append(String.valueOf(ex.get(key))).append("; ");
        }
        Log.i(TAG, "MEDIA_BUTTON extras (no KeyEvent): " + sb);
    }
}
