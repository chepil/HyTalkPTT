package ru.chepil.hytalkptt;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * HyTalk launch + PTT broadcasts shared by {@link PTTAccessibilityService} (hardware PTT) and
 * {@link BluetoothPttRoutingManager} (Bluetooth PTT). Auto-launch runs only when accessibility is enabled.
 */
final class PttHyTalkActions {

    private static final String TAG = "PTTAccessibilityService";
    private static final String TAG_PTT_TRACE = "HyTalkPTT-PTTTrace";

    private static final String[] POSSIBLE_PACKAGE_NAMES = {
            "com.hytera.ocean",
            "com.hytalkpro.ocean"
    };

    private static volatile String sCachedHyTalkBroadcastPackage;
    private static long sLastPttDownHandledTimeMs;

    interface BluetoothDownDebounceSink {
        void clearDebounceSkipFlag();

        void onDuplicateDownSkipped();
    }

    private PttHyTalkActions() {}

    static void clearCachedBroadcastPackage() {
        sCachedHyTalkBroadcastPackage = null;
    }

    static boolean handlePttKeyEvent(Context context, KeyEvent event,
            boolean hardwareKeyRepeatSendsPttDown, BluetoothDownDebounceSink btSink) {
        if (btSink != null) {
            btSink.clearDebounceSkipFlag();
        }
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                long now = System.currentTimeMillis();
                if (now - sLastPttDownHandledTimeMs < 150) {
                    pttTrace("DOWN debounced (<150ms dup path) keyCode=" + keyCode + " hwPath=" + hardwareKeyRepeatSendsPttDown);
                    Log.d(TAG, "PTT down debounced (duplicate BT path), keyCode=" + keyCode);
                    if (!hardwareKeyRepeatSendsPttDown && btSink != null) {
                        btSink.onDuplicateDownSkipped();
                    }
                    return true;
                }
                sLastPttDownHandledTimeMs = now;
                pttTrace("DOWN -> launch+broadcast keyCode=" + keyCode + " hwPath=" + hardwareKeyRepeatSendsPttDown);
                Log.d(TAG, "PTT pressed, keyCode=" + keyCode);
                MainActivity.isPTTButtonPressed = true;
                if (PttAccessibilityHelper.isHyTalkPttServiceEnabled(context)) {
                    launchHyTalkIfNeeded(context);
                } else {
                    Log.d(TAG, "HyTalk auto-launch skipped (accessibility service disabled)");
                }
                sendPTTBroadcast(context, true);
            } else if (hardwareKeyRepeatSendsPttDown) {
                pttTrace("DOWN repeat keyCode=" + keyCode + " repeat=" + event.getRepeatCount());
                Log.d(TAG, "PTT pressed (repeat), keyCode=" + keyCode + " repeat=" + event.getRepeatCount());
                sendPTTBroadcast(context, true);
            }
            return true;
        }
        if (action == KeyEvent.ACTION_UP) {
            pttTrace("UP -> broadcast keyCode=" + keyCode + " hwPath=" + hardwareKeyRepeatSendsPttDown);
            Log.d(TAG, "PTT released, keyCode=" + keyCode);
            MainActivity.isPTTButtonPressed = false;
            sendPTTBroadcast(context, false);
            return true;
        }
        return false;
    }

    private static void pttTrace(String line) {
        Log.i(TAG_PTT_TRACE, line + " |uptimeMs=" + SystemClock.uptimeMillis());
    }

    static void launchHyTalkIfNeeded(Context context) {
        try {
            Intent launchIntent = findHyTalkApp(context);
            if (launchIntent != null) {
                if (launchIntent.getComponent() != null) {
                    sCachedHyTalkBroadcastPackage = launchIntent.getComponent().getPackageName();
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                context.startActivity(launchIntent);
                pttTrace("startActivity ok pkg=" + sCachedHyTalkBroadcastPackage);
                Log.d(TAG, "Launched/brought HyTalk to foreground");
            } else {
                Log.w(TAG, "HyTalk app not found - cannot launch");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error launching HyTalk", e);
        }
    }

    static Intent findHyTalkApp(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String packageName : POSSIBLE_PACKAGE_NAMES) {
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                Log.d(TAG, "Found HyTalk app: " + packageName);
                return launchIntent;
            }
        }
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
        String selfPackage = context.getPackageName().toLowerCase();
        for (ResolveInfo info : apps) {
            String packageName = info.activityInfo.packageName.toLowerCase();
            if (packageName.equals(selfPackage)) {
                continue;
            }
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

    static void sendPTTBroadcast(Context context, boolean isDown) {
        try {
            String action = isDown ? "android.intent.action.PTT_DOWN" : "android.intent.action.PTT_UP";
            Intent intent = new Intent(action);
            String pkg = resolveHyTalkPackageNameForBroadcast(context);
            if (pkg != null) {
                intent.setPackage(pkg);
            }
            context.sendBroadcast(intent);
            pttTrace("broadcast " + action + (pkg != null ? (" pkg=" + pkg) : " implicit"));
            Log.d(TAG, "Sent PTT Broadcast Intent: " + action + (pkg != null ? (" package=" + pkg) : " (no HyTalk package; implicit)"));
        } catch (Exception e) {
            pttTrace("broadcast FAILED " + (isDown ? "PTT_DOWN" : "PTT_UP") + " " + e.getMessage());
            Log.e(TAG, "Error sending PTT Broadcast Intent", e);
        }
    }

    static String resolveHyTalkPackageNameForBroadcast(Context context) {
        if (sCachedHyTalkBroadcastPackage != null) {
            return sCachedHyTalkBroadcastPackage;
        }
        Intent launch = findHyTalkApp(context);
        if (launch != null && launch.getComponent() != null) {
            sCachedHyTalkBroadcastPackage = launch.getComponent().getPackageName();
            return sCachedHyTalkBroadcastPackage;
        }
        PackageManager pm = context.getPackageManager();
        for (String packageName : POSSIBLE_PACKAGE_NAMES) {
            try {
                pm.getApplicationInfo(packageName, 0);
                sCachedHyTalkBroadcastPackage = packageName;
                return sCachedHyTalkBroadcastPackage;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }
}
