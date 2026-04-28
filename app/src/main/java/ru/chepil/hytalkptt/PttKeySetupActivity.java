package ru.chepil.hytalkptt;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
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
import java.lang.reflect.Method;

/**
 * Activity for detecting and displaying the PTT hardware key code.
 * Stores keyCode on ACTION_DOWN; "Save settings" saves it to app sandbox.
 * Bluetooth / headset keys are delivered via {@link MediaSession} and {@link Intent#ACTION_MEDIA_BUTTON},
 * not {@link #dispatchKeyEvent}, so we register those while this screen is visible.
 * SPP (Inrico-style) and HFP vendor PTT are still received in the background and appended here as text lines
 * (HyTalk broadcasts are not sent while this screen is visible).
 */
public class PttKeySetupActivity extends AppCompatActivity {

    private static final String TAG = "PttKeySetupActivity";

    private static final int REQ_BT_CONNECT = 5101;
    private static final int REQ_BLE_PERMS = 5103;
    private static final String PERM_BT_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    private static final String PERM_BT_SCAN = "android.permission.BLUETOOTH_SCAN";
    private static final int ANDROID_12_API = 31;

    private static WeakReference<PttKeySetupActivity> sInstanceRef;

    private TextView tvInstruction;
    private TextView tvKeyCode;
    private CheckBox cbHardware;
    private CheckBox cbBluetooth;
    private CheckBox cbBtIncludeMediaPlay;
    private CheckBox cbBtMediaToggleLatch;
    private CheckBox cbBluetoothSpp;
    private CheckBox cbBlePtt;
    private Button btnBlePttSearch;
    private TextView tvBlePttHint;
    private TextView tvBlePermissionStatus;
    private TextView tvBluetoothHint;
    private TextView tvSppHint;
    /** While visible: captures BT / AVRCP keys for the on-screen readout. */
    private MediaSession mSetupMediaSession;
    private ComponentName mSetupMediaReceiverComponent;

    private AudioManager.OnAudioFocusChangeListener mSetupAudioFocusListener;
    private boolean mSetupHasAudioFocus;
    private boolean mBleHostAttachedBySetupUi;

    /** Last keyCode from ACTION_DOWN (repeatCount == 0). -1 if none yet. */
    private int lastKeyCode = -1;

    /** True while this activity is resumed (learning screen is showing). */
    static boolean isSetupScreenVisible() {
        return sInstanceRef != null && sInstanceRef.get() != null;
    }

    static boolean isBleCheckboxCheckedInSetup() {
        PttKeySetupActivity a = sInstanceRef != null ? sInstanceRef.get() : null;
        return a != null && a.cbBlePtt != null && a.cbBlePtt.isChecked();
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

    /** SPP Inrico {@code +PTT=P} / {@code +PTT=R} while this activity is resumed. */
    static void onSppPttForUi(final boolean pressed) {
        final PttKeySetupActivity a = sInstanceRef != null ? sInstanceRef.get() : null;
        if (a == null) {
            return;
        }
        final int res = pressed ? R.string.ptt_learn_spp_press : R.string.ptt_learn_spp_release;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                a.appendLearnLine(a.getString(res));
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            a.runOnUiThread(r);
        }
    }

    /** HFP vendor-specific PTT (category 85) while this activity is resumed. */
    static void onBluetoothVendorPttForUi(final boolean pressed) {
        final PttKeySetupActivity a = sInstanceRef != null ? sInstanceRef.get() : null;
        if (a == null) {
            return;
        }
        final int res = pressed ? R.string.ptt_learn_vendor_press : R.string.ptt_learn_vendor_release;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                a.appendLearnLine(a.getString(res));
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            a.runOnUiThread(r);
        }
    }

    static void onBlePttForUi(final boolean pressed) {
        final PttKeySetupActivity a = sInstanceRef != null ? sInstanceRef.get() : null;
        if (a == null) {
            return;
        }
        final int res = pressed ? R.string.ptt_ble_learn_press : R.string.ptt_ble_learn_release;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                a.appendLearnLine(a.getString(res));
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            a.runOnUiThread(r);
        }
    }

    static void notifyBleScanStarted(final Context appContext) {
        final PttKeySetupActivity a = sInstanceRef != null ? sInstanceRef.get() : null;
        if (a == null) {
            return;
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Toast.makeText(a, a.getString(R.string.ptt_ble_scan_started), Toast.LENGTH_SHORT).show();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            a.runOnUiThread(r);
        }
    }

    static void notifyBleScanFinished(final Context appContext, final boolean deviceFound) {
        final PttKeySetupActivity a = sInstanceRef != null ? sInstanceRef.get() : null;
        if (a == null) {
            return;
        }
        final int res = deviceFound ? R.string.ptt_ble_scan_found : R.string.ptt_ble_scan_timeout;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Toast.makeText(a, a.getString(res), Toast.LENGTH_SHORT).show();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            a.runOnUiThread(r);
        }
    }

    static void notifyBleGattReady(final Context appContext, final String address) {
        final PttKeySetupActivity a = sInstanceRef != null ? sInstanceRef.get() : null;
        if (a == null) {
            return;
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Toast.makeText(a, a.getString(R.string.ptt_ble_connected), Toast.LENGTH_LONG).show();
                if (address != null) {
                    a.appendLearnLine(a.getString(R.string.ptt_ble_connected) + "\n" + address);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            a.runOnUiThread(r);
        }
    }

    static void notifyBleConnectStatus(final Context appContext, final int status, final boolean likelyBusy) {
        final PttKeySetupActivity a = sInstanceRef != null ? sInstanceRef.get() : null;
        if (a == null) {
            return;
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                int res = likelyBusy ? R.string.ptt_ble_device_busy : R.string.ptt_ble_connect_failed;
                Toast.makeText(a, a.getString(res, Integer.valueOf(status)), Toast.LENGTH_LONG).show();
                a.appendLearnLine(a.getString(res, Integer.valueOf(status)));
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            a.runOnUiThread(r);
        }
    }

    static void notifyBleGapMismatch(final Context appContext) {
        final PttKeySetupActivity a = sInstanceRef != null ? sInstanceRef.get() : null;
        if (a == null) {
            return;
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Toast.makeText(a, a.getString(R.string.ptt_ble_gap_mismatch), Toast.LENGTH_LONG).show();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            a.runOnUiThread(r);
        }
    }

    static void notifyBleScanError(final Context appContext) {
        final PttKeySetupActivity a = sInstanceRef != null ? sInstanceRef.get() : null;
        if (a == null) {
            return;
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Toast.makeText(a, a.getString(R.string.ptt_ble_scan_error), Toast.LENGTH_LONG).show();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            a.runOnUiThread(r);
        }
    }

    private void appendLearnLine(String line) {
        if (tvKeyCode == null || line == null) {
            return;
        }
        CharSequence cur = tvKeyCode.getText();
        String s = cur != null ? cur.toString().trim() : "";
        String placeholder = getString(R.string.ptt_key_placeholder);
        if (s.isEmpty() || s.equals(placeholder)) {
            tvKeyCode.setText(line);
        } else {
            tvKeyCode.setText(s + "\n\n" + line);
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
        cbBluetoothSpp = (CheckBox) findViewById(R.id.cb_ptt_bluetooth_spp);
        tvBluetoothHint = (TextView) findViewById(R.id.tv_bluetooth_hint);
        tvSppHint = (TextView) findViewById(R.id.tv_spp_hint);
        cbBlePtt = (CheckBox) findViewById(R.id.cb_ptt_ble_button);
        btnBlePttSearch = (Button) findViewById(R.id.btn_ble_ptt_search);
        tvBlePttHint = (TextView) findViewById(R.id.tv_ble_ptt_hint);
        tvBlePermissionStatus = (TextView) findViewById(R.id.tv_ble_permission_status);
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
        if (cbBluetoothSpp != null) {
            cbBluetoothSpp.setChecked(PttPreferences.isPttBluetoothSppEnabled(this));
        }
        if (cbBlePtt != null) {
            cbBlePtt.setChecked(PttPreferences.isPttBleButtonEnabled(this));
        }
        updateBluetoothHintVisibility();
        updateBleSearchRowVisibility();
        updateBlePermissionStatus();

        CompoundButton.OnCheckedChangeListener sourceListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                updateBluetoothHintVisibility();
            }
        };
        if (cbBluetooth != null) {
            cbBluetooth.setOnCheckedChangeListener(sourceListener);
        }
        if (cbBluetoothSpp != null) {
            cbBluetoothSpp.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    updateBluetoothHintVisibility();
                    if (isChecked) {
                        requestSppConnectPermissionIfNeeded();
                    }
                }
            });
        }
        if (cbBlePtt != null) {
            cbBlePtt.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    updateBluetoothHintVisibility();
                    updateBleSearchRowVisibility();
                    updateBlePermissionStatus();
                    if (!isChecked) {
                        BlePttZ01Controller.disconnectSessionFromSetupUi();
                    }
                }
            });
        }
        if (btnBlePttSearch != null) {
            btnBlePttSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onBleSearchButtonClicked();
                }
            });
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
                    boolean bluetoothSpp = cbBluetoothSpp != null && cbBluetoothSpp.isChecked();
                    boolean ble = cbBlePtt != null && cbBlePtt.isChecked();
                    if (!hardware && !bluetooth && !bluetoothSpp && !ble) {
                        Toast.makeText(PttKeySetupActivity.this, R.string.ptt_save_need_source, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean includeMediaPlay = cbBtIncludeMediaPlay == null || cbBtIncludeMediaPlay.isChecked();
                    boolean mediaToggleLatch = (bluetooth || ble) && cbBtMediaToggleLatch != null && cbBtMediaToggleLatch.isChecked();
                    String bleAddrCommit = null;
                    if (ble) {
                        String sessionAddr = BlePttZ01Controller.getSessionReadyAddress(PttKeySetupActivity.this);
                        if (sessionAddr != null) {
                            bleAddrCommit = sessionAddr;
                        } else {
                            bleAddrCommit = PttPreferences.getBlePttDeviceAddress(PttKeySetupActivity.this);
                        }
                    }
                    PttPreferences.commitPttConfiguration(
                            PttKeySetupActivity.this, hardware, bluetooth, bluetoothSpp, ble, bleAddrCommit,
                            includeMediaPlay, mediaToggleLatch, lastKeyCode);
                    BluetoothPttCoordinator.syncRouting(PttKeySetupActivity.this);
                    Toast.makeText(PttKeySetupActivity.this, R.string.ptt_saved, Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
        }
    }

    private void updateBluetoothHintVisibility() {
        boolean on = cbBluetooth != null && cbBluetooth.isChecked();
        boolean bleOn = cbBlePtt != null && cbBlePtt.isChecked();
        if (tvBluetoothHint != null) {
            tvBluetoothHint.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (cbBtIncludeMediaPlay != null) {
            cbBtIncludeMediaPlay.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (cbBtMediaToggleLatch != null) {
            cbBtMediaToggleLatch.setVisibility((on || bleOn) ? View.VISIBLE : View.GONE);
        }
        boolean sppOn = cbBluetoothSpp != null && cbBluetoothSpp.isChecked();
        if (tvSppHint != null) {
            tvSppHint.setVisibility(sppOn ? View.VISIBLE : View.GONE);
        }
    }

    private void updateBleSearchRowVisibility() {
        boolean bleOn = cbBlePtt != null && cbBlePtt.isChecked();
        if (tvBlePttHint != null) {
            tvBlePttHint.setVisibility(bleOn ? View.VISIBLE : View.GONE);
        }
        if (btnBlePttSearch != null) {
            btnBlePttSearch.setVisibility(bleOn ? View.VISIBLE : View.GONE);
        }
        if (tvBlePermissionStatus != null) {
            tvBlePermissionStatus.setVisibility(bleOn ? View.VISIBLE : View.GONE);
        }
    }

    private void onBleSearchButtonClicked() {
        updateBlePermissionStatus();
        if (Build.VERSION.SDK_INT >= 23 && !BlePttZ01Controller.hasBleScanAndConnect(this)) {
            requestBleScanPermissionsIfNeeded();
            return;
        }
        BlePttZ01Controller.startScanFromSetupActivity(this);
    }

    private void requestBleScanPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            Method m = android.app.Activity.class.getMethod("requestPermissions", String[].class, int.class);
            String[] perms;
            if (Build.VERSION.SDK_INT >= ANDROID_12_API) {
                perms = new String[] {
                        PERM_BT_SCAN,
                        PERM_BT_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                };
            } else {
                perms = new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                };
            }
            m.invoke(this, new Object[] { perms, Integer.valueOf(REQ_BLE_PERMS) });
        } catch (Exception e) {
            Log.w(TAG, "requestPermissions(BLE scan) failed", e);
        }
    }

    private void requestSppConnectPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < ANDROID_12_API) {
            return;
        }
        if (isRuntimePermissionGranted(PERM_BT_CONNECT)) {
            return;
        }
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            Method m = android.app.Activity.class.getMethod("requestPermissions", String[].class, int.class);
            m.invoke(this, new Object[] { new String[] { PERM_BT_CONNECT }, Integer.valueOf(REQ_BT_CONNECT) });
        } catch (Exception e) {
            Log.w(TAG, "requestPermissions(BLUETOOTH_CONNECT) failed", e);
        }
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_BLE_PERMS) {
            boolean allOk;
            if (Build.VERSION.SDK_INT >= ANDROID_12_API) {
                boolean scan = isRuntimePermissionGranted(PERM_BT_SCAN);
                boolean connect = isRuntimePermissionGranted(PERM_BT_CONNECT);
                boolean fine = isRuntimePermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION);
                boolean coarse = isRuntimePermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION);
                allOk = scan && connect && (fine || coarse);
            } else {
                boolean fine = isRuntimePermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION);
                boolean coarse = isRuntimePermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION);
                allOk = fine || coarse;
            }
            if (allOk) {
                BlePttZ01Controller.startScanFromSetupActivity(this);
            } else {
                Toast.makeText(this, R.string.ptt_ble_scan_error, Toast.LENGTH_LONG).show();
            }
            updateBlePermissionStatus();
            return;
        }
        if (requestCode != REQ_BT_CONNECT) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            BluetoothPttCoordinator.syncRouting(this);
        } else {
            Toast.makeText(this, R.string.ptt_spp_need_connect_permission, Toast.LENGTH_LONG).show();
        }
        updateBlePermissionStatus();
    }

    private boolean isRuntimePermissionGranted(String permission) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        try {
            Method m = android.content.Context.class.getMethod("checkSelfPermission", String.class);
            Object r = m.invoke(this, permission);
            return r instanceof Integer && ((Integer) r).intValue() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return getPackageManager().checkPermission(permission, getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void updateBlePermissionStatus() {
        if (tvBlePermissionStatus == null) {
            return;
        }
        if (cbBlePtt == null || !cbBlePtt.isChecked()) {
            tvBlePermissionStatus.setVisibility(View.GONE);
            return;
        }
        tvBlePermissionStatus.setVisibility(View.VISIBLE);
        int textRes;
        int color;
        if (Build.VERSION.SDK_INT >= ANDROID_12_API) {
            boolean scan = isRuntimePermissionGranted(PERM_BT_SCAN);
            boolean connect = isRuntimePermissionGranted(PERM_BT_CONNECT);
            boolean fine = isRuntimePermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION);
            boolean coarse = isRuntimePermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (!scan || !connect) {
                textRes = R.string.ptt_ble_permission_status_need_nearby;
                color = 0xFFFFAA66;
            } else if (!fine && !coarse) {
                textRes = R.string.ptt_ble_permission_status_warn_location;
                color = 0xFFFFAA66;
            } else {
                textRes = R.string.ptt_ble_permission_status_ok;
                color = 0xFF66CC99;
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            boolean fine = isRuntimePermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION);
            boolean coarse = isRuntimePermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (fine || coarse) {
                textRes = R.string.ptt_ble_permission_status_ok;
                color = 0xFF66CC99;
            } else {
                textRes = R.string.ptt_ble_permission_status_need_location;
                color = 0xFFFFAA66;
            }
        } else {
            textRes = R.string.ptt_ble_permission_status_ok;
            color = 0xFF66CC99;
        }
        tvBlePermissionStatus.setText(textRes);
        tvBlePermissionStatus.setTextColor(color);
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
        if (BlePttZ01Controller.needsHostForSetupUi()) {
            BlePttZ01Controller.attachHost(this);
            mBleHostAttachedBySetupUi = true;
        } else {
            mBleHostAttachedBySetupUi = false;
        }
        refreshSetupInstructionBanner();
        updateBlePermissionStatus();
        PTTAccessibilityService.pauseBluetoothMediaForKeyLearning();
        if (cbBluetoothSpp != null && cbBluetoothSpp.isChecked()) {
            requestSppConnectPermissionIfNeeded();
        }
        startBluetoothKeyCapture();
        if (tvKeyCode != null) {
            tvKeyCode.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        stopBluetoothKeyCapture();
        if (mBleHostAttachedBySetupUi) {
            BlePttZ01Controller.detachHost(this);
            mBleHostAttachedBySetupUi = false;
        }
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
            Toast.makeText(this, R.string.ptt_back_exiting, Toast.LENGTH_SHORT).show();
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
        String actionLabel;
        switch (e.getAction()) {
            case KeyEvent.ACTION_DOWN:
                actionLabel = getString(R.string.ptt_key_action_down);
                break;
            case KeyEvent.ACTION_UP:
                actionLabel = getString(R.string.ptt_key_action_up);
                break;
            default:
                actionLabel = String.valueOf(e.getAction());
        }

        String keyName = KeyEvent.keyCodeToString(e.getKeyCode());

        return getString(R.string.ptt_key_event_format,
                actionLabel,
                e.getKeyCode(),
                keyName,
                e.getScanCode());
    }
}
