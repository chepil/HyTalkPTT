package ru.chepil.hytalkptt;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.lang.reflect.Method;
import java.util.List;

public class PTTAccessibilityService extends AccessibilityService {

    private static final String TAG = "PTTAccessibilityService";
    private static final int PTT_KEYCODE1 = 228; // Physical PTT button keycode (Motorola LEX F10)
    private static final int PTT_KEYCODE2 = 520; //Scanner Uravo DT 30
    private static final int PTT_KEYCODE3 = 521; //Scanner Uravo DT 30
    private static final int PTT_KEYCODE4 = 522; //Scanner Uravo DT 30
    private static final int PTT_KEYCODE5 = 381; //Ulefone Armor 26 WT
    private static final int PTT_KEYCODE6 = 301; //Ulefone Armor 20 WT
    private static final int PTT_KEYCODE7 = 131; //Ulefone Armor 18T
    private static final int REMAPPED_PTT_KEYCODE = 142; // Keycode that HyTalk expects (F12)
    
    // InputManager for key remapping on newer Android versions
    private Object inputManager = null;
    private Method injectInputEventMethod = null;
    
    // HyTalk package names
    private static final String[] POSSIBLE_PACKAGE_NAMES = {
            "com.hytera.ocean"
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed - we only use KeyEvent interception
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "PTT Accessibility Service connected");
        
        // Configure service to request key event filtering
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        } else {
            // Fallback: create new service info
            AccessibilityServiceInfo serviceInfo = new AccessibilityServiceInfo();
            serviceInfo.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            serviceInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            serviceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            serviceInfo.notificationTimeout = 100;
            setServiceInfo(serviceInfo);
        }
        
        // Initialize InputManager for key remapping on Android >= 23 (Marshmallow)
        // Note: Build.VERSION_CODES.M is not available in SDK 22, so we use numeric value
        if (Build.VERSION.SDK_INT >= 23) {
            initInputManager();
        }
    }
    
    /**
     * Initializes InputManager using reflection for key remapping on newer Android versions.
     * This allows remapping keycodes 520, 521, 522 to 142 (which HyTalk expects).
     */
    private void initInputManager() {
        try {
            // Get InputManager service (it's not in public API, so we use getSystemService)
            inputManager = getSystemService(Context.INPUT_SERVICE);
            
            if (inputManager != null) {
                // Get injectInputEvent method using reflection (it's a hidden method)
                Class<?> inputManagerClass = inputManager.getClass();
                injectInputEventMethod = inputManagerClass.getMethod(
                    "injectInputEvent", 
                    android.view.InputEvent.class, 
                    int.class
                );
                
                Log.d(TAG, "InputManager initialized for key remapping (Android " + Build.VERSION.SDK_INT + ")");
            } else {
                Log.w(TAG, "InputManager service not available");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize InputManager for key remapping: " + e.getMessage());
            // InputManager injection may not be available - we'll fall back to broadcast intents
            inputManager = null;
            injectInputEventMethod = null;
        }
    }
    
    /**
     * Attempts to inject a KeyEvent using InputManager (requires system privileges on most devices).
     * Falls back to broadcast intents if injection is not available or fails.
     * 
     * @param keyCode The keycode to inject
     * @param action KeyEvent.ACTION_DOWN or KeyEvent.ACTION_UP
     * @return true if injection was attempted, false otherwise
     */
    private boolean injectKeyEvent(int keyCode, int action) {
        // InputManager injection only available on API 23+ (Marshmallow)
        // Note: Build.VERSION_CODES.M is not available in SDK 22, so we use numeric value
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }
        
        if (inputManager == null || injectInputEventMethod == null) {
            return false; // InputManager not initialized
        }
        
        try {
            long now = System.currentTimeMillis();
            KeyEvent keyEvent = new KeyEvent(
                now, // downTime
                action == KeyEvent.ACTION_DOWN ? now : now + 100, // eventTime
                action, // action
                keyCode, // code
                0, // repeat
                0, // metaState
                InputDevice.SOURCE_KEYBOARD, // deviceId
                0, // scancode
                KeyEvent.FLAG_FROM_SYSTEM, // flags
                InputDevice.SOURCE_KEYBOARD // source
            );
            
            // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC = 0
            // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1
            int injectMode = 0; // ASYNC mode
            
            Boolean result = (Boolean) injectInputEventMethod.invoke(inputManager, keyEvent, injectMode);
            
            if (result != null && result) {
                Log.d(TAG, "Successfully injected KeyEvent: keyCode=" + keyCode + ", action=" + action);
                return true;
            } else {
                Log.w(TAG, "KeyEvent injection failed (may require system privileges)");
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Exception while injecting KeyEvent: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        
        // Handle PTT button events
        if (keyCode == PTT_KEYCODE1 || keyCode == PTT_KEYCODE2 || keyCode == PTT_KEYCODE3 || keyCode == PTT_KEYCODE4 || keyCode == PTT_KEYCODE5 || keyCode == PTT_KEYCODE6 || keyCode == PTT_KEYCODE7) {
            boolean isScannerKey = (keyCode == PTT_KEYCODE2 || keyCode == PTT_KEYCODE3 || keyCode == PTT_KEYCODE4 || keyCode == PTT_KEYCODE5 || keyCode == PTT_KEYCODE6 || keyCode == PTT_KEYCODE7);
            // Remap scanner keys (520, 521, 522) to keycode 142 on Android > SDK 22
            boolean shouldRemap = isScannerKey && Build.VERSION.SDK_INT > 22;
            
            if (action == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "PTT button pressed (onKeyEvent - ACTION_DOWN), keyCode=" + keyCode);
                MainActivity.isPTTButtonPressed = true;
                
                // Launch HyTalk directly (or bring to foreground if already running)
                launchHyTalkIfNeeded();
                
                // For scanner keys (520, 521, 522) on Android > 22, try to remap to keycode 142
                if (shouldRemap) {
                    boolean injected = injectKeyEvent(REMAPPED_PTT_KEYCODE, KeyEvent.ACTION_DOWN);
                    if (injected) {
                        Log.d(TAG, "Remapped keycode " + keyCode + " -> " + REMAPPED_PTT_KEYCODE + " (injected)");
                        // Also send broadcast intent as fallback
                        if (event.getRepeatCount() == 0) {
                            sendPTTBroadcast(true);
                        }
                        return true; // Consume the original event
                    } else {
                        Log.d(TAG, "Key remapping injection failed, using broadcast intent fallback");
                        // Fall through to broadcast intent method
                    }
                }
                
                // Send Broadcast Intent for HyTalk (based on pttremap app logic)
                // HyTalk listens to "android.intent.action.PTT_DOWN" broadcast
                if (event.getRepeatCount() == 0) {
                    sendPTTBroadcast(true);
                }
                
                return true; // Consume the event - don't pass it to other apps
            } else if (action == KeyEvent.ACTION_UP) {
                Log.d(TAG, "PTT button released (onKeyEvent - ACTION_UP), keyCode=" + keyCode);
                MainActivity.isPTTButtonPressed = false;
                
                // For scanner keys (520, 521, 522) on Android > 22, try to remap to keycode 142
                if (shouldRemap) {
                    boolean injected = injectKeyEvent(REMAPPED_PTT_KEYCODE, KeyEvent.ACTION_UP);
                    if (injected) {
                        Log.d(TAG, "Remapped keycode " + keyCode + " -> " + REMAPPED_PTT_KEYCODE + " (injected)");
                        // Also send broadcast intent as fallback
                        sendPTTBroadcast(false);
                        return true; // Consume the original event
                    } else {
                        Log.d(TAG, "Key remapping injection failed, using broadcast intent fallback");
                        // Fall through to broadcast intent method
                    }
                }
                
                // Send Broadcast Intent for HyTalk (based on pttremap app logic)
                sendPTTBroadcast(false);
                
                return true; // Consume the event - don't pass it to other apps
            }
        }
        
        // Don't intercept other keys
        return false;
    }
    
    /**
     * Launches HyTalk app directly or brings it to foreground if already running.
     * This is called when PTT button is pressed to ensure HyTalk is active.
     */
    private void launchHyTalkIfNeeded() {
        try {
            Intent launchIntent = findHyTalkApp();
            if (launchIntent != null) {
                // Add flags to bring app to foreground or launch if not running
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                
                startActivity(launchIntent);
                Log.d(TAG, "Launched/brought HyTalk to foreground");
            } else {
                Log.w(TAG, "HyTalk app not found - cannot launch");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error launching HyTalk", e);
        }
    }
    
    /**
     * Finds the HyTalk app launch intent.
     * @return Intent to launch HyTalk, or null if not found
     */
    private Intent findHyTalkApp() {
        PackageManager pm = getPackageManager();
        
        // First, try the known package names
        for (String packageName : POSSIBLE_PACKAGE_NAMES) {
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                Log.d(TAG, "Found HyTalk app: " + packageName);
                return launchIntent;
            }
        }
        
        // If not found, search through all installed packages
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
        
        for (ResolveInfo info : apps) {
            String packageName = info.activityInfo.packageName.toLowerCase();
            if (packageName.contains("hytera") || packageName.contains("hytalk")) {
                Log.d(TAG, "Found potential HyTalk app: " + packageName);
                Intent launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName);
                if (launchIntent != null) {
                    Log.d(TAG, "Launching HyTalk with package: " + info.activityInfo.packageName);
                    return launchIntent;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Sends Broadcast Intent for HyTalk PTT button.
     * Based on pttremap app logic: sends "android.intent.action.PTT_DOWN" or "PTT_UP"
     */
    private void sendPTTBroadcast(boolean isDown) {
        try {
            String action = isDown ? "android.intent.action.PTT_DOWN" : "android.intent.action.PTT_UP";
            Intent intent = new Intent(action);
            sendBroadcast(intent);
            Log.d(TAG, "Sent PTT Broadcast Intent: " + action);
        } catch (Exception e) {
            Log.e(TAG, "Error sending PTT Broadcast Intent", e);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "PTT Accessibility Service destroyed");
    }
}
