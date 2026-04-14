# Kazuhira Sync - Android App

## Quick Build Instructions

### Option 1: GitHub Actions (Easiest)
1. Push this folder to a GitHub repository
2. Go to Actions → Android CI → Run workflow
3. Download the APK artifact

### Option 2: Android Studio
1. Install Android Studio on any PC/Mac
2. File → Open → Select this folder
3. Wait for Gradle sync
4. Build → Build Bundle(s) / APK(s) → Build APK(s)
5. Find APK at: `app/build/outputs/apk/debug/app-debug.apk`

### Option 3: Command Line (if you have Android SDK)
```bash
cd KazuhiraSync
./gradlew assembleDebug
```

## Installation

1. Copy APK to your S23
2. Enable "Install from unknown sources" in Settings
3. Install APK
4. Grant Health Connect permissions when prompted

## Usage

1. **Start Tailscale** on your S23
2. **Open Kazuhira Sync app**
3. **Tap "SYNC NOW"**
4. **Check Samsung Health** → Food section

## Configuration

If your Tailscale IP changes, edit:
- `app/src/main/java/com/kazuhira/hcsync/MainActivity.kt`
- Change `HCGATEWAY_URL` to your Pequod's Tailscale IP

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "Login failed" | Check Tailscale is connected |
| "No meals to sync" | Send food photo to Kazuhira first |
| "Health Connect error" | Grant permissions in Settings → Health Connect |

*Kazuhira Miller Health Connect Sync. v1.1.1*