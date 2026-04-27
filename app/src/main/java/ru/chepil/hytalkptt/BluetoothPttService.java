package ru.chepil.hytalkptt;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps Bluetooth PTT (media session, HFP vendor, media button) alive when
 * {@link PTTAccessibilityService} is disabled. Does not auto-launch HyTalk — broadcasts only.
 */
public class BluetoothPttService extends Service {

    private static final String TAG = "BluetoothPttService";

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothPttRoutingManager.attachHost(this);
        BlePttZ01Controller.attachHost(this);
        Log.d(TAG, "Bluetooth PTT routing attached (no accessibility)");
    }

    @Override
    public void onDestroy() {
        BlePttZ01Controller.detachHost(this);
        BluetoothPttRoutingManager.detachHost(this);
        Log.d(TAG, "Bluetooth PTT routing detached");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
