package ru.chepil.hytalkptt;

import android.app.Application;

public class HyTalkPttApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PttPreferences.ensureDefault(this);
        BluetoothPttCoordinator.syncRouting(this);
    }
}
