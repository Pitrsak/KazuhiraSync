# Kazuhira Sync
### Tactical AI Nutrition Tracker // iDroid OS

[![Android CI](https://github.com/Pitrsak/KazuhiraSync/actions/workflows/android.yml/badge.svg)](https://github.com/Pitrsak/KazuhiraSync/actions/workflows/android.yml)
[![Platform](https://img.shields.io/badge/Platform-Android%209.0%2B%20(API%2028%2B)-blue.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg)](https://kotlinlang.org)
[![Health Connect](https://img.shields.io/badge/Health%20Connect-Integrated-brightgreen.svg)](https://developer.android.com/health-and-fitness/guides/health-connect)

> *"Kaz... I'm already a nutritionist."*

**Kazuhira Sync** is a client-side Android application modeled after the iconic **iDroid** interface from *Metal Gear Solid V: The Phantom Pain*. It uses multimodal Vision AI APIs (Google Gemini and OpenRouter) to evaluate food images and automatically log macronutrients and calories into **Android Health Connect** and **Samsung Health**.

There is no intermediate backend, proxy server, or account registration required. All API calls are made directly from your device to the configured AI provider using your personal API key, and your meal logs are stored locally on your device.

---

## Key Features

### Authentic MGSV iDroid Holographic Interface
- **Optical Background Feed:** Runs CameraX continuously behind the holographic UI, providing the appearance of an augmented-reality projection over your surroundings.
- **Hardware-Accelerated HUD Shader:**
  - Procedural scanlines and CRT grain
  - Ambient dot matrix grid
  - Cyan-blue holographic tint and vignette calibrated for legibility over food plates
  - Tactical HUD corner brackets
- **Gyroscope Parallax Reactivity:** UI cards and the ration log react smoothly to device orientation via rotation vector / accelerometer sensors, creating depth perspective.
- **MGSV Typography and Palette:** Monochromatic cyan and ice-blue color scheme using the Rajdhani geometric typeface and Share Tech Mono tabular numerals.

### Multimodal Vision AI Providers
- **Google Gemini (Direct):** Direct integration with Google AI Studio supporting models such as `gemini-3.8-flash`, `gemini-3.5-flash-lite`, `gemini-3.5-flash`, `gemini-2.5-flash`, or custom identifiers.
- **OpenRouter Support:** Connect to any multimodal vision model supported on OpenRouter (e.g. `google/gemini-3.8-flash`, `openai/gpt-4o-mini`, `anthropic/claude-3.5-haiku`, `meta-llama/llama-3.2-11b-vision-instruct`).
- **Bring Your Own Key:** API keys are stored solely in Android private app preferences (`SharedPreferences`), encrypted by OS-level sandbox boundaries.

### Nutrition Tracking Workflow
1. **Target Acquisition:** Point the camera at a meal and tap **CAPTURE TARGET SCAN** to take an image from the live feed, or tap **LOAD INTEL FILE** to select an image from the gallery.
2. **AI Estimation:** The vision model analyzes portion sizes, identifies ingredients, and estimates total calories (kcal), protein (g), carbohydrates (g), and fat (g).
3. **Intel Verification:** An inspection dialog allows manual review and adjustments of meal designations, calories, and macros before saving.
4. **Health Connect Sync:** Saves the entry to local history (sorted newest first) and commits a `NutritionRecord` to **Android Health Connect**, automatically syncing with connected services like Samsung Health.

---

## Configuration

1. Launch **Kazuhira Sync**.
2. Tap the **Settings** icon in the upper-right corner.
3. Select your preferred **AI Provider**:
   - **Google Gemini (Direct):** Generate an API key at [Google AI Studio](https://aistudio.google.com/app/apikey).
   - **OpenRouter:** Generate an API key at [OpenRouter](https://openrouter.ai/keys).
4. Paste your key and select a model preset or enter a custom model name.
5. Tap **SAVE CONFIG**.

---

## Download and Installation

### Pre-built APK Releases
1. Go to [Releases](https://github.com/Pitrsak/KazuhiraSync/releases/latest) on your Android device.
2. Download `KazuhiraSync.apk` from the latest release assets.
3. Open the downloaded file to install or update (enable "Install unknown apps" in system settings if prompted).
4. Launch the application and grant the requested Camera and Health Connect permissions.

---

## Building from Source

To build the APK locally:

1. Clone the repository:
   ```bash
   git clone https://github.com/Pitrsak/KazuhiraSync.git
   cd KazuhiraSync
   ```
2. Build the release APK using Gradle:
   ```bash
   ./gradlew assembleRelease
   ```
3. The resulting APK will be located at:
   ```
   app/build/outputs/apk/release/app-release.apk
   ```

---

## Permissions and Privacy

Kazuhira Sync requests the following permissions:
- `android.permission.CAMERA`: Renders the live optical background feed and captures photos for analysis.
- `android.permission.health.READ_NUTRITION` & `WRITE_NUTRITION`: Reads and writes meal records to Android Health Connect.
- `android.permission.INTERNET`: Sends meal images directly to your chosen AI provider (Google Gemini or OpenRouter).

No analytics, ads, tracking libraries, or intermediary servers are included. Communications happen exclusively between your device and the AI endpoints you explicitly configure.

---

## License

Distributed under the MIT License. See `LICENSE` for details.
Aesthetic references and HUD styling are inspired by Konami's *Metal Gear Solid V: The Phantom Pain*.
