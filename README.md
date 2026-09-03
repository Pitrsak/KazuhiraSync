# Kazuhira Sync 🛰️
### Tactical On-Device AI Nutrition Tracker // iDroid OS

[![Android CI](https://github.com/Pitrsak/KazuhiraSync/actions/workflows/android.yml/badge.svg)](https://github.com/Pitrsak/KazuhiraSync/actions/workflows/android.yml)
[![Platform](https://img.shields.io/badge/Platform-Android%209.0%2B%20(API%2028%2B)-blue.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg)](https://kotlinlang.org)
[![Health Connect](https://img.shields.io/badge/Health%20Connect-Integrated-brightgreen.svg)](https://developer.android.com/health-and-fitness/guides/health-connect)

> *"Kaz... I'm already a nutritionist."*

**Kazuhira Sync** is a standalone, on-device Android application modeled after the iconic **iDroid** interface from *Metal Gear Solid V: The Phantom Pain*. It uses cutting-edge multimodal Vision AI (Google Gemini & OpenRouter) to evaluate food photos and automatically log macronutrients and calories straight into **Android Health Connect** and **Samsung Health**.

---

## 📸 Key Features

### 🛰️ Authentic MGSV iDroid Holographic HUD
- **Always-On Optical Viewfinder:** Runs a live camera stream continuously behind the interface, mimicking Snake holding up the holographic iDroid projector in the field.
- **Holographic AR Shader Overlay:** Custom hardware-accelerated layer featuring:
  - Matrix dot grid matching the in-game display
  - Procedural digital CRT grain & scanlines
  - Deep holographic blue/cyan vignette and ambient wash
  - Tactical corner HUD brackets (`┌`, `┐`, `└`, `┘`)
- **Authentic Typography:** Armed with **Rajdhani** geometric sans-serif and **Share Tech Mono** tabular digits.
- **Live Tactical HUD:** Bottom-right corner heads-up display tracking real-time clock and daily caloric intake.

### 🧠 Dual Vision AI Providers
- **Google Gemini (Direct):** Fast on-device requests to `gemini-3.8-flash` (latest speed-tier flagship), `gemini-3.5-flash-lite` (ultra-fast & cheap), `gemini-3.5-flash`, or custom models via Google AI Studio.
- **OpenRouter Support:** Use any multimodal model available on OpenRouter (`google/gemini-3.8-flash`, `google/gemini-3.5-flash-lite`, `google/gemini-flash-latest`, `openai/gpt-4o-mini`, `anthropic/claude-3.5-haiku`, `meta-llama/llama-3.2-11b-vision-instruct`, etc.).
- **Zero Hardcoded Secrets:** Bring your own API key; keys are stored exclusively in your device's private storage.

### ⚡ Seamless Nutrition Workflow
1. **Target Acquisition:** Point the camera at your meal and tap `⚡ CAPTURE TARGET SCAN` to snap a photo directly from the live feed, or tap `📁 LOAD INTEL FILE` to import an existing photo from Gallery.
2. **AI Estimation:** The AI analyzes portion sizes, identifies dishes, and estimates Calories (kcal), Protein (g), Carbohydrates (g), and Fat (g).
3. **Intel Verification:** Review, adjust quantities or meal name if desired.
4. **Direct Health Sync:** One tap writes the nutrition record straight to **Android Health Connect**, propagating immediately to **Samsung Health**, Google Fit, and other connected trackers.

---

## ⚙️ Configuration

1. Launch **Kazuhira Sync**.
2. Tap the ⚙️ **Settings** icon in the top right corner.
3. Select your preferred **AI Provider**:
   - **Google Gemini (Direct):** Get a free API key from [Google AI Studio](https://aistudio.google.com/app/apikey).
   - **OpenRouter:** Get an API key from [OpenRouter](https://openrouter.ai/keys).
4. Paste your API key and choose your vision model preset (or enter a custom model identifier).
5. Tap **SAVE CONFIG**.

---

## 📥 Download & Quick Install

### Direct Download (No building required)
1. Navigate to **[Releases](https://github.com/Pitrsak/KazuhiraSync/releases/latest)** on your Android device.
2. Download **`KazuhiraSync.apk`** from the latest release assets.
3. Open the downloaded file to install (allow "Install unknown apps" if prompted).
4. Launch **Kazuhira Sync**, set up your API key in Settings (⚙️), and grant Health Connect & Camera permissions.

---

## 🛠️ Building from Source (For Developers)

If you wish to compile the application yourself:
1. Clone this repository:
   ```bash
   git clone https://github.com/Pitrsak/KazuhiraSync.git
   ```
2. Open the project in **Android Studio** (Hedgehog or newer recommended).
3. Allow Gradle to synchronize dependencies.
4. Run `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`.
5. Find the compiled APK at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🔒 Permissions & Privacy

Kazuhira Sync requires the following permissions for operational functionality:
- `android.permission.CAMERA`: Drives the always-on holographic AR viewfinder and instant target scan.
- `android.permission.health.READ_NUTRITION` & `WRITE_NUTRITION`: Logs your meals and daily macronutrients into Android Health Connect.
- `android.permission.INTERNET`: Communicates directly with the chosen AI API (Google Gemini or OpenRouter) to evaluate food images.

**No tracking. No ads. No telemetry. No third-party servers.** All requests are sent directly from your device to the API provider you configure.

---

## 📜 License

Distributed under the MIT License. See `LICENSE` for more information.
All Metal Gear Solid aesthetic references and iDroid design homages are inspired by Konami's *Metal Gear Solid V: The Phantom Pain*.
