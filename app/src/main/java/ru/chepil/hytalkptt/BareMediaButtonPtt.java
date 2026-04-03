package ru.chepil.hytalkptt;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * Headsets such as Inrico B02PTT sometimes send {@link Intent#ACTION_MEDIA_BUTTON} with no
 * {@link Intent#EXTRA_KEY_EVENT}. We map each such intent to alternating synthetic
 * {@link KeyEvent#KEYCODE_HEADSETHOOK} DOWN/UP. A timeout forces UP if a matching UP never arrives.
 */
final class BareMediaButtonPtt {

    private static final String TAG = "HyTalkPTT-MediaBtn";
    private static final Object LOCK = new Object();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final long AUTO_UP_MS = 5000L;

    private static boolean latchedDown;
    private static Runnable autoUpRunnable;

    private BareMediaButtonPtt() {}

    static void resetState() {
        synchronized (LOCK) {
            cancelAutoUp();
            latchedDown = false;
        }
    }

    static KeyEvent nextSyntheticKeyEvent(Context context) {
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            long now = SystemClock.uptimeMillis();
            latchedDown = !latchedDown;
            int action = latchedDown ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
            KeyEvent ev = new KeyEvent(now, now, action, KeyEvent.KEYCODE_HEADSETHOOK, 0,
                    0, 0, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);

            if (latchedDown) {
                scheduleAutoUp(app);
            } else {
                cancelAutoUp();
            }
            Log.i(TAG, "bare MEDIA_BUTTON -> synthetic " + (action == KeyEvent.ACTION_DOWN ? "DOWN" : "UP"));
            return ev;
        }
    }

    private static void cancelAutoUp() {
        if (autoUpRunnable != null) {
            HANDLER.removeCallbacks(autoUpRunnable);
            autoUpRunnable = null;
        }
    }

    private static void scheduleAutoUp(final Context app) {
        cancelAutoUp();
        autoUpRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    autoUpRunnable = null;
                    if (!latchedDown) {
                        return;
                    }
                    latchedDown = false;
                    long now = SystemClock.uptimeMillis();
                    KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0,
                            0, 0, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
                    Log.i(TAG, "bare MEDIA_BUTTON -> synthetic UP (timeout)");
                    MediaButtonKeyDispatcher.deliver(app, up);
                }
            }
        };
        HANDLER.postDelayed(autoUpRunnable, AUTO_UP_MS);
    }
}
