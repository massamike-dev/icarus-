# ICARUS Native Android Host

This Android app is the Stage 2/3 native bridge for the Base44 ICARUS UI.

## Implemented
- WebView host with `window.ICARUS_NATIVE` JavaScript interface
- verified-result action contract
- foreground “Hey Icarus” speech-recognition service after the user opens the app and grants microphone access
- launch installed apps by visible app name
- flashlight, media volume, brightness permission flow, battery status
- alarms and timers
- safe call dialer and SMS composer with contact lookup
- navigation and camera intents
- paired Bluetooth device reporting
- read-only Bluetooth Classic ELM327-style OBD-II connection
- OBD snapshot: speed, RPM, coolant temperature, engine load, throttle, fuel level, and adapter voltage

## Build prerequisite
Base44's sandbox does not contain Java, Gradle, the Android SDK, or Unity, so this source cannot be compiled to an APK inside Base44.

Open `native/android` in Android Studio. Before building, set `ICARUS_WEB_URL` in `gradle.properties` to the published HTTPS URL for the Base44 ICARUS app.

Then build/install the `app` configuration on Beam Pro or another supported Android host.

## Permissions
Microphone permission is requested for the wake-word foreground service. Other protected permissions such as contacts, camera, Bluetooth and system brightness are requested only when the matching device action needs them.

## Wake-word reality check
The foreground service is designed to remain alive after the app is opened, including while the screen is off, until restart/force-stop/service stop. Android can still restrict background activity launches on some firmware. Beam Pro testing is required before claiming that a wake phrase always brings the full UI to the foreground from every screen-off state.

## Vehicle safety
The OBD implementation is read-only. It sends standard diagnostic PID queries and adapter setup commands only. It contains no ECU write/reflash/control commands.