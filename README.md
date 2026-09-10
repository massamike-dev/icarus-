# I.C.A.R.U.S.

**Intelligent Companion for Assistance, Reasoning, Understanding, and Support**

ICARUS is an Android-first personal assistant backed by the published Base44 web app. The Android host adds capabilities that a browser cannot safely provide, including wake-word listening, native voice capture/TTS, device actions, Bluetooth/OBD-II access, optional wearable integrations, and signed in-place updates.

## Canonical Android app

> **`native/android/` is the only canonical Android application.**

Current package: `com.icarusalmighty.app`

- **1.4.3 / versionCode 20** is the current stability baseline.
- **1.4.4 / versionCode 21** is the integration-hardening line under development.
- `native/android/app/` is the installable ICARUS application module.
- `native/android/xreal/` is the optional XREAL integration module.
- `.github/workflows/android-build.yml` is the canonical Android CI/release workflow.

The repository-root `/app` tree is a **legacy 1.2.0 bridge retained only for historical reference**. Do not build, release, or add new work there. See `app/README.md`.

## 1.4.4 architecture

Driving Mode is a core ICARUS feature. Wearables and vehicle telemetry are optional extensions, never prerequisites.

```text
ICARUS
└── Driving Mode
    ├── Phone map + GPS + ICARUS voice   (core)
    ├── OBD-II telemetry                 (optional)
    ├── XREAL integration                (optional)
    └── Meta integration                 (optional)
```

If Meta or XREAL is disabled, unavailable, disconnected, or fails, phone Driving Mode continues. OBD-II may be connected or removed independently. No ARCore or Google Play Services for AR dependency is required for core Driving Mode.

### Optional integration rules

- **Meta Integration** defaults off. When off, Meta runtime code must not initialize and Meta controls are hidden.
- **XREAL Integration** defaults off. When off, the XREAL runtime/controller must not be constructed and XREAL controls are hidden.
- Turning either integration off must never disable map/GPS navigation, ICARUS voice, or normal assistant use.
- Wearable failures degrade to the phone UI rather than terminating ICARUS.
- OBD-II is read-only telemetry. ICARUS does not send vehicle-control commands to the ECU.

## Implemented foundation

- Android 14+ microphone foreground-service declarations
- Account-free local wake-word engine with persistent opt-in notification
- Native short-command speech capture after wake detection
- Android TTS voice path and hands-free conversation support
- Native device actions for alarms, timers, flashlight, volume, brightness, navigation, camera, calls/SMS, Bluetooth, battery, and app launching
- Local Gemma fallback and native-command interpretation boundary
- Bluetooth OBD-II connection with read-only RPM, speed, coolant, voltage, engine-load and related telemetry
- Base44 command/conversation gateway
- Signed Play AAB and shareable APK pipeline
- In-place updater manifest
- XREAL module boundary
- Meta DAT dependency boundary, currently guarded while startup compatibility is validated

## Wake-word release requirement

The wake engine is functional but must be calibrated on real target devices before general release. Measure false accepts and missed detections in quiet, vehicle, TV/music, pocket, and Bluetooth-audio conditions. Do not treat an uncalibrated threshold as production-ready merely because it compiled. Computers are exceptionally willing to pass CI while misunderstanding the room.

## Privacy and permissions

The canonical Android app requests only permissions tied to explicit features. See [`PRIVACY.md`](PRIVACY.md) for the permission-by-permission rationale and release checklist.

Published privacy policy: **https://icarusassistant.com/privacy-policy**

Key rules:

- Wake-word listening is user-enabled and uses a foreground microphone service with a persistent notification.
- Wake-word processing is local; the short command after a wake event may use Android speech recognition.
- Location is used for Driving Mode/navigation only and does not require background-location permission.
- Contacts are used only when resolving a user-requested call/text recipient.
- Camera access is user initiated.
- Bluetooth is used for audio routing and optional OBD-II/wearable connections.
- Original media is never modified by the montage pipeline.
- No authentication tokens, API keys, or signing credentials belong in source control.

## Safety model

Sensitive device actions remain explicit and reviewable. Calls/texts should use user-visible Android flows or clearly confirmed native behavior. Destructive, financial, sharing, camera, location-sharing, and media-editing actions require appropriate confirmation. Optional integrations do not get broader authority merely because they are attached to glasses.

## Build the canonical app

Requirements: Java 17 and Android SDK 36.

```bash
cd native/android
bash scripts/prepare-sherpa-kws.sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  -PICARUS_WEB_URL="https://icarusassistant.com"
```

For release builds use `.github/workflows/android-build.yml`; signing credentials remain in GitHub Actions secrets.

## Release-readiness checklist

Before calling a build generally release-ready:

1. Unit tests pass for command policy, optional-integration capability gating, and Driving Mode fallback.
2. Debug/release lint and signed APK/AAB builds pass.
3. Wake-word thresholds are calibrated on representative devices.
4. Privacy policy and permission disclosures match the shipped manifest.
5. Meta and XREAL can each be disabled without changing core Driving Mode behavior.
6. Unsupported/incomplete UI is gated or labeled rather than exposed as a dead control.
7. Device testing covers fresh install, in-place upgrade, reboot, revoked permissions, offline mode, and accessory disconnects.
