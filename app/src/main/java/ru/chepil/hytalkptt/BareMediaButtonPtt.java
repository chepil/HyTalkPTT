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
 * <p>
 * When {@link PttPreferences#isBluetoothPttMediaToggleLatch} is on, each bare intent is only a
 * synthetic DOWN — the Bluetooth toggle handler ignores UP (AVRCP short pulse), so alternating
 * DOWN/UP would make the second physical tap a synthetic UP and transmit would never stop.
 * <p>
 * Many stacks deliver <strong>two</strong> {@code MEDIA_BUTTON} intents per physical press (~50–100&nbsp;ms
 * apart). Without deduping, the second synthetic DOWN immediately toggles transmit off again.
 */
final class BareMediaButtonPtt {

    private static final String TAG = "HyTalkPTT-MediaBtn";
    private static final Object LOCK = new Object();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final long AUTO_UP_MS = 5000L;
    /** Drop duplicate bare intents in toggle mode within this window after the last accepted one. */
    private static final long BARE_TOGGLE_DEDUPE_MS = 150L;

    private static boolean latchedDown;
    private static Runnable autoUpRunnable;
    /** Wall time of last accepted bare synthetic event in media-toggle-latch mode. */
    private static long sLastBareToggleAcceptedWallMs;

    private BareMediaButtonPtt() {}

    static void resetState() {
        synchronized (LOCK) {
            cancelAutoUp();
            latchedDown = false;
            sLastBareToggleAcceptedWallMs = 0L;
        }
    }

    static KeyEvent nextSyntheticKeyEvent(Context context) {
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (PttPreferences.isBluetoothPttMediaToggleLatch(app)) {
                cancelAutoUp();
                latchedDown = false;
                long now = SystemClock.uptimeMillis();
                if (sLastBareToggleAcceptedWallMs != 0L
                        && now - sLastBareToggleAcceptedWallMs < BARE_TOGGLE_DEDUPE_MS) {
                    Log.i(TAG, "bare MEDIA_BUTTON -> ignored (toggle dedupe, "
                            + (now - sLastBareToggleAcceptedWallMs) + "ms since last)");
                    return null;
                }
                sLastBareToggleAcceptedWallMs = now;
                KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0,
                        0, 0, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
                Log.i(TAG, "bare MEDIA_BUTTON -> synthetic DOWN (media toggle latch)");
                return ev;
            }
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
