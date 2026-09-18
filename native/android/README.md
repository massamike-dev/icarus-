# Canonical ICARUS Android app

This directory is the authoritative Android application for ICARUS.

- Package: `com.icarusalmighty.app`
- Current development line: `1.4.4` / versionCode `21`
- App module: `app/`
- Optional XREAL module: `xreal/`
- Canonical CI/release workflow: `.github/workflows/android-build.yml`
- Published independent ICARUS host: `https://icarusassistant.com`

Do not make Android product changes in the repository-root `/app` directory. That tree is the deprecated 1.2.0 bridge retained only for historical comparison.

## Core architecture

Phone Driving Mode is always available independently of accessories:

```text
Driving Mode
├── Map + foreground GPS + ICARUS voice
├── OBD-II telemetry (optional)
├── XREAL spatial HUD (optional)
└── Meta wearable features (optional)
```

Disabling Meta or XREAL must prevent that integration from initializing or being invoked. A failed/disconnected wearable falls back to the phone experience rather than interrupting navigation or ICARUS voice.

## Implemented native host capabilities

- WebView host with `window.ICARUS_NATIVE`
- account-free wake-word engine and foreground microphone service
- native command capture and Android TTS
- installed-app launch, flashlight, volume, brightness, battery, alarms/timers
- calls/SMS/contact resolution with sensitive-action policy
- navigation intents and phone Driving Mode GPS bridge
- Bluetooth device reporting
- read-only ELM327-style OBD-II telemetry
- local Gemma model boundary
- signed Play AAB/shareable APK and in-place updater
- optional XREAL module boundary
- Meta DAT boundary, currently crash-guarded pending device validation

## Build

Java 17 and Android SDK 36 are required.

```bash
bash scripts/prepare-sherpa-kws.sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  -PICARUS_WEB_URL="https://icarusassistant.com"
```

Release builds and signing should run through the canonical GitHub Actions workflow rather than local ad-hoc signing.

## Private phone testing

The `privateTest` build type produces **ICARUS Test** (`com.icarusalmighty.app.test`, version suffix `-test`). It installs alongside regular ICARUS with separate Android storage, WebView cookies, native preferences, and permissions. It accepts `icarus-test://` links; regular `icarus://` links continue to open the regular app.

Use the private test workflow with an authenticated, isolated test backend. The build requires `-PICARUS_TEST_WEB_URL=https://<private-test-origin>` and uses the existing upload signing credentials. There is no production URL fallback. Production origins, URL credentials, non-root paths, queries, and fragments are rejected. Packaging also rejects reserved placeholder hosts and missing signing credentials. CI can compile, lint, and run unit tests with `https://icarus-test.invalid` without producing an installable APK.

Public update checks and Google Play billing are disabled in ICARUS Test. Updates must be installed through the private distribution flow. Production Play subscription, licensing, and update behavior require the normal Play package and cannot be verified with this separate package. Meta DAT remains guarded as in the normal app; future Meta device testing requires approval for the test package and signing identity. Shared external hardware (microphone, Bluetooth, glasses) is still a physical resource: stop the regular app's hands-free listener before testing the second app.

The private origin receives the same exact-origin, main-frame-only Android bridge restrictions as production. The APK contains no access credentials: the test server must enforce account access and keep its test data separate.

## Permissions

See the repository-root [`PRIVACY.md`](../../PRIVACY.md). Location is foreground-only for Driving Mode; ICARUS does not request Android background-location permission.

## Wake-word release check

The wake service is intentionally opt-in and user-visible. The detection threshold still requires real-device calibration for false accepts and missed detections before general release.

## Vehicle safety

The OBD implementation is read-only. It sends standard diagnostic PID queries and adapter setup commands only. It contains no ECU write/reflash/control commands.
