# OTGCam Agent — Setup Guide

## 1. Create a Telegram Bot and Obtain the Bot Token

1. Open Telegram and search for **@BotFather**.
2. Send `/newbot` and follow the prompts to choose a name and username for your bot.
3. BotFather will reply with a token in the format:
   ```
   1234567890:ABCDefGhIJKlmNoPQRsTUVwxyZ
   ```
4. Copy this token — you will enter it in the app's setup screen.
5. **Keep this token secret.** Anyone with the token can send messages as your bot.

## 2. Obtain Your Telegram Chat ID

1. Start a conversation with your bot by sending it any message (e.g. `/start`).
2. Open a browser and navigate to:
   ```
   https://api.telegram.org/bot<YOUR_BOT_TOKEN>/getUpdates
   ```
3. Look for `"chat":{"id":` in the JSON response. The number that follows is your Chat ID.
   - Example: `"chat":{"id":987654321,"first_name":"..."}`
4. If the result is empty, send another message to the bot and refresh the URL.
5. For a **group chat**: add the bot to the group, send a message mentioning the bot,
   then check getUpdates. Group chat IDs are negative numbers (e.g. `-100123456789`).

## 3. Define an Agent ID

The Agent ID is a short alphanumeric string you choose to identify this specific device
(e.g. `agent01`, `fieldcam`, `unit-a`). It must match exactly what you configure in the
Receiver app. It is embedded in every signaling message so the Receiver only responds
to signals intended for this Agent.

## 4. Build the APK

Prerequisites: Android Studio Electric Eel or newer, JDK 11+.

```bash
cd otgcam-agent
./gradlew assembleDebug
```

Output APK path:
```
app/build/outputs/apk/debug/app-debug.apk
```

## 5. Install the APK

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the device and open it with a file manager (enable "Install from
unknown sources" in Settings → Security first).

## 6. First-Launch Setup

1. Launch **OTGCam Agent** on the field device.
2. The setup screen appears. Enter:
   - **Bot Token** — from BotFather (Section 1)
   - **Chat ID** — your Telegram chat ID (Section 2)
   - **Agent ID** — your chosen identifier (Section 3)
3. Tap **Save and Continue**.
4. Grant all requested permissions (Camera, Microphone, Storage).
5. Tap **Start Service**.
6. Connect your UVC camera via the OTG adapter — you will feel a 3-second vibration
   confirming detection.

## 7. Grant "Display Over Other Apps" Permission (Android 6+)

The Agent uses a 1×1 pixel overlay window to keep the camera surface alive with the
screen locked. This requires the overlay permission.

1. Go to **Settings → Apps → OTGCam Agent → Advanced → Display over other apps**.
2. Enable the toggle.

On Android 10+, the app will open the permission screen automatically on first launch.

## 8. Battery Optimisation Exemption

The service will be killed by aggressive battery management unless you whitelist the app.

### Stock Android (Pixel, Android One)
Settings → Battery → Battery Saver → Unrestricted apps → Add OTGCam Agent

### Samsung (One UI)
Settings → Device Care → Battery → Background usage limits → Never sleeping apps → Add OTGCam Agent

### Xiaomi / MIUI
Settings → Apps → Manage apps → OTGCam Agent → Battery saver → No restrictions
Also: Security app → Permissions → Autostart → Enable for OTGCam Agent

### OnePlus / OxygenOS
Settings → Battery → Battery Optimisation → OTGCam Agent → Don't optimise

## 9. Troubleshooting

| Problem | Solution |
|---|---|
| UVC camera not detected | Check OTG cable is fully seated. Verify the camera is UVC class (most webcams are). Try a powered OTG hub if the camera draws too much current. |
| 3-second vibration then nothing | Camera connected at OS level but library failed to open. Check permissions were granted. Try unplugging and replugging. |
| No vibration on button press | Open the Agent app, tap Start Service. The earpiece receiver only works when the service is running. |
| Photos not uploading | Check internet connection. Verify bot token and chat ID in setup (re-enter if needed). Check the log view for the specific error. |
| Service stops after screen off | Battery optimisation is killing the service. Follow Section 8 for your device. |
| App crashes on launch | Android version may be below API 21. OTGCam requires Android 5.0+. |
| "Overlay permission required" warning in log | Follow Section 7 to grant the overlay permission. |
| Boot auto-start not working | Battery optimisation or manufacturer restrictions. Enable Autostart (Xiaomi) or add to protected apps list. |
| Audio call connects but no sound | Bluetooth SCO may not have initialised. Disconnect and reconnect the Bluetooth headset while the service is running. |
| Video resolution is lower than 4K | The UVC camera does not support 4K. The library negotiates down to the highest supported resolution automatically. |
| Upload queue grows indefinitely | Persistent network failure. Check bot token validity at api.telegram.org/bot<TOKEN>/getMe |
| SetupFragment shows blank fields | EncryptedSharedPreferences key store may be corrupt. Clear app data in Settings → Apps → OTGCam Agent → Storage → Clear Data and re-enter credentials. |
| "foregroundServiceType not allowed" crash on Android 9 | Target SDK in build.gradle must be 29. Rebuild. |
