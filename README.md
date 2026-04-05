# HyTalkPTT

HyTalkPTT is an Android application that enables a configurable physical PTT (Push-To-Talk) button to activate the HyTalk application. It works with Motorola LEX F10, UROVO DT30, Ulefone, and other devices whose PTT keycode you configure in the app. **External Bluetooth PTT microphones** are supported as well — for example **Inrico BT02** and similar headsets that deliver PTT over Bluetooth (HFP vendor / AVRCP / media keys, depending on the device).

## Overview

The app intercepts PTT button presses and converts them into broadcast intents that HyTalk recognizes. It uses an **Accessibility Service** to capture global key events and sends `android.intent.action.PTT_DOWN` and `android.intent.action.PTT_UP` broadcasts. The PTT keycode is stored in app preferences (default **228** for Motorola LEX F10) and can be changed via **Configure PTT Key** in the app.

**Setup order matters:** enable **Accessibility** for HyTalkPTT first (otherwise key events are not delivered to the app), then **Configure PTT Key**, then optional **Configure Programmable Keys (for Motorola)** on supported devices.

## Requirements

- **Devices**: Motorola LEX F10 (default keycode 228), UROVO DT30, Ulefone (26WT, 20WT, 18T, etc.), or any device with a configurable PTT keycode; **external Bluetooth PTT mics** (e.g. Inrico BT02 and similar) when the phone exposes their PTT to apps (see below)
- **Android**: Android 5.1.1 (API 22)
- **Target app**: HyTalk (`com.hytera.ocean` / `com.hytalkpro.ocean` where applicable)
- **Android Studio**: Latest with Android SDK

## Features

- **Accessibility-based capture**: Global PTT handling requires HyTalkPTT to be enabled under **Settings → Accessibility** before keys are intercepted.
- **Configurable PTT keycode**: Set via **Configure PTT Key** (press your hardware PTT → Save). Default 228 (Motorola LEX F10).
- Intercepts only the configured PTT key and converts it to broadcast intents for HyTalk
- **Bluetooth PTT** (optional): external mics and headsets — HFP hook, AVRCP media keys, and **HFP vendor-specific** events (used by devices such as **Inrico BT02**). Enable **Bluetooth headset / microphone PTT** in **Configure PTT Key** and tap **Save settings**.
- Automatically launches or brings HyTalk to foreground when PTT is pressed
- Works when the app is in the background or was killed
- Minimal resource usage

## Setup Instructions

Configure these steps **in this order**:

### 1. Accessibility Service (do this first)

1. Open **HyTalkPTT** from the launcher.
2. Tap **Configure Accessibility** (first button on the main screen).
3. In **Settings → Accessibility**, find **HyTalkPTT** and **enable** the service.

Without this step, the app cannot receive global PTT key events.

### 2. Set PTT Key code

1. Return to **HyTalkPTT** and tap **Configure PTT Key**.
2. Press your device’s physical PTT button (or configure Bluetooth PTT sources as needed).
3. Tap **Save settings**.

The app stores the keycode (e.g. 228 for LEX F10, 520–522 for UROVO DT30, 381/301/131 for Ulefone). You can change it anytime by repeating these steps.

### 3. Configure Programmable Keys (for Motorola)

1. In **HyTalkPTT**, tap **Configure Programmable Keys (for Motorola)** (opens system Settings).
2. Go to **Settings → Programmable Keys** (path may vary by device).
3. Set **PTT Key app** (or equivalent) to **HyTalkPTT**.

This helps the device deliver PTT events when locked or in the background (where supported).

## External Bluetooth PTT microphones (e.g. Inrico BT02)

Support has been added for **external Bluetooth PTT microphones** that work like a dedicated radio-style PTT button over Bluetooth. Examples include **Inrico BT02** and **similar** models from Inrico or other vendors, provided the Android Bluetooth stack forwards their events to apps.

- In **Configure PTT Key**, enable **Bluetooth headset / microphone PTT** and **Save settings** (until you save, Bluetooth capture is not active).
- Some radios (e.g. **Retevis / Ailunce HD2** over Bluetooth) send **PLAY/PAUSE** as a **very short pulse** (DOWN+UP in a few milliseconds), so HyTalk only sees a “click”, not a hold. Enable **Tap to toggle transmit** in Configure PTT Key: **first tap** = start PTT (with repeated `PTT_DOWN` keepalive), **second tap** = stop. Leave it off for normal press-to-talk headsets that send a real key hold.
- Depending on the mic, PTT may arrive as **AVRCP** (play/pause), **HFP hook**, or **vendor-specific HFP** frames (e.g. `+XEVENT` / `TALK` press and release). The app maps these to the same `PTT_DOWN` / `PTT_UP` broadcasts as the hardware key.
- If nothing appears when you press PTT, the device may use a **proprietary protocol** that Android does not expose — in that case use the phone’s own programmable PTT key or another mic model.

**Debug / proximity on HD2 (and similar):** the app logs Bluetooth probe lines with tag **`HyTalkPTT-BtProbe`** (vendor HFP `VENDOR_SPECIFIC`, `MEDIA_BUTTON` extras, and `KeyEvent` from MediaSession vs accessibility). Capture while touching the sensor:

```bash
adb logcat -v time 'HyTalkPTT-BtProbe:I' 'PTTAccessibilityService:D' '*:S'
```

(Quote `*:S` in zsh.) Compare finger-on vs finger-off lines; vendor AT commands may use a different company id than 85 — the **action-only** vendor receiver logs those without the category filter.

If you **hear a short tone in the earpiece** when touching a **proximity sensor** but **no `HyTalkPTT-BtProbe` lines** appear, the headset is almost certainly handling the sensor **inside its own firmware** (local audio feedback only). Android apps then receive **no** separate key or vendor event for that gesture — HyTalkPTT cannot map it unless the manufacturer exposes a documented Bluetooth command stream.

## Building the Project

### Prerequisites

- Android Studio (latest)
- Android SDK API 22
- JDK 8+

### Build steps

1. Clone the repo:
   ```bash
   git clone https://github.com/chepil/HyTalkPTT.git
   cd HyTalkPTT
   ```

2. Open the project in Android Studio and sync Gradle.

3. Build and install:
   ```bash
   ./gradlew installDebug
   ```
   Or: **Build → Make Project**, then **Run → Run 'app'** with the device connected via USB (USB debugging enabled).

## How It Works

1. **Accessibility**: `PTTAccessibilityService` must be enabled so the system delivers filtered key events to the app.

2. **Configured PTT keycode**: The app uses a single keycode from **SharedPreferences** (default **228**). You set it in **Configure PTT Key**.

3. **Key event interception**: `PTTAccessibilityService` receives global key events and reacts when the key matches your configuration (hardware and/or Bluetooth, per preferences).

4. **Broadcast intents**:  
   - `ACTION_DOWN` → `android.intent.action.PTT_DOWN`  
   - `ACTION_UP` → `android.intent.action.PTT_UP`

5. **HyTalk**: Listens for these broadcasts and activates PTT.

6. **Launch**: If HyTalk is not running, the app launches it (or brings it to foreground) when PTT is pressed.

## Technical Details

- **Min/Target/Compile SDK**: 22 (Android 5.1.1)
- **Package**: `ru.chepil.hytalkptt`
- **Components**:
  - **MainActivity**: App entry, setup UI (Accessibility, PTT Key, Configure Programmable Keys (for Motorola)), HyTalk launch.
  - **PttKeySetupActivity**: “Configure PTT Key” — detect hardware key, Bluetooth options, save to preferences.
  - **PttPreferences**: Stores PTT keycode in `SharedPreferences` (default 228).
  - **PTTAccessibilityService**: Intercepts configured PTT sources and sends PTT broadcasts.

## Permissions

- **BIND_ACCESSIBILITY_SERVICE**: For intercepting key events.
- **BLUETOOTH**: Declared so some OEM Bluetooth stacks deliver headset-related broadcasts.
- **SYSTEM_ALERT_WINDOW**: Optional, not used currently.

## Troubleshooting

### PTT button has no effect

1. Enable **Accessibility** for HyTalkPTT (**Configure Accessibility** on the main screen).
2. Set **Configure PTT Key** (press PTT, enable Bluetooth PTT if needed, then Save).
3. On Motorola-type devices, use **Configure Programmable Keys (for Motorola)** and set **PTT Key app** → HyTalkPTT.
4. Check logcat:
   ```bash
   adb logcat | grep -i "HyTalkPTT\|PTTAccessibilityService"
   ```

### HyTalk doesn’t launch

1. Install HyTalk (`com.hytera.ocean` or your build’s package).
2. Check logcat for launch errors.

### Wrong key detected

- Open **Configure PTT Key**, press your PTT, confirm the shown keycode, then **Save settings**.  
- On some devices, PTT may use different keycodes (e.g. 520, 521, 522 for UROVO DT30).

### UI: checkboxes hard to see (dark theme)

- The PTT setup screen uses a dark background; checkboxes use an explicit light tint so they stay visible. If anything is still unclear, use system **Display** settings or increase brightness.

## License

The MIT License

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Author

Denis Chepil — den@chepil.ru

## Tested with

- Motorola LEX F10 (default keycode 228)
- UROVO DT30 (keycodes 520, 521, 522)
- Ulefone Armor 26 WT, 20 WT, 18T (e.g. 381, 301, 131)
- **Inrico BT02** (Bluetooth PTT via HFP vendor / `+XEVENT`); other similar Bluetooth PTT mics may work if the OS delivers comparable events
