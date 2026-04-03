package ru.chepil.hytalkptt;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.util.Log;

/**
 * Huawei {@code MediaSessionService} often routes AVRCP keys to {@code com.android.mediacenter}
 * when that app has the dominant session. A minimal metadata payload makes HyTalkPTT look like a
 * real media session (some ROMs use this in priority heuristics).
 */
final class PttMediaSessionMetadata {

    private static final String TAG = "HyTalkPTT-MediaSession";

    private PttMediaSessionMetadata() {}

    static void applyPttPlaceholder(MediaSession session) {
        if (session == null) {
            return;
        }
        try {
            MediaMetadata meta = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "HyTalk PTT")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, " ")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 86_400_000L)
                    .build();
            session.setMetadata(meta);
        } catch (Throwable t) {
            Log.w(TAG, "setMetadata failed: " + t.getMessage());
        }
    }
}
