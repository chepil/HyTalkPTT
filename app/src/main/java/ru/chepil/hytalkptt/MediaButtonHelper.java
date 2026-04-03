package ru.chepil.hytalkptt;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Huawei / newer Android route headset keys through {@link android.media.session.MediaSession}
 * {@code setMediaButtonReceiver(PendingIntent)} — not only {@link android.media.AudioManager}
 * {@code registerMediaButtonEventReceiver(ComponentName)}.
 */
final class MediaButtonHelper {

    /**
     * {@link PendingIntent#FLAG_IMMUTABLE} (API 23; bit 26 in current AOSP). Required on API 31+
     * together with explicit component {@link Intent#setClass}.
     */
    private static final int PI_FLAG_IMMUTABLE = 1 << 26;

    private MediaButtonHelper() {}

    static int mediaButtonPendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PI_FLAG_IMMUTABLE;
        }
        return flags;
    }

    static PendingIntent mediaButtonPendingIntent(Context context, Class<?> receiverClass) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.setPackage(context.getPackageName());
        intent.setClass(context, receiverClass);
        return PendingIntent.getBroadcast(context, 0, intent, mediaButtonPendingIntentFlags());
    }
}
