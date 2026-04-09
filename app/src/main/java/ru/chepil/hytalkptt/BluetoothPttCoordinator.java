package ru.chepil.hytalkptt;

import android.content.Context;
import android.content.Intent;

/**
 * Starts {@link BluetoothPttService} when classic Bluetooth PTT and/or SPP is enabled and accessibility is off;
 * stops it when both are disabled or accessibility owns routing.
 */
final class BluetoothPttCoordinator {

    private BluetoothPttCoordinator() {}

    static void syncRouting(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        boolean bt = PttPreferences.isPttBluetoothSourceEnabled(app);
        boolean spp = PttPreferences.isPttBluetoothSppEnabled(app);
        boolean a11y = PttAccessibilityHelper.isHyTalkPttServiceEnabled(app);
        Intent svc = new Intent(app, BluetoothPttService.class);
        if (!bt && !spp) {
            app.stopService(svc);
            return;
        }
        if (a11y) {
            app.stopService(svc);
        } else {
            app.startService(svc);
        }
    }
}
