# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build and install debug APK
./gradlew installDebug

# Build release APK (outputs to app/build/outputs/apk/release/)
./gradlew assembleRelease

# Clean build
./gradlew clean

# Check lint issues
./gradlew lint
```

Output APK naming: `HyTalkPTT-v{versionName}-{versionCode}.apk`

## Project Overview

HyTalkPTT intercepts hardware PTT button presses on rugged Android devices and forwards them to the HyTalk app (`com.hytera.ocean`) via broadcast intents (`android.intent.action.PTT_DOWN` / `PTT_UP`).

**SDK Configuration**: Compile SDK 22, Target SDK 36, Min SDK 22 (Android 5.1.1), Java 8

## Architecture

```
Hardware PTT Button Press
         ↓
PTTAccessibilityService.onKeyEvent()  ← Global key interception via Accessibility API
         ↓
    ├─ Broadcast Intent (PTT_DOWN/UP) → HyTalk app
    └─ Launch HyTalk if not running
```

### Key Components

| Class | Purpose |
|-------|---------|
| `PTTAccessibilityService` | Core service - intercepts global key events, filters for configured PTT keycode, sends broadcasts to HyTalk |
| `MainActivity` | Setup UI with buttons for PTT key config, programmable keys settings, accessibility settings |
| `PttKeySetupActivity` | Detects and displays keycode when user presses PTT button, saves to preferences |
| `PttPreferences` | SharedPreferences wrapper for PTT keycode storage (default: 228 for Motorola LEX F10) |

### Inter-Component Communication

- **MainActivity ↔ PTTAccessibilityService**: Static volatile flag `isPTTButtonPressed`
- **App → HyTalk**: Broadcast intents `android.intent.action.PTT_DOWN` / `PTT_UP`
- **Config Storage**: SharedPreferences key `"ptt_keycode"` in `"ru.chepil.hytalkptt.ptt_prefs"`

## Key Technical Details

- Uses `AccessibilityService` with `android:canRequestFilterKeyEvents="true"` for global key capture
- Supports key injection via reflection on Android 6.0+ (InputManager)
- MainActivity uses `android:launchMode="singleTask"`
- No AndroidX - uses legacy support library `appcompat-v7:22.2.1`

## Supported Devices & Keycodes

- Motorola LEX F10: 228 (default)
- UROVO DT30: 520, 521, 522
- Ulefone (26WT, 20WT, 18T): 381, 301, 131

## Compatibility Protection

This project has multiple safeguards to ensure builds work on all supported devices (especially Motorola LEX F10 on Android 5.1.1):

### Automated Protections

| Layer | What It Does |
|-------|--------------|
| **Dependabot ignore rules** | Blocks upgrades to `appcompat-v7` and major AGP versions |
| **Lint `NewApi` check** | Fails build if code uses APIs above minSdkVersion |
| **CI APK validation** | Verifies minSdkVersion=22 in built APK |
| **Dependency locking** | Prevents transitive dependency drift |

### Baseline Reference

See `.github/compatibility-baseline.yml` for documented v1.4 compatibility requirements.

### After Dependency Changes

```bash
# Regenerate dependency lock files
./gradlew dependencies --write-locks

# Verify build still works
./gradlew clean assembleRelease

# Run lint to check for API compatibility issues
./gradlew lint
```

### DO NOT Change Without Hardware Testing

- `compileSdkVersion` (must stay 22)
- `minSdkVersion` (must stay 22)
- `appcompat-v7` version (must match compileSdkVersion)

## Debug Logging

```bash
adb logcat | grep -i "HyTalkPTT\|PTTAccessibilityService"
```
