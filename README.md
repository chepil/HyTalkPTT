# HyTalkPTT

HyTalkPTT is an Android application that enables a configurable physical PTT (Push-To-Talk) button to activate the HyTalk application. It works with Motorola LEX F10, UROVO DT30, Ulefone, and other devices whose PTT keycode you configure in the app.

## Overview

The app intercepts PTT button presses and converts them into broadcast intents that HyTalk recognizes. It uses an Accessibility Service to capture global key events and sends `android.intent.action.PTT_DOWN` and `android.intent.action.PTT_UP` broadcasts. The PTT keycode is stored in app preferences (default **228** for Motorola LEX F10) and can be changed via **Configure PTT Key** in the app.

## Requirements

- **Devices**: Motorola LEX F10 (default keycode 228), UROVO DT30, Ulefone (26WT, 20WT, 18T, etc.), or any device with a configurable PTT keycode
- **Android**: Android 5.1.1 (API 22)
- **Target app**: HyTalk (`com.hytera.ocean`)
- **Android Studio**: Latest with Android SDK

## Features

- **Configurable PTT keycode**: Set via **Configure PTT Key** (press your hardware PTT → Save). Default 228 (Motorola LEX F10).
- Intercepts only the configured PTT key and converts it to broadcast intents for HyTalk
- Automatically launches or brings HyTalk to foreground when PTT is pressed
- Works when the app is in the background or was killed
- Minimal resource usage

## Setup Instructions

Configure these **three** steps (in order):

### 1. Set PTT Key code

1. Open **HyTalkPTT** from the launcher.
2. Tap **Configure PTT Key**.
3. Press your device’s physical PTT button.
4. Tap **Save settings**.

The app stores the keycode (e.g. 228 for LEX F10, 520–522 for UROVO DT30, 381/301/131 for Ulefone). You can change it anytime by repeating these steps.

### 2. Programmable Keys

1. Go to **Settings → Programmable Keys**
2. Set **PTT Key app** (or equivalent) to **HyTalkPTT**

This lets the app receive PTT events when the device is locked or the app is in the background.

### 3. Accessibility Service

1. Go to **Settings → Accessibility**
2. Find **HyTalkPTT** and enable it

This allows global interception of the PTT key.

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

1. **Configured PTT keycode**: The app uses a single keycode from **SharedPreferences** (default **228**). You set it in **Configure PTT Key**.

2. **Key event interception**: `PTTAccessibilityService` receives global key events and reacts only when `keyCode` matches the stored value.

3. **Broadcast intents**:  
   - `ACTION_DOWN` → `android.intent.action.PTT_DOWN`  
   - `ACTION_UP` → `android.intent.action.PTT_UP`

4. **HyTalk**: Listens for these broadcasts and activates PTT.

5. **Launch**: If HyTalk is not running, the app launches it (or brings it to foreground) when PTT is pressed.

## Technical Details

- **Min/Target/Compile SDK**: 22 (Android 5.1.1)
- **Package**: `ru.chepil.hytalkptt`
- **Components**:
  - **MainActivity**: App entry, setup UI, HyTalk launch.
  - **PttKeySetupActivity**: “Configure PTT Key” — detect hardware key, show keycode, save to preferences.
  - **PttPreferences**: Stores PTT keycode in `SharedPreferences` (default 228).
  - **PTTAccessibilityService**: Intercepts only the configured keycode and sends PTT broadcasts.

## Permissions

- **BIND_ACCESSIBILITY_SERVICE**: For intercepting key events.
- **SYSTEM_ALERT_WINDOW**: Optional, not used currently.

## Troubleshooting

### PTT button has no effect

1. Set **Configure PTT Key** (press PTT, then Save).
2. Configure **Programmable Keys** (PTT Key app → HyTalkPTT).
3. Enable **Accessibility** for HyTalkPTT.
4. Check logcat:
   ```bash
   adb logcat | grep -i "HyTalkPTT\|PTTAccessibilityService"
   ```

### HyTalk doesn’t launch

1. Install HyTalk (`com.hytera.ocean`).
2. Check logcat for launch errors.

### Wrong key detected

- Open **Configure PTT Key**, press your PTT, confirm the shown keycode, then **Save settings**.  
- On some devices, PTT may use different keycodes (e.g. 520, 521, 522 for UROVO DT30).

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
