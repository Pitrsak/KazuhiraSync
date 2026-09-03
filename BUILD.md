# Kazuhira Sync - Standalone On-Device AI Nutrition Tracker

Kazuhira Sync is a fully local Android app that evaluates food photos using Google Gemini AI Vision and logs nutrition (calories, protein, carbs, fat) directly into **Android Health Connect** and **Samsung Health**.

> [!NOTE]
> **No Raspberry Pi or Tailscale required!** Everything runs directly on your phone.

---

## Usage

1. **Open Kazuhira Sync app** (or share any food photo from Samsung Gallery / Camera directly to Kazuhira Sync).
2. **Capture or Select Photo**:
   - The optical camera feed is continuously active in the background with the authentic MGSV iDroid holographic overlay (grid matrix, scanlines, noise, HUD brackets).
   - Tap ⚡ **CAPTURE TARGET SCAN** to immediately analyze the target from the live camera stream.
   - Tap 📁 **LOAD INTEL FILE** to select an existing photo from Gallery.
3. **Review & Confirm**: The app uses Gemini AI to estimate meal name, calories, protein, carbs, and fat. Adjust any numbers if needed.
4. **Tap "LOG MEAL"**: The meal is saved locally and written straight to **Health Connect** / **Samsung Health**.

---

## Configuration

- Tap ⚙️ **Settings** in the top right corner to customize your Google AI / OpenRouter API key or select your model (defaults to `gemini-3.8-flash`).

---

## Installation

### Pre-built APK
1. Download **`KazuhiraSync.apk`** from the latest GitHub Release:
   https://github.com/Pitrsak/KazuhiraSync/releases/latest
2. Open and install on your phone.
3. Grant Health Connect & Camera permissions when prompted.

---

## Build from Source (Android Studio)
1. Open this repository folder in Android Studio.
2. Allow Gradle to sync dependencies.
3. Select **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**.
4. Output APK will be at: `app/build/outputs/apk/debug/app-debug.apk`.

*Kazuhira Sync v2.0.0 - Local AI Edition*