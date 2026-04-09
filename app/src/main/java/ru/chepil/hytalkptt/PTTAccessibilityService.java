package ru.chepil.hytalkptt;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

public class PTTAccessibilityService extends AccessibilityService {

    private static final String TAG = "PTTAccessibilityService";
    private static final String TAG_PTT_TRACE = "HyTalkPTT-PTTTrace";

    private static WeakReference<PTTAccessibilityService> sInstanceRef;

    private Object inputManager = null;
    private Method injectInputEventMethod = null;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstanceRef = new WeakReference<PTTAccessibilityService>(this);
        BareMediaButtonPtt.resetState();
        Log.d(TAG, "PTT Accessibility Service connected");

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        } else {
            AccessibilityServiceInfo serviceInfo = new AccessibilityServiceInfo();
            serviceInfo.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            serviceInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            serviceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            serviceInfo.notificationTimeout = 100;
            setServiceInfo(serviceInfo);
        }

        if (Build.VERSION.SDK_INT >= 23) {
            initInputManager();
        }

        BluetoothPttCoordinator.syncRouting(getApplicationContext());
        BluetoothPttRoutingManager.attachHost(this);
    }

    private void initInputManager() {
        try {
            inputManager = getSystemService(Context.INPUT_SERVICE);
            if (inputManager != null) {
                Class<?> inputManagerClass = inputManager.getClass();
                injectInputEventMethod = inputManagerClass.getMethod(
                        "injectInputEvent",
                        android.view.InputEvent.class,
                        int.class);
                Log.d(TAG, "InputManager initialized for key remapping (Android " + Build.VERSION.SDK_INT + ")");
            } else {
                Log.w(TAG, "InputManager service not available");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize InputManager for key remapping: " + e.getMessage());
            inputManager = null;
            injectInputEventMethod = null;
        }
    }

    @SuppressWarnings("unused")
    private boolean injectKeyEvent(int keyCode, int action) {
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }
        if (inputManager == null || injectInputEventMethod == null) {
            return false;
        }
        try {
            long now = System.currentTimeMillis();
            KeyEvent keyEvent = new KeyEvent(
                    now,
                    action == KeyEvent.ACTION_DOWN ? now : now + 100,
                    action,
                    keyCode,
                    0,
                    0,
                    InputDevice.SOURCE_KEYBOARD,
                    0,
                    KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD);
            int injectMode = 0;
            Boolean result = (Boolean) injectInputEventMethod.invoke(inputManager, keyEvent, injectMode);
            if (result != null && result) {
                Log.d(TAG, "Successfully injected KeyEvent: keyCode=" + keyCode + ", action=" + action);
                return true;
            }
            Log.w(TAG, "KeyEvent injection failed (may require system privileges)");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Exception while injecting KeyEvent: " + e.getMessage());
            return false;
        }
    }

    private boolean isConfiguredPttKey(int keyCode) {
        boolean hardware = PttPreferences.isPttHardwareSourceEnabled(this);
        boolean bluetooth = PttPreferences.isPttBluetoothSourceEnabled(this);
        if (!hardware && !bluetooth) {
            return false;
        }
        if (hardware && keyCode == PttPreferences.getPttKeyCode(this)) {
            return true;
        }
        return bluetooth && BluetoothPttRoutingManager.isBluetoothHeadsetPttKeyForPtt(this, keyCode);
    }

    public static void pauseBluetoothMediaForKeyLearning() {
        BluetoothPttRoutingManager.pauseBluetoothMediaForKeyLearning();
    }

    public static void resumeBluetoothMediaForKeyLearning() {
        BluetoothPttRoutingManager.resumeBluetoothMediaForKeyLearning();
    }

    public static void refreshBluetoothMediaRoutingFromUi(Context context) {
        BluetoothPttCoordinator.syncRouting(context);
        BluetoothPttRoutingManager.refreshFromUi(context);
    }

    /** @deprecated Prefer {@link BluetoothPttRoutingManager#dispatchBluetoothMediaKey}; kept for callers. */
    @SuppressWarnings("unused")
    public static void dispatchBluetoothMediaKey(Context context, KeyEvent event) {
        BluetoothPttRoutingManager.dispatchBluetoothMediaKey(context, event);
    }

    /** @deprecated Prefer {@link BluetoothPttRoutingManager} internal dispatch; kept for compatibility. */
    @SuppressWarnings("unused")
    public static void dispatchVendorHeadsetIntent(Context context, android.content.Intent intent) {
        BluetoothPttRoutingManager.dispatchVendorHeadsetIntent(context, intent);
    }

    private void pttTrace(String line) {
        Log.i(TAG_PTT_TRACE, line + " |uptimeMs=" + SystemClock.uptimeMillis());
    }

    private boolean handlePttKeyEvent(KeyEvent event, boolean hardwareKeyRepeatSendsPttDown) {
        return PttHyTalkActions.handlePttKeyEvent(this, event, hardwareKeyRepeatSendsPttDown, null);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        int savedPttKey = PttPreferences.getPttKeyCode(this);
        if (keyCode == savedPttKey) {
            pttTrace("onKeyEvent SAVED_PTT_KEY key=" + keyCode + " act=" + event.getAction()
                    + " rep=" + event.getRepeatCount()
                    + " hwSrc=" + PttPreferences.isPttHardwareSourceEnabled(this)
                    + " btSrc=" + PttPreferences.isPttBluetoothSourceEnabled(this)
                    + " willHandle=" + isConfiguredPttKey(keyCode));
        }

        if (PttKeySetupActivity.isSetupScreenVisible()
                && BluetoothPttRoutingManager.isBluetoothHeadsetMediaKey(keyCode)) {
            PttKeySetupActivity.onMediaButtonKey(getApplicationContext(), event);
            return true;
        }

        if (PttPreferences.isPttBluetoothSourceEnabled(this)
                && BluetoothPttRoutingManager.isBluetoothHeadsetMediaKey(keyCode)) {
            BluetoothHeadsetProbeLog.logKeyEvent("Accessibility-onKeyEvent", event);
        }

        if (BluetoothPttRoutingManager.tryConsumeAccessibilityToggleLatch(this, event)) {
            return true;
        }

        if (!isConfiguredPttKey(keyCode)) {
            return false;
        }

        pttTrace("onKeyEvent PTT key=" + keyCode + " act=" + event.getAction() + " rep=" + event.getRepeatCount());
        return handlePttKeyEvent(event, true);
    }

    @Override
    public void onDestroy() {
        BluetoothPttRoutingManager.detachHost(this);
        PttHyTalkActions.clearCachedBroadcastPackage();
        BluetoothPttCoordinator.syncRouting(getApplicationContext());
        sInstanceRef = null;
        super.onDestroy();
        Log.d(TAG, "PTT Accessibility Service destroyed");
    }
}
