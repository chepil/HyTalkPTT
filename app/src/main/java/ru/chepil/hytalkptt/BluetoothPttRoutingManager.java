package ru.chepil.hytalkptt;

import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Method;

/**
 * Owns Bluetooth PTT capture ({@link MediaSession}, vendor HFP, legacy media button) while bound to a
 * {@link Context} that stays alive — either {@link PTTAccessibilityService} or {@link BluetoothPttService}.
 */
final class BluetoothPttRoutingManager implements PttHyTalkActions.BluetoothDownDebounceSink {

    private static final String TAG = "PTTAccessibilityService";
    private static final String TAG_PTT_TRACE = "HyTalkPTT-PTTTrace";

    private static final int REMAPPED_PTT_KEYCODE = 142;

    private static BluetoothPttRoutingManager sInstance;

    private final Context mApp;

    private Context mHost;

    private MediaSession mBluetoothMediaSession;
    private ComponentName mMediaButtonReceiverComponent;

    private static final String CATEGORY_HEADSET_COMPANY_85 =
            "android.bluetooth.headset.intent.category.companyid.85";
    private static final int RECEIVER_EXPORTED_FLAG = 2;
    private static final String ANDROID_PERMISSION_BLUETOOTH = "android.permission.BLUETOOTH";

    private BroadcastReceiver mVendorHeadsetReceiver;
    private boolean mVendorHeadsetRegistered;
    private BroadcastReceiver mVendorProbeLooseReceiver;
    private boolean mVendorProbeLooseRegistered;

    private static final long BLUETOOTH_PTT_DOWN_REPEAT_MS = 400L;

    private Handler mBluetoothPttHoldRepeatHandler;
    private final Runnable mBluetoothPttHoldRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            PttHyTalkActions.sendPTTBroadcast(mApp, true);
            if (mBluetoothPttHoldRepeatHandler != null) {
                mBluetoothPttHoldRepeatHandler.postDelayed(this, BLUETOOTH_PTT_DOWN_REPEAT_MS);
            }
        }
    };

    private boolean mBluetoothPttDebounceSkippedFirstDown;

    private boolean mBluetoothPttMediaLatched;
    private long mLastBluetoothToggleDownWallMs;
    private long mBluetoothToggleOnUptimeMs;
    private static final long BLUETOOTH_TOGGLE_MIN_HOLD_BEFORE_OFF_MS = 310L;

    private static final long BT_TOGGLE_ROUTING_RECLAIM_FIRST_MS = 400L;
    private static final long BT_TOGGLE_ROUTING_RECLAIM_PERIOD_MS = 2800L;

    private Runnable mBtToggleRoutingReclaimRunnable;

    private BluetoothSppPttConnector mSppConnector;

    private final BluetoothSppPttConnector.PttSink mSppPttSink = new BluetoothSppPttConnector.PttSink() {
        @Override
        public void onSppPttDown() {
            if (PttKeySetupActivity.isSetupScreenVisible()) {
                PttKeySetupActivity.onSppPttForUi(true);
                return;
            }
            deliverVendorHeadsetPtt(true);
        }

        @Override
        public void onSppPttUp() {
            if (PttKeySetupActivity.isSetupScreenVisible()) {
                PttKeySetupActivity.onSppPttForUi(false);
                return;
            }
            deliverVendorHeadsetPtt(false);
        }
    };

    private AudioManager.OnAudioFocusChangeListener mBluetoothMusicFocusListener;
    private boolean mBluetoothMusicFocusHeld;

    private final SharedPreferences.OnSharedPreferenceChangeListener mPttPrefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                    if (PttPreferences.PREF_KEY_PTT_SOURCE_BLUETOOTH.equals(key)
                            || PttPreferences.PREF_KEY_PTT_BT_INCLUDE_MEDIA_PLAY.equals(key)
                            || PttPreferences.PREF_KEY_PTT_BT_MEDIA_TOGGLE_LATCH.equals(key)
                            || PttPreferences.PREF_KEY_PTT_BLUETOOTH_SPP.equals(key)
                            || PttPreferences.PREF_KEY_PTT_BLE_BUTTON.equals(key)
                            || PttPreferences.PREF_KEY_BLE_PTT_DEVICE_ADDRESS.equals(key)) {
                        if (PttPreferences.PREF_KEY_PTT_SOURCE_BLUETOOTH.equals(key)
                                && !sharedPreferences.getBoolean(PttPreferences.PREF_KEY_PTT_SOURCE_BLUETOOTH, false)) {
                            clearBluetoothMediaToggleLatchIfTransmitting();
                        }
                        if (PttPreferences.PREF_KEY_PTT_BT_MEDIA_TOGGLE_LATCH.equals(key)
                                && !sharedPreferences.getBoolean(PttPreferences.PREF_KEY_PTT_BT_MEDIA_TOGGLE_LATCH, false)) {
                            clearBluetoothMediaToggleLatchIfTransmitting();
                        }
                        if (PttPreferences.PREF_KEY_PTT_BLE_BUTTON.equals(key)
                                && !sharedPreferences.getBoolean(PttPreferences.PREF_KEY_PTT_BLE_BUTTON, false)) {
                            clearBluetoothMediaToggleLatchIfTransmitting();
                        }
                        refresh();
                        BluetoothPttCoordinator.syncRouting(mApp);
                    }
                }
            };

    private BluetoothPttRoutingManager(Context appContext) {
        mApp = appContext;
    }

    static synchronized void attachHost(Context host) {
        Context app = host.getApplicationContext();
        if (sInstance != null && sInstance.mHost != null && sInstance.mHost != host) {
            sInstance.releaseBluetoothResources();
            sInstance.mHost = null;
        }
        if (sInstance == null) {
            sInstance = new BluetoothPttRoutingManager(app);
            PttPreferences.prefs(app).registerOnSharedPreferenceChangeListener(sInstance.mPttPrefsListener);
        }
        sInstance.mHost = host;
        sInstance.refresh();
    }

    static synchronized void detachHost(Context host) {
        if (sInstance == null || sInstance.mHost != host) {
            return;
        }
        sInstance.releaseBluetoothResources();
        PttPreferences.prefs(sInstance.mApp).unregisterOnSharedPreferenceChangeListener(sInstance.mPttPrefsListener);
        sInstance.mHost = null;
        sInstance = null;
    }

    static void dispatchBluetoothMediaKey(Context context, KeyEvent event) {
        if (context == null || event == null) {
            return;
        }
        if (PttKeySetupActivity.isSetupScreenVisible()) {
            PttKeySetupActivity.onMediaButtonKey(context.getApplicationContext(), event);
            return;
        }
        if (!PttPreferences.isPttBluetoothSourceEnabled(context.getApplicationContext())) {
            Log.w("HyTalkPTT-MediaBtn", "ignored: classic Bluetooth PTT disabled in Configure PTT — enable + Save");
            return;
        }
        BluetoothPttRoutingManager mgr = sInstance;
        if (mgr == null || mgr.mHost == null) {
            Log.w(TAG, "Bluetooth key: routing host not running (enable Bluetooth PTT + Save, open app once)");
            return;
        }
        if (!isBluetoothHeadsetPttKeyForPtt(context.getApplicationContext(), event.getKeyCode())) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY
                    && !PttPreferences.isBluetoothPttIncludeMediaPlay(context.getApplicationContext())) {
                Log.d(TAG, "Bluetooth key ignored: MEDIA_PLAY off in Configure PTT (power/hook)");
            } else {
                Log.d(TAG, "Bluetooth key ignored keyCode=" + event.getKeyCode());
            }
            return;
        }
        Log.i(TAG_PTT_TRACE, "dispatchBluetoothMediaKey key=" + event.getKeyCode() + " act=" + event.getAction()
                + " rep=" + event.getRepeatCount() + " |uptimeMs=" + SystemClock.uptimeMillis());
        mgr.handleBluetoothMediaPttKeyEvent(event);
    }

    /**
     * BLE PTT-Z01 (FFE1 notify): pressed/released or toggle when {@link PttPreferences#isBluetoothPttMediaToggleLatch}
     * is enabled.
     */
    static void dispatchBlePttButton(Context app, boolean pressed) {
        if (app == null) {
            return;
        }
        Context ctx = app.getApplicationContext();
        if (!PttPreferences.isPttBleButtonEnabled(ctx)) {
            return;
        }
        if (PttKeySetupActivity.isSetupScreenVisible()) {
            PttKeySetupActivity.onBlePttForUi(pressed);
            return;
        }
        BluetoothPttRoutingManager mgr = sInstance;
        if (mgr == null || mgr.mHost == null) {
            Log.w(TAG, "BLE PTT: routing host not running (accessibility or background Bluetooth service)");
            return;
        }
        mgr.deliverBlePttButton(pressed);
    }

    static void dispatchVendorHeadsetIntent(Context context, Intent intent) {
        if (intent == null
                || !BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT.equals(intent.getAction())) {
            return;
        }
        if (!intent.hasCategory(CATEGORY_HEADSET_COMPANY_85)) {
            return;
        }
        if (VendorHeadsetPttIntentParser.shouldDropDuplicateVendorIntent(intent)) {
            return;
        }
        Boolean parsed = VendorHeadsetPttIntentParser.resolvePressRelease(intent);
        if (parsed == null) {
            return;
        }
        if (PttKeySetupActivity.isSetupScreenVisible()) {
            PttKeySetupActivity.onBluetoothVendorPttForUi(parsed.booleanValue());
            return;
        }
        BluetoothPttRoutingManager mgr = sInstance;
        if (mgr == null || mgr.mHost == null) {
            return;
        }
        if (!PttPreferences.isPttBluetoothSourceEnabled(mgr.mApp)) {
            return;
        }
        mgr.deliverVendorHeadsetPtt(parsed.booleanValue());
    }

    static void pauseBluetoothMediaForKeyLearning() {
        BluetoothPttRoutingManager mgr = sInstance;
        if (mgr != null) {
            mgr.pauseBluetoothMediaForKeyLearningInternal();
        }
    }

    static void resumeBluetoothMediaForKeyLearning() {
        BluetoothPttRoutingManager mgr = sInstance;
        if (mgr != null) {
            mgr.resumeBluetoothMediaForKeyLearningInternal();
        }
    }

    static void refreshFromUi(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!PttPreferences.isPttBluetoothSourceEnabled(app) && !PttPreferences.isPttBluetoothSppEnabled(app)
                && !PttPreferences.isPttBleButtonEnabled(app)) {
            return;
        }
        if (PttKeySetupActivity.isSetupScreenVisible()) {
            return;
        }
        BluetoothPttRoutingManager mgr = sInstance;
        if (mgr == null || mgr.mHost == null) {
            return;
        }
        mgr.refresh();
        Log.d(TAG, "Bluetooth PTT: refreshed from UI (e.g. after Music / another media app)");
    }

    static boolean isBluetoothHeadsetMediaKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                || keyCode == KeyEvent.KEYCODE_MEDIA_RECORD
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST;
    }

    static boolean isBluetoothHeadsetPttKeyForPtt(Context ctx, int keyCode) {
        if (!isBluetoothHeadsetMediaKey(keyCode)) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                && !PttPreferences.isBluetoothPttIncludeMediaPlay(ctx)) {
            return false;
        }
        return true;
    }

    /**
     * When accessibility is on, some headsets deliver media keys via {@link AccessibilityService#onKeyEvent}
     * instead of MediaSession — reuse the same toggle-latch logic as the Bluetooth stack.
     */
    static boolean tryConsumeAccessibilityToggleLatch(Context ctx, KeyEvent event) {
        if (!PttPreferences.isBluetoothPttMediaToggleLatch(ctx)
                || !PttPreferences.isPttBluetoothSourceEnabled(ctx)
                || !isBluetoothHeadsetPttKeyForPtt(ctx, event.getKeyCode())) {
            return false;
        }
        BluetoothPttRoutingManager mgr = sInstance;
        if (mgr == null || mgr.mHost == null) {
            return false;
        }
        Log.i(TAG_PTT_TRACE, "onKeyEvent BT-toggle key=" + event.getKeyCode() + " act=" + event.getAction()
                + " rep=" + event.getRepeatCount() + " |uptimeMs=" + SystemClock.uptimeMillis());
        return mgr.handleBluetoothMediaToggleLatch(event);
    }

    @Override
    public void clearDebounceSkipFlag() {
        mBluetoothPttDebounceSkippedFirstDown = false;
    }

    @Override
    public void onDuplicateDownSkipped() {
        mBluetoothPttDebounceSkippedFirstDown = true;
    }

    private void pauseBluetoothMediaForKeyLearningInternal() {
        cancelBluetoothToggleRoutingReclaim();
        abandonBluetoothMusicAudioFocus();
        unregisterLegacyMediaButtonReceiver();
        if (mBluetoothMediaSession != null) {
            try {
                mBluetoothMediaSession.setMediaButtonReceiver(null);
            } catch (Exception ignored) {
            }
            try {
                mBluetoothMediaSession.setActive(false);
            } catch (Exception ignored) {
            }
        }
        if (PttPreferences.isPttBluetoothSppEnabled(mApp) && PttKeySetupActivity.isSetupScreenVisible()) {
            ensureSppReaderRunning();
        }
        Log.d(TAG, "Bluetooth PTT: paused for key learning screen (SPP left on for on-screen PTT readout)");
    }

    private void resumeBluetoothMediaForKeyLearningInternal() {
        refresh();
        Log.d(TAG, "Bluetooth PTT: resumed after key learning screen");
    }

    private void refresh() {
        if (mHost == null) {
            return;
        }
        boolean classic = PttPreferences.isPttBluetoothSourceEnabled(mHost);
        boolean spp = PttPreferences.isPttBluetoothSppEnabled(mHost);
        if (!classic && !spp) {
            releaseBluetoothResources();
            Log.w(TAG, "Bluetooth PTT: OFF in Configure PTT — enable classic Bluetooth and/or SPP, Save");
            return;
        }
        if (PttKeySetupActivity.isSetupScreenVisible()) {
            if (classic) {
                registerVendorHeadsetReceiver();
            } else {
                unregisterVendorHeadsetReceiver();
            }
            if (spp) {
                ensureSppReaderRunning();
            } else {
                stopSppReader();
            }
            return;
        }
        if (classic) {
            if (mBluetoothMediaSession == null) {
                try {
                    mBluetoothMediaSession = new MediaSession(mHost, "HyTalkPTT");
                    mBluetoothMediaSession.setCallback(new MediaSession.Callback() {
                        @Override
                        public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                            return handleBluetoothMediaButtonIntent(mediaButtonIntent);
                        }
                    });
                    mBluetoothMediaSession.setFlags(
                            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
                    PlaybackState state = new PlaybackState.Builder()
                            .setActions(PlaybackState.ACTION_PLAY
                                    | PlaybackState.ACTION_PAUSE
                                    | PlaybackState.ACTION_PLAY_PAUSE
                                    | PlaybackState.ACTION_STOP)
                            .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                            .build();
                    mBluetoothMediaSession.setPlaybackState(state);
                    PttMediaSessionMetadata.applyPttPlaceholder(mBluetoothMediaSession);
                    attachBluetoothMediaButtonPendingIntent();
                    mBluetoothMediaSession.setActive(true);
                    Log.d(TAG, "Bluetooth PTT: MediaSession active (AVRCP / headset keys)");
                    registerLegacyMediaButtonReceiver();
                    requestBluetoothMusicAudioFocus();
                    registerVendorHeadsetReceiver();
                } catch (Exception e) {
                    Log.e(TAG, "Bluetooth PTT: failed to start MediaSession", e);
                    releaseBluetoothResources();
                    return;
                }
            } else {
                attachBluetoothMediaButtonPendingIntent();
                PttMediaSessionMetadata.applyPttPlaceholder(mBluetoothMediaSession);
                try {
                    mBluetoothMediaSession.setActive(true);
                } catch (Exception ignored) {
                }
                registerLegacyMediaButtonReceiver();
                requestBluetoothMusicAudioFocus();
                registerVendorHeadsetReceiver();
                Log.d(TAG, "Bluetooth PTT: MediaSession refreshed (after pause or pref change)");
            }
        } else {
            tearDownClassicBluetoothMedia();
        }
        if (spp) {
            ensureSppReaderRunning();
        } else {
            stopSppReader();
        }
    }

    private void tearDownClassicBluetoothMedia() {
        clearBluetoothMediaToggleLatchIfTransmitting();
        stopBluetoothPttHoldRepeat();
        cancelBluetoothToggleRoutingReclaim();
        abandonBluetoothMusicAudioFocus();
        unregisterVendorHeadsetReceiver();
        unregisterLegacyMediaButtonReceiver();
        if (mBluetoothMediaSession != null) {
            try {
                mBluetoothMediaSession.setMediaButtonReceiver(null);
            } catch (Exception ignored) {
            }
            try {
                mBluetoothMediaSession.setActive(false);
            } catch (Exception ignored) {
            }
            mBluetoothMediaSession.release();
            mBluetoothMediaSession = null;
            Log.d(TAG, "Bluetooth PTT: classic MediaSession released (SPP-only or classic off)");
        }
    }

    private void ensureSppReaderRunning() {
        if (mSppConnector == null) {
            mSppConnector = new BluetoothSppPttConnector(mApp, mSppPttSink);
        }
        mSppConnector.start();
    }

    private void stopSppReader() {
        if (mSppConnector != null) {
            mSppConnector.stop();
        }
    }

    private void requestBluetoothMusicAudioFocus() {
        if (mHost == null || !PttPreferences.isPttBluetoothSourceEnabled(mHost)) {
            return;
        }
        AudioManager am = (AudioManager) mHost.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        if (mBluetoothMusicFocusListener == null) {
            mBluetoothMusicFocusListener = new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    Log.d(TAG, "Bluetooth PTT STREAM_MUSIC focus change: " + focusChange);
                }
            };
        }
        abandonBluetoothMusicAudioFocus();
        int r = am.requestAudioFocus(
                mBluetoothMusicFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        mBluetoothMusicFocusHeld = (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        Log.i(TAG, "Bluetooth PTT: requestAudioFocus(MUSIC, GAIN) result=" + r
                + " — headset media keys should target this app, not Music");
    }

    private void abandonBluetoothMusicAudioFocus() {
        if (!mBluetoothMusicFocusHeld || mBluetoothMusicFocusListener == null) {
            mBluetoothMusicFocusHeld = false;
            return;
        }
        if (mHost == null) {
            mBluetoothMusicFocusHeld = false;
            return;
        }
        AudioManager am = (AudioManager) mHost.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.abandonAudioFocus(mBluetoothMusicFocusListener);
        }
        mBluetoothMusicFocusHeld = false;
    }

    private void ensureBluetoothPttHoldRepeatHandler() {
        if (mBluetoothPttHoldRepeatHandler == null) {
            mBluetoothPttHoldRepeatHandler = new Handler(Looper.getMainLooper());
        }
    }

    private void cancelBluetoothToggleRoutingReclaim() {
        if (mBluetoothPttHoldRepeatHandler != null && mBtToggleRoutingReclaimRunnable != null) {
            mBluetoothPttHoldRepeatHandler.removeCallbacks(mBtToggleRoutingReclaimRunnable);
        }
        mBtToggleRoutingReclaimRunnable = null;
    }

    private void scheduleBluetoothToggleRoutingReclaimLoop() {
        cancelBluetoothToggleRoutingReclaim();
        if (!PttPreferences.isBluetoothPttMediaToggleLatch(mApp)
                || !PttPreferences.isPttBluetoothSourceEnabled(mApp)) {
            return;
        }
        ensureBluetoothPttHoldRepeatHandler();
        mBtToggleRoutingReclaimRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mBluetoothPttMediaLatched
                        || !PttPreferences.isPttBluetoothSourceEnabled(mApp)
                        || PttKeySetupActivity.isSetupScreenVisible()) {
                    mBtToggleRoutingReclaimRunnable = null;
                    return;
                }
                refresh();
                pttTrace("reclaim AVRCP routing (toggle TX on; HyTalk may steal MEDIA_BUTTON)");
                Log.d(TAG, "Bluetooth PTT: reclaim routing (toggle TX — refresh session + focus)");
                if (mBluetoothPttHoldRepeatHandler != null && mBtToggleRoutingReclaimRunnable != null) {
                    mBluetoothPttHoldRepeatHandler.postDelayed(this, BT_TOGGLE_ROUTING_RECLAIM_PERIOD_MS);
                }
            }
        };
        mBluetoothPttHoldRepeatHandler.postDelayed(mBtToggleRoutingReclaimRunnable, BT_TOGGLE_ROUTING_RECLAIM_FIRST_MS);
    }

    private void startBluetoothPttHoldRepeat() {
        stopBluetoothPttHoldRepeat();
        ensureBluetoothPttHoldRepeatHandler();
        mBluetoothPttHoldRepeatHandler.postDelayed(mBluetoothPttHoldRepeatRunnable, BLUETOOTH_PTT_DOWN_REPEAT_MS);
    }

    private void stopBluetoothPttHoldRepeat() {
        if (mBluetoothPttHoldRepeatHandler != null) {
            mBluetoothPttHoldRepeatHandler.removeCallbacks(mBluetoothPttHoldRepeatRunnable);
        }
    }

    private void pttTrace(String line) {
        Log.i(TAG_PTT_TRACE, line + " |uptimeMs=" + SystemClock.uptimeMillis());
    }

    private void deliverBlePttButton(boolean pressed) {
        if (PttPreferences.isBluetoothPttMediaToggleLatch(mApp)) {
            if (pressed) {
                applyBluetoothMediaToggleLatchTapFromBle();
            }
            return;
        }
        deliverVendorHeadsetPtt(pressed);
    }

    /** Same latch semantics as headset media toggle, driven by BLE notify edges. */
    private void applyBluetoothMediaToggleLatchTapFromBle() {
        long now = SystemClock.uptimeMillis();
        if (now - mLastBluetoothToggleDownWallMs < 50) {
            pttTrace("BLE-toggle ignore dup (<50ms)");
            Log.d(TAG, "BLE PTT toggle: ignore duplicate tap");
            return;
        }
        mLastBluetoothToggleDownWallMs = now;
        mBluetoothPttDebounceSkippedFirstDown = false;
        mBluetoothPttMediaLatched = !mBluetoothPttMediaLatched;
        if (mBluetoothPttMediaLatched) {
            pttTrace("BLE-toggle -> TX ON (synthetic DOWN)");
            mBluetoothToggleOnUptimeMs = now;
            KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, REMAPPED_PTT_KEYCODE, 0);
            PttHyTalkActions.handlePttKeyEvent(mApp, ev, false, null);
            scheduleBluetoothToggleRoutingReclaimLoop();
        } else {
            if (mBluetoothToggleOnUptimeMs != 0L
                    && now - mBluetoothToggleOnUptimeMs < BLUETOOTH_TOGGLE_MIN_HOLD_BEFORE_OFF_MS) {
                mBluetoothPttMediaLatched = true;
                pttTrace("BLE-toggle ignore OFF too soon (" + (now - mBluetoothToggleOnUptimeMs) + "ms)");
                Log.d(TAG, "BLE PTT toggle: ignore OFF too soon after ON");
                return;
            }
            mBluetoothToggleOnUptimeMs = 0L;
            stopBluetoothPttHoldRepeat();
            cancelBluetoothToggleRoutingReclaim();
            pttTrace("BLE-toggle -> TX OFF (synthetic UP)");
            KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_UP, REMAPPED_PTT_KEYCODE, 0);
            PttHyTalkActions.handlePttKeyEvent(mApp, ev, false, null);
        }
    }

    private void deliverVendorHeadsetPtt(boolean isDown) {
        if (isDown) {
            KeyEvent ev = new KeyEvent(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN,
                    REMAPPED_PTT_KEYCODE,
                    0);
            PttHyTalkActions.handlePttKeyEvent(mApp, ev, false, this);
            if (!mBluetoothPttDebounceSkippedFirstDown) {
                startBluetoothPttHoldRepeat();
            }
        } else {
            stopBluetoothPttHoldRepeat();
            KeyEvent ev = new KeyEvent(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP,
                    REMAPPED_PTT_KEYCODE,
                    0);
            PttHyTalkActions.handlePttKeyEvent(mApp, ev, false, this);
        }
    }

    private boolean handleBluetoothMediaPttKeyEvent(KeyEvent event) {
        if (PttPreferences.isBluetoothPttMediaToggleLatch(mApp)) {
            return handleBluetoothMediaToggleLatch(event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            stopBluetoothPttHoldRepeat();
        }
        boolean handled = PttHyTalkActions.handlePttKeyEvent(mApp, event, false, this);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && !mBluetoothPttDebounceSkippedFirstDown) {
            startBluetoothPttHoldRepeat();
        }
        return handled;
    }

    private boolean handleBluetoothMediaToggleLatch(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            pttTrace("BT-toggle ignore UP (tap-to-toggle mode)");
            Log.d(TAG, "Bluetooth PTT toggle: ignore UP (latched until second tap)");
            return true;
        }
        if (event.getRepeatCount() != 0) {
            return true;
        }
        long now = SystemClock.uptimeMillis();
        if (now - mLastBluetoothToggleDownWallMs < 50) {
            pttTrace("BT-toggle ignore dup DOWN (<50ms)");
            Log.d(TAG, "Bluetooth PTT toggle: ignore duplicate DOWN (two delivery paths)");
            return true;
        }
        mLastBluetoothToggleDownWallMs = now;
        mBluetoothPttDebounceSkippedFirstDown = false;
        // PttHyTalkActions debounce time is separate; clear not needed for toggle path synthetic keys

        mBluetoothPttMediaLatched = !mBluetoothPttMediaLatched;
        if (mBluetoothPttMediaLatched) {
            pttTrace("BT-toggle -> TX ON (synthetic DOWN, no DOWN keepalive)");
            mBluetoothToggleOnUptimeMs = now;
            KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, REMAPPED_PTT_KEYCODE, 0);
            PttHyTalkActions.handlePttKeyEvent(mApp, ev, false, null);
            scheduleBluetoothToggleRoutingReclaimLoop();
        } else {
            if (mBluetoothToggleOnUptimeMs != 0L
                    && now - mBluetoothToggleOnUptimeMs < BLUETOOTH_TOGGLE_MIN_HOLD_BEFORE_OFF_MS) {
                mBluetoothPttMediaLatched = true;
                pttTrace("BT-toggle ignore OFF too soon after ON (" + (now - mBluetoothToggleOnUptimeMs) + "ms)");
                Log.d(TAG, "Bluetooth PTT toggle: ignore OFF too soon after ON ("
                        + (now - mBluetoothToggleOnUptimeMs) + "ms, min="
                        + BLUETOOTH_TOGGLE_MIN_HOLD_BEFORE_OFF_MS + "ms)");
                return true;
            }
            mBluetoothToggleOnUptimeMs = 0L;
            stopBluetoothPttHoldRepeat();
            cancelBluetoothToggleRoutingReclaim();
            pttTrace("BT-toggle -> TX OFF (synthetic UP)");
            KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_UP, REMAPPED_PTT_KEYCODE, 0);
            PttHyTalkActions.handlePttKeyEvent(mApp, ev, false, null);
        }
        return true;
    }

    static void clearToggleLatchIfNeeded() {
        BluetoothPttRoutingManager mgr = sInstance;
        if (mgr != null) {
            mgr.clearBluetoothMediaToggleLatchIfTransmitting();
        }
    }

    private void clearBluetoothMediaToggleLatchIfTransmitting() {
        if (!mBluetoothPttMediaLatched) {
            return;
        }
        mBluetoothPttMediaLatched = false;
        mBluetoothToggleOnUptimeMs = 0L;
        stopBluetoothPttHoldRepeat();
        cancelBluetoothToggleRoutingReclaim();
        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_UP, REMAPPED_PTT_KEYCODE, 0);
        PttHyTalkActions.handlePttKeyEvent(mApp, ev, false, null);
    }

    @SuppressWarnings("deprecation")
    private void registerLegacyMediaButtonReceiver() {
        if (mMediaButtonReceiverComponent != null || mHost == null) {
            return;
        }
        mMediaButtonReceiverComponent = new ComponentName(mHost, BluetoothMediaButtonReceiver.class);
        AudioManager am = (AudioManager) mHost.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.registerMediaButtonEventReceiver(mMediaButtonReceiverComponent);
            Log.d(TAG, "Bluetooth PTT: registerMediaButtonEventReceiver");
        }
    }

    @SuppressWarnings("deprecation")
    private void unregisterLegacyMediaButtonReceiver() {
        if (mMediaButtonReceiverComponent == null || mHost == null) {
            mMediaButtonReceiverComponent = null;
            return;
        }
        AudioManager am = (AudioManager) mHost.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            try {
                am.unregisterMediaButtonEventReceiver(mMediaButtonReceiverComponent);
            } catch (Exception e) {
                Log.w(TAG, "unregisterMediaButtonEventReceiver: " + e.getMessage());
            }
        }
        mMediaButtonReceiverComponent = null;
    }

    private void attachBluetoothMediaButtonPendingIntent() {
        if (mBluetoothMediaSession == null || mHost == null) {
            return;
        }
        try {
            mBluetoothMediaSession.setMediaButtonReceiver(
                    MediaButtonHelper.mediaButtonPendingIntent(mHost, BluetoothMediaButtonReceiver.class));
            Log.d(TAG, "Bluetooth PTT: MediaSession.setMediaButtonReceiver");
        } catch (Exception e) {
            Log.w(TAG, "Bluetooth PTT: setMediaButtonReceiver failed: " + e.getMessage());
        }
    }

    private void ensureVendorHeadsetReceiverCreated() {
        if (mVendorHeadsetReceiver != null) {
            return;
        }
        mVendorHeadsetReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                BluetoothHeadsetProbeLog.logVendorIntent(context, intent, "VENDOR cat.85");
                BluetoothPttRoutingManager.dispatchVendorHeadsetIntent(context, intent);
            }
        };
    }

    private void ensureVendorProbeLooseReceiverCreated() {
        if (mVendorProbeLooseReceiver != null) {
            return;
        }
        mVendorProbeLooseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                BluetoothHeadsetProbeLog.logVendorIntent(context, intent, "VENDOR action-only");
            }
        };
    }

    private void registerVendorProbeLooseReceiver() {
        if (mHost == null) {
            return;
        }
        ensureVendorProbeLooseReceiverCreated();
        unregisterVendorProbeLooseReceiver();
        IntentFilter loose = new IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Method m = Context.class.getMethod(
                        "registerReceiver",
                        BroadcastReceiver.class,
                        IntentFilter.class,
                        String.class,
                        Handler.class,
                        int.class);
                m.invoke(mHost, mVendorProbeLooseReceiver, loose, ANDROID_PERMISSION_BLUETOOTH, null,
                        Integer.valueOf(RECEIVER_EXPORTED_FLAG));
                mVendorProbeLooseRegistered = true;
            } else if (Build.VERSION.SDK_INT <= 23) {
                mHost.registerReceiver(mVendorProbeLooseReceiver, loose);
                mVendorProbeLooseRegistered = true;
            } else {
                mHost.registerReceiver(mVendorProbeLooseReceiver, loose, ANDROID_PERMISSION_BLUETOOTH, null);
                mVendorProbeLooseRegistered = true;
            }
            Log.d(TAG, "Bluetooth PTT: BtProbe VENDOR action-only registered (uncategorized vendor AT)");
        } catch (Exception e) {
            Log.w(TAG, "Bluetooth PTT: BtProbe loose VENDOR register failed", e);
        }
    }

    private void unregisterVendorProbeLooseReceiver() {
        if (!mVendorProbeLooseRegistered || mVendorProbeLooseReceiver == null || mHost == null) {
            mVendorProbeLooseRegistered = false;
            return;
        }
        try {
            mHost.unregisterReceiver(mVendorProbeLooseReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        mVendorProbeLooseRegistered = false;
    }

    private void registerVendorHeadsetReceiver() {
        if (mHost == null) {
            return;
        }
        ensureVendorHeadsetReceiverCreated();
        IntentFilter filter = new IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
        filter.addCategory(CATEGORY_HEADSET_COMPANY_85);
        boolean registeredCategoryFilter = false;
        try {
            if (mVendorHeadsetRegistered) {
                try {
                    mHost.unregisterReceiver(mVendorHeadsetReceiver);
                } catch (IllegalArgumentException ignored) {
                }
                mVendorHeadsetRegistered = false;
            }
            if (Build.VERSION.SDK_INT >= 33) {
                Method m = Context.class.getMethod(
                        "registerReceiver",
                        BroadcastReceiver.class,
                        IntentFilter.class,
                        String.class,
                        Handler.class,
                        int.class);
                m.invoke(mHost, mVendorHeadsetReceiver, filter, ANDROID_PERMISSION_BLUETOOTH, null,
                        Integer.valueOf(RECEIVER_EXPORTED_FLAG));
                mVendorHeadsetRegistered = true;
                registeredCategoryFilter = true;
                Log.d(TAG, "Bluetooth PTT: VENDOR_SPECIFIC registered (API 33+ category .85 + permission)");
            } else if (Build.VERSION.SDK_INT <= 23) {
                mHost.registerReceiver(mVendorHeadsetReceiver, filter);
                mVendorHeadsetRegistered = true;
                registeredCategoryFilter = true;
                Log.d(TAG, "Bluetooth PTT: VENDOR_SPECIFIC registered (API≤23 category .85, 2-arg)");
            } else {
                mHost.registerReceiver(mVendorHeadsetReceiver, filter, ANDROID_PERMISSION_BLUETOOTH, null);
                mVendorHeadsetRegistered = true;
                registeredCategoryFilter = true;
                Log.d(TAG, "Bluetooth PTT: VENDOR_SPECIFIC registered (category .85 + permission string)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Bluetooth PTT: registerReceiver vendor failed, trying action-only filter", e);
            try {
                IntentFilter loose = new IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
                mHost.registerReceiver(mVendorHeadsetReceiver, loose);
                mVendorHeadsetRegistered = true;
                registeredCategoryFilter = false;
                Log.w(TAG, "Bluetooth PTT: VENDOR_SPECIFIC registered (action-only fallback, no category)");
            } catch (Exception e2) {
                Log.e(TAG, "Bluetooth PTT: VENDOR_SPECIFIC registration failed", e2);
            }
        }
        if (registeredCategoryFilter) {
            registerVendorProbeLooseReceiver();
        }
    }

    private void unregisterVendorHeadsetReceiver() {
        unregisterVendorProbeLooseReceiver();
        if (!mVendorHeadsetRegistered || mVendorHeadsetReceiver == null || mHost == null) {
            mVendorHeadsetRegistered = false;
            return;
        }
        try {
            mHost.unregisterReceiver(mVendorHeadsetReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        mVendorHeadsetRegistered = false;
        Log.d(TAG, "Bluetooth PTT: VENDOR_SPECIFIC_HEADSET_EVENT unregistered");
    }

    private void releaseBluetoothResources() {
        stopSppReader();
        clearBluetoothMediaToggleLatchIfTransmitting();
        stopBluetoothPttHoldRepeat();
        cancelBluetoothToggleRoutingReclaim();
        abandonBluetoothMusicAudioFocus();
        unregisterVendorHeadsetReceiver();
        unregisterLegacyMediaButtonReceiver();
        if (mBluetoothMediaSession != null && mHost != null) {
            try {
                mBluetoothMediaSession.setMediaButtonReceiver(null);
            } catch (Exception ignored) {
            }
            try {
                mBluetoothMediaSession.setActive(false);
            } catch (Exception ignored) {
            }
            mBluetoothMediaSession.release();
            mBluetoothMediaSession = null;
            Log.d(TAG, "Bluetooth PTT: MediaSession released");
        } else if (mBluetoothMediaSession != null) {
            try {
                mBluetoothMediaSession.release();
            } catch (Exception ignored) {
            }
            mBluetoothMediaSession = null;
        }
    }

    private static final String LOG_MEDIA_BTN = "HyTalkPTT-MediaBtn";

    private boolean handleBluetoothMediaButtonIntent(Intent intent) {
        BluetoothHeadsetProbeLog.logMediaButtonIntent(intent);
        KeyEvent event = MediaButtonKeyEventParser.fromIntent(intent);
        if (event == null) {
            event = BareMediaButtonPtt.nextSyntheticKeyEvent(mApp);
            if (event != null) {
                BluetoothHeadsetProbeLog.logKeyEvent("MediaSession-callback-bare", event);
                Log.i(LOG_MEDIA_BTN, "MediaSession bare MEDIA_BUTTON -> synthetic (receiver-equivalent)");
            }
        } else {
            BluetoothHeadsetProbeLog.logKeyEvent("MediaSession-callback", event);
            Log.i(LOG_MEDIA_BTN, "MediaSession callback: " + KeyEvent.keyCodeToString(event.getKeyCode())
                    + " action=" + event.getAction());
        }
        if (PttKeySetupActivity.isSetupScreenVisible()) {
            if (event != null) {
                PttKeySetupActivity.onMediaButtonKey(mApp, event);
                return true;
            }
            return false;
        }
        if (mHost == null || !PttPreferences.isPttBluetoothSourceEnabled(mHost)) {
            return false;
        }
        if (event == null) {
            return false;
        }
        if (!isBluetoothHeadsetPttKeyForPtt(mHost, event.getKeyCode())) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY
                    && !PttPreferences.isBluetoothPttIncludeMediaPlay(mHost)) {
                Log.d(TAG, "MediaSession ignored: MEDIA_PLAY disabled (Configure PTT)");
            } else {
                Log.d(TAG, "MediaSession ignored keyCode=" + event.getKeyCode());
            }
            return false;
        }
        return handleBluetoothMediaPttKeyEvent(event);
    }
}
