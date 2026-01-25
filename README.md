# HyTalkPTT

HyTalkPTT is an Android application designed for Motorola LEX F10 devices that enables the physical PTT (Push-To-Talk) button to activate the HyTalk application.

## Overview

This app intercepts PTT button presses and converts them into broadcast intents that HyTalk recognizes. It uses an Accessibility Service to capture global key events and sends the appropriate `android.intent.action.PTT_DOWN` and `android.intent.action.PTT_UP` broadcast intents to activate PTT functionality in HyTalk.

## Requirements

- **Device**: Motorola LEX F10
- **Android Version**: Android 5.1.1 (API Level 22)
- **Target Application**: HyTalk (com.hytera.ocean)
- **Android Studio**: Latest version with Android SDK

## Features

- Intercepts physical PTT button presses (keycode 228)
- Converts PTT events to broadcast intents for HyTalk
- Automatically launches HyTalk when PTT button is pressed
- Works even when the app is in the background or killed
- Minimal resource usage

## Setup Instructions

Before using this app, you need to configure two system settings:

### 1. Programmable Keys

1. Go to **Settings → Programmable Keys**
2. Select **PTT Key app**
3. Choose **HyTalkPTT**

This allows the app to receive PTT button events when the device is locked or the app is in the background.

### 2. Accessibility Service

1. Go to **Settings → Accessibility**
2. Find **HyTalkPTT** in the list
3. Enable the toggle switch

This grants the app permission to intercept key events globally.

## Building the Project

### Prerequisites

- Android Studio (latest version)
- Android SDK with API Level 22
- JDK 8 or later

### Build Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/chepil/HyTalkPTT.git
   cd HyTalkPTT
   ```

2. Open the project in Android Studio:
   - File → Open → Select the project directory

3. Sync Gradle files (Android Studio will prompt you, or click "Sync Now")

4. Build the project:
   - Build → Make Project (Ctrl+F9 / Cmd+F9)
   - Or use: `./gradlew assembleDebug`

5. Install on device:
   - Connect your Motorola LEX F10 device via USB
   - Enable USB debugging on the device
   - Run → Run 'app' (Shift+F10 / Ctrl+R)

Alternatively, build and install using command line:
```bash
./gradlew installDebug
```

## How It Works

1. **Physical PTT Button Press**: When you press the physical PTT button on the Motorola LEX F10, it generates a key event with keycode 228.

2. **Accessibility Service Interception**: The `PTTAccessibilityService` intercepts this key event globally (even when the app is in the background).

3. **Broadcast Intent Conversion**: The service converts the key event into a broadcast intent:
   - `ACTION_DOWN` → `android.intent.action.PTT_DOWN`
   - `ACTION_UP` → `android.intent.action.PTT_UP`

4. **HyTalk Activation**: HyTalk listens for these broadcast intents and activates its PTT functionality accordingly.

5. **App Launch**: If HyTalk is not running, the app automatically launches it when PTT is pressed.

## Technical Details

- **Min SDK Version**: 22 (Android 5.1.1)
- **Target SDK Version**: 22 (Android 5.1.1)
- **Compile SDK Version**: 22
- **Package Name**: `ru.chepil.hytalkptt`
- **Key Components**:
  - `MainActivity`: Handles app lifecycle and HyTalk launch logic
  - `PTTAccessibilityService`: Intercepts PTT button events and sends broadcast intents

## Permissions

The app requires the following permissions:

- `BIND_ACCESSIBILITY_SERVICE`: Required for the accessibility service to intercept key events
- `SYSTEM_ALERT_WINDOW`: (Optional, not currently used)

## Troubleshooting

### PTT button doesn't work

1. Verify that **Programmable Keys** is configured (Settings → Programmable Keys → Select PTT Key app → HyTalkPTT)
2. Verify that **Accessibility Service** is enabled (Settings → Accessibility → HyTalkPTT → Enable)
3. Check logcat for error messages:
   ```bash
   adb logcat | grep -i "HyTalkPTT\|PTTAccessibilityService"
   ```

### HyTalk doesn't launch

1. Verify that HyTalk app (com.hytera.ocean) is installed on the device
2. Check logcat for launch errors
3. Verify that the app has permission to launch other applications

### Broadcast intents not received by HyTalk

- This is expected behavior - HyTalk handles the broadcast intents internally
- Verify that PTT functionality works in HyTalk when pressing the physical button
- Check logcat to confirm broadcast intents are being sent

## License

The MIT License

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Author

[Denis Chepil/den@chepil.ru]

### Tested with:

Motorola LEX F10, 
UROVO DT30, 
Ulefone models 26WT, 20WT, 13
