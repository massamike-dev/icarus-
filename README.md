# I.C.A.R.U.S. Native Android Bridge

Native companion for the existing Base44 I.C.A.R.U.S. assistant. It supplies Android capabilities that a web app cannot provide.

## Intended lifecycle

1. After a phone restart, background listening is off.
2. The user opens ICARUS and taps **Enable until restart**.
3. Android starts a microphone foreground service with a persistent notification.
4. Locking the screen or removing the UI from Recents does not intentionally enable a reboot receiver.
5. Restarting, force-stopping, revoking microphone permission, pressing Stop, or OS termination disables listening. Reopen the app to start it again.

## Implemented foundation

- Android 14+ microphone foreground-service declarations
- Bluetooth headset, Bluetooth LE audio and car-audio conversation routing
- Hands-free Conversation Mode with Listening, Thinking and Speaking states
- Automatic listen-again loop until "end conversation" or "goodbye ICARUS"
- Manual post-reboot activation and persistent Stop notification
- Free on-device wake engine trained from five recordings of the user's own voice
- Short command capture after wake detection
- Review/confirmation activity
- Command parser and handlers for alarms, timers, flashlight, volume, display settings, navigation, dialer, SMS composer, calendar, camera, battery and installed apps
- MediaStore video catalog covering accessible shared storage volumes
- Non-destructive montage plan and video-classifier boundary
- Media3 dependencies for preview/export implementation
- Authenticated Base44 native-command gateway boundary
- Server-side allow-listed `interpretNativeCommand` function added to the Base44 app
- Authenticated `nativeConversationTurn` endpoint that preserves ICARUS personality, approved memory, history and usage limits
- No credentials in source code

## Wake-word testing

Open the app, grant microphone permission, choose **Enroll Hey ICARUS**, and say the phrase five times. Templates are stored in app-private preferences and matched locally. No wake audio is uploaded. After enrollment, choose **Enable until restart**. The enrolled matcher is intentionally conservative and must be calibrated on real devices for false accepts and missed detections before release. Only the short command after a detected wake event uses Android speech recognition.

## Base44 connection

Add the published Base44 URL to untracked `local.properties`:

```properties
BASE44_URL=https://your-published-icarus-app.example
```

The Base44 command and conversation endpoints are present. The remaining account-integration step is a secure native sign-in flow that supplies a short-lived Base44 session to `SessionTokenProvider`. Do not copy a browser cookie or hard-code a token. Until signed native authentication is completed, only the local allow-listed command router is enabled.

## Safety model

Calls open the system dialer and texts open the system SMS composer. The bridge does not silently call or send. Destructive, financial, sharing, camera, location-sharing and media-editing actions require review. Original videos are never modified.

## Build

Open the project in Android Studio, allow it to install Android SDK 35, and use the generated Gradle wrapper or Android Studio build action. Java 17 is required.

GitHub Actions also builds a debug APK after every push to `main` or when the
`Build ICARUS Debug APK` workflow is started manually. Download the resulting
`ICARUS-Native-Bridge-debug` artifact from the completed Actions run.

This is a functional bridge foundation, not a finished APK. The wake SDK/model, Base44 native authentication endpoint, contact resolution, visual video classifier, montage preview/export UI and device testing remain required.
