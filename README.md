# ESP32 Nav Lite (Minimal ChronosESP32 App)

Minimal Android app built specifically for your `ChronosESP32` OLED display — zero smartwatch bloat!

## Features
- 1-Tap BLE Scan & Connect to `ESP32 Nav`
- Reads Google Maps navigation notifications automatically in the background
- Sends exact `ChronosESP32` `CF_NAV_DATA` packets (distance, direction, road title, ETA)
- Built-in OLED test buttons (Left / Straight / Right / U-Turn / Stop)

## Option A: Get APK without installing Android Studio (Free via GitHub Actions)
1. Create a free account on github.com and create a new Repository (e.g., `esp32-nav-app`).
2. Extract this ZIP and upload all files inside `ESP32NavApp` (including `.github/workflows/build_apk.yml`) to the repository.
3. Open the **Actions** tab on GitHub -> wait ~2 minutes for **Build ESP32 Nav APK** to finish.
4. Click the finished run -> download **ESP32-Nav-APK** from Artifacts -> extract `app-debug.apk` and install on your Android phone!

## Option B: Build with Android Studio
1. Extract this ZIP.
2. Open the `ESP32NavApp` folder in Android Studio.
3. Wait for Gradle Sync -> click **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
