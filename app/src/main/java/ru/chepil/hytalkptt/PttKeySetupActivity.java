package ru.chepil.hytalkptt;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * Activity for detecting and displaying the PTT hardware key code.
 * Stores keyCode on ACTION_DOWN; "Save settings" saves it to app sandbox.
 * Bluetooth / headset keys are delivered via {@link MediaSession} and {@link Intent#ACTION_MEDIA_BUTTON},
 * not {@link #dispatchKeyEvent}, so we register those while this screen is visible.
 */
public class PttKeySetupActivity extends AppCompatActivity {

    private static final String TAG = "PttKeySetupActivity";

    private static WeakReference<PttKeySetupActivity> sInstanceRef;

    private TextView tvInstruction;
    private TextView tvKeyCode;
    private CheckBox cbHardware;
    private CheckBox cbBluetooth;
    private CheckBox cbBtIncludeMediaPlay;
    private CheckBox cbBtMediaToggleLatch;
    private TextView tvBluetoothHint;
    /** While visible: captures BT / AVRCP keys for the on-screen readout. */
    private MediaSession mSetupMediaSession;
    private ComponentName mSetupMediaReceiverComponent;

    private AudioManager.OnAudioFocusChangeListener mSetupAudioFocusListener;
    private boolean mSetupHasAudioFocus;

    /** Last keyCode from ACTION_DOWN (repeatCount == 0). -1 if none yet. */
    private int lastKeyCode = -1;

    /** True while this activity is resumed (learning screen is showing). */
    static boolean isSetupScreenVisible() {
        return sInstanceRef != null && sInstanceRef.get() != null;
    }

    static void onMediaButtonKey(Context appContext, final KeyEvent event) {
        final PttKeySetupActivity a = sInstanceRef != null ? sInstanceRef.get() : null;
        if (a == null) {
            Log.w("HyTalkPTT-MediaBtn", "onMediaButtonKey: setup activity not in foreground, drop key");
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            a.recordKeyEventForUi(event);
        } else {
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    a.recordKeyEventForUi(event);
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ptt_key_setup);

        tvInstruction = (TextView) findViewById(R.id.tv_instruction);
        cbHardware = (CheckBox) findViewById(R.id.cb_ptt_hardware);
        cbBluetooth = (CheckBox) findViewById(R.id.cb_ptt_bluetooth);
        cbBtIncludeMediaPlay = (CheckBox) findViewById(R.id.cb_bt_include_media_play);
        cbBtMediaToggleLatch = (CheckBox) findViewById(R.id.cb_bt_media_toggle_latch);
        tvBluetoothHint = (TextView) findViewById(R.id.tv_bluetooth_hint);
        if (cbHardware != null) {
            cbHardware.setChecked(PttPreferences.isPttHardwareSourceEnabled(this));
        }
        if (cbBluetooth != null) {
            cbBluetooth.setChecked(PttPreferences.isPttBluetoothSourceEnabled(this));
        }
        if (cbBtIncludeMediaPlay != null) {
            cbBtIncludeMediaPlay.setChecked(PttPreferences.isBluetoothPttIncludeMediaPlay(this));
        }
        if (cbBtMediaToggleLatch != null) {
            cbBtMediaToggleLatch.setChecked(PttPreferences.isBluetoothPttMediaToggleLatch(this));
        }
        updateBluetoothHintVisibility();

        CompoundButton.OnCheckedChangeListener sourceListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                updateBluetoothHintVisibility();
            }
        };
        if (cbBluetooth != null) {
            cbBluetooth.setOnCheckedChangeListener(sourceListener);
        }

        tvKeyCode = (TextView) findViewById(R.id.tv_key_code);
        if (tvKeyCode != null) {
            tvKeyCode.setFocusable(true);
            tvKeyCode.setFocusableInTouchMode(true);
            tvKeyCode.requestFocus();
        }

        Button btnSave = (Button) findViewById(R.id.btn_save_settings);
        if (btnSave != null) {
            btnSave.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean hardware = cbHardware != null && cbHardware.isChecked();
                    boolean bluetooth = cbBluetooth != null && cbBluetooth.isChecked();
                    if (!hardware && !bluetooth) {
                        Toast.makeText(PttKeySetupActivity.this, R.string.ptt_save_need_source, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean includeMediaPlay = cbBtIncludeMediaPlay == null || cbBtIncludeMediaPlay.isChecked();
                    boolean mediaToggleLatch = bluetooth && cbBtMediaToggleLatch != null && cbBtMediaToggleLatch.isChecked();
                    PttPreferences.commitPttConfiguration(
                            PttKeySetupActivity.this, hardware, bluetooth, includeMediaPlay, mediaToggleLatch, lastKeyCode);
                    Toast.makeText(PttKeySetupActivity.this, R.string.ptt_saved, Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
        }
    }

    private void updateBluetoothHintVisibility() {
        boolean on = cbBluetooth != null && cbBluetooth.isChecked();
        if (tvBluetoothHint != null) {
            tvBluetoothHint.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (cbBtIncludeMediaPlay != null) {
            cbBtIncludeMediaPlay.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (cbBtMediaToggleLatch != null) {
            cbBtMediaToggleLatch.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    private void refreshSetupInstructionBanner() {
        if (tvInstruction == null) {
            return;
        }
        String base = getString(R.string.ptt_setup_instruction);
        if (!PttAccessibilityHelper.isHyTalkPttServiceEnabled(this)) {
            tvInstruction.setText(getString(R.string.ptt_setup_warn_accessibility_off) + "\n\n" + base);
            tvInstruction.setTextColor(0xFFFFAA66);
        } else {
            tvInstruction.setText(base);
            tvInstruction.setTextColor(0xFFAAAAAA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sInstanceRef = new WeakReference<PttKeySetupActivity>(this);
        refreshSetupInstructionBanner();
        PTTAccessibilityService.pauseBluetoothMediaForKeyLearning();
        startBluetoothKeyCapture();
        if (tvKeyCode != null) {
            tvKeyCode.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        stopBluetoothKeyCapture();
        // Clear before resuming service MediaSession, else isSetupScreenVisible() stays true during resume.
        if (sInstanceRef != null && sInstanceRef.get() == this) {
            sInstanceRef = null;
        }
        PTTAccessibilityService.resumeBluetoothMediaForKeyLearning();
        super.onPause();
    }

    @SuppressWarnings("deprecation")
    private void startBluetoothKeyCapture() {
        stopBluetoothKeyCapture();
        try {
            requestSetupAudioFocus();

            mSetupMediaSession = new MediaSession(this, "HyTalkPTT-KeySetup");
            mSetupMediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                    KeyEvent ev = MediaButtonKeyEventParser.fromIntent(mediaButtonIntent);
                    if (ev != null) {
                        Log.d(TAG, "Setup MediaSession: " + formatEvent(ev));
                        recordKeyEventForUi(ev);
                        return true;
                    }
                    Log.w("HyTalkPTT-MediaBtn", "Setup MediaSession: MEDIA_BUTTON without KeyEvent — "
                            + "try call/hook button; dedicated PTT may use another channel on this device");
                    return super.onMediaButtonEvent(mediaButtonIntent);
                }
            });
            mSetupMediaSession.setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY
                            | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .build();
            mSetupMediaSession.setPlaybackState(state);
            PttMediaSessionMetadata.applyPttPlaceholder(mSetupMediaSession);

            PendingIntent mediaPi = MediaButtonHelper.mediaButtonPendingIntent(this, BluetoothMediaButtonReceiver.class);
            mSetupMediaSession.setMediaButtonReceiver(mediaPi);
            Log.d(TAG, "Key setup: MediaSession.setMediaButtonReceiver (Huawei / AVRCP path)");

            mSetupMediaSession.setActive(true);
            Log.d(TAG, "Key setup: MediaSession active for Bluetooth / headset keys");

            mSetupMediaReceiverComponent = new ComponentName(this, BluetoothMediaButtonReceiver.class);
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.registerMediaButtonEventReceiver(mSetupMediaReceiverComponent);
                Log.d(TAG, "Key setup: registerMediaButtonEventReceiver");
            }
        } catch (Exception e) {
            Log.e(TAG, "Key setup: failed to start Bluetooth key capture", e);
            stopBluetoothKeyCapture();
        }
    }

    @SuppressWarnings("deprecation")
    private void requestSetupAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        if (mSetupAudioFocusListener == null) {
            mSetupAudioFocusListener = new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    // no-op; keys may still arrive while learning
                }
            };
        }
        int musicGain = am.requestAudioFocus(
                mSetupAudioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        if (musicGain == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mSetupHasAudioFocus = true;
            Log.d(TAG, "Key setup: requestAudioFocus(MUSIC, GAIN) granted — media keys prefer this app over Music");
            return;
        }
        int voice = am.requestAudioFocus(
                mSetupAudioFocusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        if (voice == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mSetupHasAudioFocus = true;
            Log.d(TAG, "Key setup: requestAudioFocus(VOICE_CALL, TRANSIENT) result=" + voice);
            return;
        }
        int musicTr = am.requestAudioFocus(
                mSetupAudioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        mSetupHasAudioFocus = (musicTr == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        Log.d(TAG, "Key setup: MUSIC_GAIN=" + musicGain + " VOICE=" + voice + " MUSIC_TR=" + musicTr);
    }

    @SuppressWarnings("deprecation")
    private void abandonSetupAudioFocus() {
        if (!mSetupHasAudioFocus || mSetupAudioFocusListener == null) {
            return;
        }
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.abandonAudioFocus(mSetupAudioFocusListener);
        }
        mSetupHasAudioFocus = false;
    }

    @SuppressWarnings("deprecation")
    private void stopBluetoothKeyCapture() {
        if (mSetupMediaReceiverComponent != null) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                try {
                    am.unregisterMediaButtonEventReceiver(mSetupMediaReceiverComponent);
                } catch (Exception e) {
                    Log.w(TAG, "unregisterMediaButtonEventReceiver: " + e.getMessage());
                }
            }
            mSetupMediaReceiverComponent = null;
        }
        if (mSetupMediaSession != null) {
            try {
                mSetupMediaSession.setMediaButtonReceiver(null);
            } catch (Exception ignored) {
                // ignore
            }
            try {
                mSetupMediaSession.setActive(false);
            } catch (Exception ignored) {
                // ignore
            }
            mSetupMediaSession.release();
            mSetupMediaSession = null;
            Log.d(TAG, "Key setup: Bluetooth key capture stopped");
        }
        abandonSetupAudioFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            Log.d(TAG, "BACK button pressed (KEYCODE_BACK, code 4) – exiting");
            Toast.makeText(this, "BACK pressed – exiting", Toast.LENGTH_SHORT).show();
            finish();
            return true;
        }

        recordKeyEventForUi(event);
        return false;
    }

    private void recordKeyEventForUi(KeyEvent event) {
        String info = formatEvent(event);
        Log.d(TAG, info);

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            lastKeyCode = event.getKeyCode();
        }

        if (tvKeyCode != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                tvKeyCode.setText(info);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                String downAndUpText = tvKeyCode.getText() + "\n\n" + info;
                tvKeyCode.setText(downAndUpText);
            }
        }
    }

    private String formatEvent(KeyEvent e) {
        String action;
        switch (e.getAction()) {
            case KeyEvent.ACTION_DOWN:
                action = "DOWN";
                break;
            case KeyEvent.ACTION_UP:
                action = "UP";
                break;
            default:
                action = String.valueOf(e.getAction());
        }

        String keyName = KeyEvent.keyCodeToString(e.getKeyCode());

        return action +
                "  keyCode=" + e.getKeyCode() + " (" + keyName + ")" +
                "  scanCode=" + e.getScanCode();
    }
}
