package ru.chepil.hytalkptt;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;

public class PTTAccessibilityService extends AccessibilityService {

    private static final String TAG = "PTTAccessibilityService";

    /**
     * One-tag timeline for PTT debugging. Filter: {@code adb logcat -s HyTalkPTT-PTTTrace:V HyTalkPTT-MediaBtn:V PTTAccessibilityService:V}
     */
    private static final String TAG_PTT_TRACE = "HyTalkPTT-PTTTrace";

    private static WeakReference<PTTAccessibilityService> sInstanceRef;
    // Device-specific PTT keycodes (for reference only; actual keycode comes from SharedPreferences)
    //private static final int PTT_KEYCODE1 = 228; // Motorola LEX F10
    //private static final int PTT_KEYCODE2 = 520; // Scanner Urovo DT 30
    //private static final int PTT_KEYCODE3 = 521; // Scanner Urovo DT 30
    //private static final int PTT_KEYCODE4 = 522; // Scanner Urovo DT 30
    //private static final int PTT_KEYCODE5 = 381; // Ulefone Armor 26 WT
    //private static final int PTT_KEYCODE6 = 301; // Ulefone Armor 20 WT
    //private static final int PTT_KEYCODE7 = 131; // Ulefone Armor 18T
    private static final int REMAPPED_PTT_KEYCODE = 142; // Keycode that HyTalk expects (F12)
    
    // InputManager for key remapping on newer Android versions
    private Object inputManager = null;
    private Method injectInputEventMethod = null;
    
    // HyTalk package names
    private static final String[] POSSIBLE_PACKAGE_NAMES = {
            "com.hytera.ocean",
            "com.hytalkpro.ocean"
    };

    /** Routes Bluetooth / AVRCP media keys when accessibility does not see them as {@link #onKeyEvent}. */
    private MediaSession mBluetoothMediaSession;

    /** Legacy media-button target used with {@link AudioManager#registerMediaButtonEventReceiver(ComponentName)}. */
    private ComponentName mMediaButtonReceiverComponent;

    /** {@link BluetoothHeadset} adds this category for Bluetooth vendor id 0x55 (decimal 85). */
    private static final String CATEGORY_HEADSET_COMPANY_85 =
            "android.bluetooth.headset.intent.category.companyid.85";

    /** {@link Context#RECEIVER_EXPORTED} (API 33); literal keeps compileSdk 22. */
    private static final int RECEIVER_EXPORTED_FLAG = 2;

    /** Match {@code sendBroadcast} used by the Bluetooth stack for secured headset intents. */
    private static final String ANDROID_PERMISSION_BLUETOOTH = "android.permission.BLUETOOTH";

    private BroadcastReceiver mVendorHeadsetReceiver;
    private boolean mVendorHeadsetRegistered;
    /** Logs uncategorized {@link BluetoothHeadset#ACTION_VENDOR_SPECIFIC_HEADSET_EVENT} (HD2 / other OEMs). */
    private BroadcastReceiver mVendorProbeLooseReceiver;
    private boolean mVendorProbeLooseRegistered;

    /** Drops duplicate DOWN from MediaSession + {@link BluetoothMediaButtonReceiver} within this window. */
    private long mLastPttDownHandledTimeMs;

    /**
     * HyTalk treats an isolated PTT_DOWN as a short tap; resend PTT_DOWN while Bluetooth PTT is held
     * until PTT_UP (HFP vendor TALK,0 or AVRCP/media key release). Not too fast: each tick used to
     * resolve the HyTalk package on the main thread (now cached) and floods HyTalk; ~400ms keeps
     * keepalive-style refresh without hammering HyTalk.
     */
    private static final long BLUETOOTH_PTT_DOWN_REPEAT_MS = 400L;

    private Handler mBluetoothPttHoldRepeatHandler;
    private final Runnable mBluetoothPttHoldRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            sendPTTBroadcast(true);
            if (mBluetoothPttHoldRepeatHandler != null) {
                mBluetoothPttHoldRepeatHandler.postDelayed(this, BLUETOOTH_PTT_DOWN_REPEAT_MS);
            }
        }
    };

    /**
     * Set when the 150ms duplicate-DOWN debounce skipped {@link #sendPTTBroadcast(boolean)} — Bluetooth hold
     * repeat must not start without an initial PTT_DOWN.
     */
    private boolean mBluetoothPttDebounceSkippedFirstDown;

    /**
     * When {@link PttPreferences#isBluetoothPttMediaToggleLatch} is true: first tap starts TX, second tap ends it
     * (no periodic {@code PTT_DOWN} while latched — only normal hold-to-talk uses that).
     */
    private boolean mBluetoothPttMediaLatched;
    private long mLastBluetoothToggleDownWallMs;
    /** {@link SystemClock#uptimeMillis} when toggle latched ON — ignore OFF if a late duplicate MEDIA_BUTTON arrives too soon. */
    private long mBluetoothToggleOnUptimeMs;
    /**
     * Reject toggle-OFF if within this many ms of toggle-ON (late duplicate MEDIA_BUTTON ~200–350ms after first).
     */
    private static final long BLUETOOTH_TOGGLE_MIN_HOLD_BEFORE_OFF_MS = 310L;

    /**
     * While HyTalk is foreground it often registers a {@link MediaSession} and receives headset hook /
     * {@code MEDIA_BUTTON} instead of us. Re-run {@link #updateBluetoothMediaSession()} periodically
     * only in tap-to-toggle TX-on state to win routing back without resuming DOWN keepalive.
     */
    private static final long BT_TOGGLE_ROUTING_RECLAIM_FIRST_MS = 400L;
    private static final long BT_TOGGLE_ROUTING_RECLAIM_PERIOD_MS = 2800L;

    private Runnable mBtToggleRoutingReclaimRunnable;

    /** Cached target for explicit {@link #sendPTTBroadcast}; avoid {@link #findHyTalkApp()} every keepalive tick. */
    private String mCachedHyTalkBroadcastPackage;

    /**
     * Exclusive music-stream focus so the system routes headset play/pause to our {@link MediaSession}
     * instead of the default Music app (common on Huawei when we never held focus).
     */
    private AudioManager.OnAudioFocusChangeListener mBluetoothMusicFocusListener;
    private boolean mBluetoothMusicFocusHeld;

    private final SharedPreferences.OnSharedPreferenceChangeListener mPttPrefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                    if (PttPreferences.PREF_KEY_PTT_SOURCE_BLUETOOTH.equals(key)
                            || PttPreferences.PREF_KEY_PTT_BT_INCLUDE_MEDIA_PLAY.equals(key)
                            || PttPreferences.PREF_KEY_PTT_BT_MEDIA_TOGGLE_LATCH.equals(key)) {
                        if (PttPreferences.PREF_KEY_PTT_SOURCE_BLUETOOTH.equals(key)
                                && !sharedPreferences.getBoolean(PttPreferences.PREF_KEY_PTT_SOURCE_BLUETOOTH, false)) {
                            clearBluetoothMediaToggleLatchIfTransmitting();
                        }
                        if (PttPreferences.PREF_KEY_PTT_BT_MEDIA_TOGGLE_LATCH.equals(key)
                                && !sharedPreferences.getBoolean(PttPreferences.PREF_KEY_PTT_BT_MEDIA_TOGGLE_LATCH, false)) {
                            clearBluetoothMediaToggleLatchIfTransmitting();
                        }
                        updateBluetoothMediaSession();
                    }
                }
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
        sInstanceRef = new WeakReference<PTTAccessibilityService>(this);
        BareMediaButtonPtt.resetState();
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

        PttPreferences.prefs(this).registerOnSharedPreferenceChangeListener(mPttPrefsListener);
        updateBluetoothMediaSession();
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
    
    /**
     * Headset / AVRCP codes we may show on the key-learning screen or forward to setup activity.
     */
    private static boolean isBluetoothHeadsetMediaKey(int keyCode) {
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

    /**
     * Same as {@link #isBluetoothHeadsetMediaKey} except {@link KeyEvent#KEYCODE_MEDIA_PLAY} can be
     * turned off in preferences (power / hook often sends PLAY; dedicated PTT may not).
     */
    private static boolean isBluetoothHeadsetPttKeyForPtt(Context ctx, int keyCode) {
        if (!isBluetoothHeadsetMediaKey(keyCode)) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                && !PttPreferences.isBluetoothPttIncludeMediaPlay(ctx)) {
            return false;
        }
        return true;
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
        return bluetooth && isBluetoothHeadsetPttKeyForPtt(this, keyCode);
    }

    /**
     * While {@link PttKeySetupActivity} is open, Bluetooth keys must be shown there — not handled as PTT.
     * Unregisters legacy receiver and deactivates our {@link MediaSession} so the activity can take over.
     * Vendor HFP receiver stays registered; {@link #dispatchVendorHeadsetIntent} ignores PTT while this screen is visible.
     */
    public static void pauseBluetoothMediaForKeyLearning() {
        PTTAccessibilityService s = sInstanceRef != null ? sInstanceRef.get() : null;
        if (s != null) {
            s.pauseBluetoothMediaForKeyLearningInternal();
        }
    }

    /** Restores Bluetooth PTT capture after leaving the key setup screen. */
    public static void resumeBluetoothMediaForKeyLearning() {
        PTTAccessibilityService s = sInstanceRef != null ? sInstanceRef.get() : null;
        if (s != null) {
            s.resumeBluetoothMediaForKeyLearningInternal();
        }
    }

    /**
     * Re-attach media button receiver, {@link MediaSession}, and STREAM_MUSIC focus after another app
     * (e.g. system Music) may have taken headset key routing. Call from {@link MainActivity#onResume}
     * when Bluetooth PTT is enabled — one-shot per resume, not a periodic timer.
     */
    public static void refreshBluetoothMediaRoutingFromUi(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!PttPreferences.isPttBluetoothSourceEnabled(app)) {
            return;
        }
        if (PttKeySetupActivity.isSetupScreenVisible()) {
            return;
        }
        PTTAccessibilityService s = sInstanceRef != null ? sInstanceRef.get() : null;
        if (s == null) {
            return;
        }
        s.updateBluetoothMediaSession();
        Log.d(TAG, "Bluetooth PTT: refreshed from UI (e.g. after Music / another media app)");
    }

    private void pauseBluetoothMediaForKeyLearningInternal() {
        cancelBluetoothToggleRoutingReclaim();
        abandonBluetoothMusicAudioFocus();
        unregisterLegacyMediaButtonReceiver();
        if (mBluetoothMediaSession != null) {
            try {
                mBluetoothMediaSession.setMediaButtonReceiver(null);
            } catch (Exception ignored) {
                // ignore
            }
            try {
                mBluetoothMediaSession.setActive(false);
            } catch (Exception ignored) {
                // ignore
            }
        }
        Log.d(TAG, "Bluetooth PTT: paused for key learning screen");
    }

    private void resumeBluetoothMediaForKeyLearningInternal() {
        updateBluetoothMediaSession();
        Log.d(TAG, "Bluetooth PTT: resumed after key learning screen");
    }

    private void updateBluetoothMediaSession() {
        if (!PttPreferences.isPttBluetoothSourceEnabled(this)) {
            releaseBluetoothMediaSession();
            Log.w(TAG, "Bluetooth PTT: OFF in Configure PTT (not saved as enabled) — no MEDIA_BUTTON "
                    + "registration on this screen; enable Bluetooth checkbox, Save, then test again");
            return;
        }
        if (PttKeySetupActivity.isSetupScreenVisible()) {
            // MediaSession is owned by PttKeySetupActivity while learning; vendor PTT is suppressed in dispatch until leave.
            registerVendorHeadsetReceiver();
            return;
        }
        if (mBluetoothMediaSession == null) {
            try {
                mBluetoothMediaSession = new MediaSession(this, "HyTalkPTT");
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
                releaseBluetoothMediaSession();
            }
            return;
        }
        attachBluetoothMediaButtonPendingIntent();
        PttMediaSessionMetadata.applyPttPlaceholder(mBluetoothMediaSession);
        try {
            mBluetoothMediaSession.setActive(true);
        } catch (Exception ignored) {
            // ignore
        }
        registerLegacyMediaButtonReceiver();
        requestBluetoothMusicAudioFocus();
        registerVendorHeadsetReceiver();
        Log.d(TAG, "Bluetooth PTT: MediaSession refreshed (after pause or pref change)");
    }

    private void requestBluetoothMusicAudioFocus() {
        if (!PttPreferences.isPttBluetoothSourceEnabled(this)) {
            return;
        }
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
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
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.abandonAudioFocus(mBluetoothMusicFocusListener);
        }
        mBluetoothMusicFocusHeld = false;
    }

    /**
     * Delivers a resolved headset / media key (from {@link BluetoothMediaButtonReceiver} or parser).
     */
    public static void dispatchBluetoothMediaKey(Context context, KeyEvent event) {
        if (context == null || event == null) {
            return;
        }
        if (PttKeySetupActivity.isSetupScreenVisible()) {
            PttKeySetupActivity.onMediaButtonKey(context.getApplicationContext(), event);
            return;
        }
        if (!PttPreferences.isPttBluetoothSourceEnabled(context.getApplicationContext())) {
            Log.w("HyTalkPTT-MediaBtn", "ignored: Bluetooth PTT source disabled in Configure PTT — enable + Save");
            return;
        }
        PTTAccessibilityService svc = sInstanceRef != null ? sInstanceRef.get() : null;
        if (svc == null) {
            Log.w(TAG, "Bluetooth key: accessibility service not running");
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
        svc.handleBluetoothMediaPttKeyEvent(event);
    }

    /**
     * HFP vendor frames ({@code VENDOR_SPECIFIC_HEADSET_EVENT}) for company id 85 — registered in
     * {@link #registerVendorHeadsetReceiver()} with category {@link #CATEGORY_HEADSET_COMPANY_85}.
     */
    public static void dispatchVendorHeadsetIntent(Context context, Intent intent) {
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
            return;
        }
        PTTAccessibilityService svc = sInstanceRef != null ? sInstanceRef.get() : null;
        if (svc == null) {
            return;
        }
        if (!PttPreferences.isPttBluetoothSourceEnabled(svc)) {
            return;
        }
        svc.deliverVendorHeadsetPtt(parsed.booleanValue());
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

    /**
     * After HyTalk {@link #startActivity}, AVRCP often moves to HyTalk until we refresh session + focus.
     * Runs on main while {@link #mBluetoothPttMediaLatched} and tap-to-toggle preference are on.
     */
    private void scheduleBluetoothToggleRoutingReclaimLoop() {
        cancelBluetoothToggleRoutingReclaim();
        if (!PttPreferences.isBluetoothPttMediaToggleLatch(this)
                || !PttPreferences.isPttBluetoothSourceEnabled(this)) {
            return;
        }
        ensureBluetoothPttHoldRepeatHandler();
        mBtToggleRoutingReclaimRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mBluetoothPttMediaLatched
                        || !PttPreferences.isPttBluetoothSourceEnabled(PTTAccessibilityService.this)
                        || PttKeySetupActivity.isSetupScreenVisible()) {
                    mBtToggleRoutingReclaimRunnable = null;
                    return;
                }
                updateBluetoothMediaSession();
                pttTrace("reclaim AVRCP routing (toggle TX on; HyTalk may steal MEDIA_BUTTON)");
                Log.d(TAG, "Bluetooth PTT: reclaim routing (toggle TX — refresh session + focus)");
                if (mBluetoothPttHoldRepeatHandler != null && mBtToggleRoutingReclaimRunnable != null) {
                    mBluetoothPttHoldRepeatHandler.postDelayed(this, BT_TOGGLE_ROUTING_RECLAIM_PERIOD_MS);
                }
            }
        };
        mBluetoothPttHoldRepeatHandler.postDelayed(mBtToggleRoutingReclaimRunnable, BT_TOGGLE_ROUTING_RECLAIM_FIRST_MS);
    }

    /** Retransmit PTT_DOWN periodically; first DOWN is already sent by {@link #handlePttKeyEvent}. */
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

    private void deliverVendorHeadsetPtt(boolean isDown) {
        if (isDown) {
            KeyEvent ev = new KeyEvent(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN,
                    REMAPPED_PTT_KEYCODE,
                    0);
            handlePttKeyEvent(ev, false);
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
            handlePttKeyEvent(ev, false);
        }
    }

    /**
     * Bluetooth AVRCP / media-button path: same hold refresh as {@link #deliverVendorHeadsetPtt(boolean)}.
     */
    private boolean handleBluetoothMediaPttKeyEvent(KeyEvent event) {
        if (PttPreferences.isBluetoothPttMediaToggleLatch(this)) {
            return handleBluetoothMediaToggleLatch(event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            stopBluetoothPttHoldRepeat();
        }
        boolean handled = handlePttKeyEvent(event, false);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && !mBluetoothPttDebounceSkippedFirstDown) {
            startBluetoothPttHoldRepeat();
        }
        return handled;
    }

    /**
     * Headsets such as Retevis / Ailunce HD2 send PLAY/PAUSE as an immediate DOWN+UP; Android never delivers a
     * long press. Toggle mode: ignore UP, each DOWN flips TX on/off with a single synthetic DOWN/UP — no
     * {@link #startBluetoothPttHoldRepeat()} (keepalive DOWN would flood HyTalk and breaks first PTT_UP).
     */
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
        mLastPttDownHandledTimeMs = 0L;

        mBluetoothPttMediaLatched = !mBluetoothPttMediaLatched;
        if (mBluetoothPttMediaLatched) {
            pttTrace("BT-toggle -> TX ON (synthetic DOWN, no DOWN keepalive)");
            mBluetoothToggleOnUptimeMs = now;
            KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, REMAPPED_PTT_KEYCODE, 0);
            handlePttKeyEvent(ev, false);
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
            handlePttKeyEvent(ev, false);
        }
        return true;
    }

    private void clearBluetoothMediaToggleLatchIfTransmitting() {
        if (!mBluetoothPttMediaLatched) {
            return;
        }
        mBluetoothPttMediaLatched = false;
        mBluetoothToggleOnUptimeMs = 0L;
        stopBluetoothPttHoldRepeat();
        cancelBluetoothToggleRoutingReclaim();
        mLastPttDownHandledTimeMs = 0L;
        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_UP, REMAPPED_PTT_KEYCODE, 0);
        handlePttKeyEvent(ev, false);
    }

    @SuppressWarnings("deprecation")
    private void registerLegacyMediaButtonReceiver() {
        if (mMediaButtonReceiverComponent != null) {
            return;
        }
        mMediaButtonReceiverComponent = new ComponentName(this, BluetoothMediaButtonReceiver.class);
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.registerMediaButtonEventReceiver(mMediaButtonReceiverComponent);
            Log.d(TAG, "Bluetooth PTT: registerMediaButtonEventReceiver");
        }
    }

    @SuppressWarnings("deprecation")
    private void unregisterLegacyMediaButtonReceiver() {
        if (mMediaButtonReceiverComponent == null) {
            return;
        }
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
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
        if (mBluetoothMediaSession == null) {
            return;
        }
        try {
            mBluetoothMediaSession.setMediaButtonReceiver(
                    MediaButtonHelper.mediaButtonPendingIntent(this, BluetoothMediaButtonReceiver.class));
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
                PTTAccessibilityService.dispatchVendorHeadsetIntent(context, intent);
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
                m.invoke(this, mVendorProbeLooseReceiver, loose, ANDROID_PERMISSION_BLUETOOTH, null,
                        Integer.valueOf(RECEIVER_EXPORTED_FLAG));
                mVendorProbeLooseRegistered = true;
            } else if (Build.VERSION.SDK_INT <= 23) {
                registerReceiver(mVendorProbeLooseReceiver, loose);
                mVendorProbeLooseRegistered = true;
            } else {
                registerReceiver(mVendorProbeLooseReceiver, loose, ANDROID_PERMISSION_BLUETOOTH, null);
                mVendorProbeLooseRegistered = true;
            }
            Log.d(TAG, "Bluetooth PTT: BtProbe VENDOR action-only registered (uncategorized vendor AT)");
        } catch (Exception e) {
            Log.w(TAG, "Bluetooth PTT: BtProbe loose VENDOR register failed", e);
        }
    }

    private void unregisterVendorProbeLooseReceiver() {
        if (!mVendorProbeLooseRegistered || mVendorProbeLooseReceiver == null) {
            mVendorProbeLooseRegistered = false;
            return;
        }
        try {
            unregisterReceiver(mVendorProbeLooseReceiver);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        mVendorProbeLooseRegistered = false;
    }

    private void registerVendorHeadsetReceiver() {
        ensureVendorHeadsetReceiverCreated();
        IntentFilter filter = new IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
        // Required: system intent includes category companyid.85; a filter with no categories only matches no-category intents.
        filter.addCategory(CATEGORY_HEADSET_COMPANY_85);
        boolean registeredCategoryFilter = false;
        try {
            if (mVendorHeadsetRegistered) {
                try {
                    unregisterReceiver(mVendorHeadsetReceiver);
                } catch (IllegalArgumentException ignored) {
                    // not registered
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
                m.invoke(this, mVendorHeadsetReceiver, filter, ANDROID_PERMISSION_BLUETOOTH, null,
                        Integer.valueOf(RECEIVER_EXPORTED_FLAG));
                mVendorHeadsetRegistered = true;
                registeredCategoryFilter = true;
                Log.d(TAG, "Bluetooth PTT: VENDOR_SPECIFIC registered (API 33+ category .85 + permission)");
            } else if (Build.VERSION.SDK_INT <= 23) {
                registerReceiver(mVendorHeadsetReceiver, filter);
                mVendorHeadsetRegistered = true;
                registeredCategoryFilter = true;
                Log.d(TAG, "Bluetooth PTT: VENDOR_SPECIFIC registered (API≤23 category .85, 2-arg)");
            } else {
                registerReceiver(mVendorHeadsetReceiver, filter, ANDROID_PERMISSION_BLUETOOTH, null);
                mVendorHeadsetRegistered = true;
                registeredCategoryFilter = true;
                Log.d(TAG, "Bluetooth PTT: VENDOR_SPECIFIC registered (category .85 + permission string)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Bluetooth PTT: registerReceiver vendor failed, trying action-only filter", e);
            try {
                IntentFilter loose = new IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
                registerReceiver(mVendorHeadsetReceiver, loose);
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
        if (!mVendorHeadsetRegistered || mVendorHeadsetReceiver == null) {
            mVendorHeadsetRegistered = false;
            return;
        }
        try {
            unregisterReceiver(mVendorHeadsetReceiver);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        mVendorHeadsetRegistered = false;
        Log.d(TAG, "Bluetooth PTT: VENDOR_SPECIFIC_HEADSET_EVENT unregistered");
    }

    private void releaseBluetoothMediaSession() {
        clearBluetoothMediaToggleLatchIfTransmitting();
        stopBluetoothPttHoldRepeat();
        abandonBluetoothMusicAudioFocus();
        unregisterVendorHeadsetReceiver();
        unregisterLegacyMediaButtonReceiver();
        if (mBluetoothMediaSession != null) {
            try {
                mBluetoothMediaSession.setMediaButtonReceiver(null);
            } catch (Exception ignored) {
                // ignore
            }
            try {
                mBluetoothMediaSession.setActive(false);
            } catch (Exception ignored) {
                // ignore
            }
            mBluetoothMediaSession.release();
            mBluetoothMediaSession = null;
            Log.d(TAG, "Bluetooth PTT: MediaSession released");
        }
    }

    private static final String LOG_MEDIA_BTN = "HyTalkPTT-MediaBtn";

    private boolean handleBluetoothMediaButtonIntent(Intent intent) {
        BluetoothHeadsetProbeLog.logMediaButtonIntent(intent);
        KeyEvent event = MediaButtonKeyEventParser.fromIntent(intent);
        if (event == null) {
            // Must match {@link BluetoothMediaButtonReceiver}: when the stack delivers MEDIA_BUTTON to
            // MediaSession without EXTRA_KEY_EVENT, bare intents were dropped here while the receiver
            // synthesized HEADSETHOOK — HyTalk in foreground often routes keys only through Session.
            event = BareMediaButtonPtt.nextSyntheticKeyEvent(getApplicationContext());
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
                PttKeySetupActivity.onMediaButtonKey(getApplicationContext(), event);
                return true;
            }
            return false;
        }
        if (!PttPreferences.isPttBluetoothSourceEnabled(this)) {
            return false;
        }
        if (event == null) {
            return false;
        }
        if (!isBluetoothHeadsetPttKeyForPtt(this, event.getKeyCode())) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY
                    && !PttPreferences.isBluetoothPttIncludeMediaPlay(this)) {
                Log.d(TAG, "MediaSession ignored: MEDIA_PLAY disabled (Configure PTT)");
            } else {
                Log.d(TAG, "MediaSession ignored keyCode=" + event.getKeyCode());
            }
            return false;
        }
        return handleBluetoothMediaPttKeyEvent(event);
    }

    /**
     * Handles DOWN/UP after the key is already accepted as PTT (hardware or Bluetooth).
     *
     * @param hardwareKeyRepeatSendsPttDown if true, OS key repeats while the side key is held also send
     *                                      {@code PTT_DOWN} (HyTalk keepalive). Bluetooth paths pass false and use
     *                                      {@link #startBluetoothPttHoldRepeat()} instead to avoid double sends.
     */
    private boolean handlePttKeyEvent(KeyEvent event, boolean hardwareKeyRepeatSendsPttDown) {
        if (!hardwareKeyRepeatSendsPttDown) {
            mBluetoothPttDebounceSkippedFirstDown = false;
        }
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                long now = System.currentTimeMillis();
                if (now - mLastPttDownHandledTimeMs < 150) {
                    pttTrace("DOWN debounced (<150ms dup path) keyCode=" + keyCode + " hwPath=" + hardwareKeyRepeatSendsPttDown);
                    Log.d(TAG, "PTT down debounced (duplicate BT path), keyCode=" + keyCode);
                    if (!hardwareKeyRepeatSendsPttDown) {
                        mBluetoothPttDebounceSkippedFirstDown = true;
                    }
                    return true;
                }
                mLastPttDownHandledTimeMs = now;
                pttTrace("DOWN -> launch+broadcast keyCode=" + keyCode + " hwPath=" + hardwareKeyRepeatSendsPttDown);
                Log.d(TAG, "PTT pressed, keyCode=" + keyCode);
                MainActivity.isPTTButtonPressed = true;
                launchHyTalkIfNeeded();
                sendPTTBroadcast(true);
            } else if (hardwareKeyRepeatSendsPttDown) {
                pttTrace("DOWN repeat keyCode=" + keyCode + " repeat=" + event.getRepeatCount());
                Log.d(TAG, "PTT pressed (repeat), keyCode=" + keyCode + " repeat=" + event.getRepeatCount());
                sendPTTBroadcast(true);
            }
            return true;
        }
        if (action == KeyEvent.ACTION_UP) {
            pttTrace("UP -> broadcast keyCode=" + keyCode + " hwPath=" + hardwareKeyRepeatSendsPttDown);
            Log.d(TAG, "PTT released, keyCode=" + keyCode);
            MainActivity.isPTTButtonPressed = false;
            sendPTTBroadcast(false);
            return true;
        }
        return false;
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

        if (PttKeySetupActivity.isSetupScreenVisible() && isBluetoothHeadsetMediaKey(keyCode)) {
            PttKeySetupActivity.onMediaButtonKey(getApplicationContext(), event);
            return true;
        }

        if (PttPreferences.isPttBluetoothSourceEnabled(this) && isBluetoothHeadsetMediaKey(keyCode)) {
            BluetoothHeadsetProbeLog.logKeyEvent("Accessibility-onKeyEvent", event);
        }

        if (PttPreferences.isBluetoothPttMediaToggleLatch(this)
                && PttPreferences.isPttBluetoothSourceEnabled(this)
                && isBluetoothHeadsetPttKeyForPtt(this, keyCode)) {
            pttTrace("onKeyEvent BT-toggle key=" + keyCode + " act=" + event.getAction() + " rep=" + event.getRepeatCount());
            return handleBluetoothMediaToggleLatch(event);
        }

        if (!isConfiguredPttKey(keyCode)) {
            return false;
        }

        pttTrace("onKeyEvent PTT key=" + keyCode + " act=" + event.getAction() + " rep=" + event.getRepeatCount());
        return handlePttKeyEvent(event, true);
    }
    
    /**
     * Launches HyTalk app directly or brings it to foreground if already running.
     * This is called when PTT button is pressed to ensure HyTalk is active.
     */
    private void launchHyTalkIfNeeded() {
        try {
            Intent launchIntent = findHyTalkApp();
            if (launchIntent != null) {
                if (launchIntent.getComponent() != null) {
                    mCachedHyTalkBroadcastPackage = launchIntent.getComponent().getPackageName();
                }
                // Add flags to bring app to foreground or launch if not running
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

                startActivity(launchIntent);
                pttTrace("startActivity ok pkg=" + mCachedHyTalkBroadcastPackage);
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
        
        String selfPackage = getPackageName().toLowerCase();
        for (ResolveInfo info : apps) {
            String packageName = info.activityInfo.packageName.toLowerCase();
            if (packageName.equals(selfPackage)) {
                continue; // Exclude ourselves (ru.chepil.hytalkptt contains "hytalk")
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
    
    /**
     * Sends Broadcast Intent for HyTalk PTT button.
     * Based on pttremap app logic: sends "android.intent.action.PTT_DOWN" or "PTT_UP"
     */
    private void sendPTTBroadcast(boolean isDown) {
        try {
            String action = isDown ? "android.intent.action.PTT_DOWN" : "android.intent.action.PTT_UP";
            Intent intent = new Intent(action);
            String pkg = resolveHyTalkPackageNameForBroadcast();
            if (pkg != null) {
                intent.setPackage(pkg);
            }
            sendBroadcast(intent);
            pttTrace("broadcast " + action + (pkg != null ? (" pkg=" + pkg) : " implicit"));
            Log.d(TAG, "Sent PTT Broadcast Intent: " + action + (pkg != null ? (" package=" + pkg) : " (no HyTalk package; implicit)"));
        } catch (Exception e) {
            pttTrace("broadcast FAILED " + (isDown ? "PTT_DOWN" : "PTT_UP") + " " + e.getMessage());
            Log.e(TAG, "Error sending PTT Broadcast Intent", e);
        }
    }

    /** Prefer explicit package so HyTalk receives the intent on OEM builds that restrict implicit broadcasts. */
    private String resolveHyTalkPackageNameForBroadcast() {
        if (mCachedHyTalkBroadcastPackage != null) {
            return mCachedHyTalkBroadcastPackage;
        }
        Intent launch = findHyTalkApp();
        if (launch != null && launch.getComponent() != null) {
            mCachedHyTalkBroadcastPackage = launch.getComponent().getPackageName();
            return mCachedHyTalkBroadcastPackage;
        }
        PackageManager pm = getPackageManager();
        for (String packageName : POSSIBLE_PACKAGE_NAMES) {
            try {
                pm.getApplicationInfo(packageName, 0);
                mCachedHyTalkBroadcastPackage = packageName;
                return mCachedHyTalkBroadcastPackage;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }
    
    @Override
    public void onDestroy() {
        clearBluetoothMediaToggleLatchIfTransmitting();
        stopBluetoothPttHoldRepeat();
        cancelBluetoothToggleRoutingReclaim();
        mCachedHyTalkBroadcastPackage = null;
        PttPreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(mPttPrefsListener);
        releaseBluetoothMediaSession();
        sInstanceRef = null;
        super.onDestroy();
        Log.d(TAG, "PTT Accessibility Service destroyed");
    }
}
