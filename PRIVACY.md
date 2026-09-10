# ICARUS privacy and Android permissions

Canonical Android app: `native/android/`

Published privacy policy: https://icarusassistant.com/privacy-policy

This document explains why the Android host requests each permission and what release behavior is expected. It is a repository engineering document; the published privacy policy remains the user-facing policy.

## Permission rationale

| Permission | Why ICARUS uses it | Expected behavior |
| --- | --- | --- |
| `INTERNET` | Load the published Base44 app, cloud assistant endpoints, update metadata, and optional SDK network calls | Core UI should still degrade cleanly when offline and use local capabilities where available |
| `RECORD_AUDIO` | Wake-word listening and explicit voice/conversation capture | Wake listening is opt-in; foreground mic service shows a persistent notification |
| `POST_NOTIFICATIONS` | Persistent wake-word foreground-service notification and related status | Requested only where Android requires runtime notification permission |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` | Keep user-enabled wake-word listening alive while the UI is not foregrounded | Stops on force-stop, permission revocation, explicit Stop, or reboot until user re-enables |
| `CAMERA` | User-requested photos and optional wearable camera functions | Camera actions remain user initiated/reviewable |
| `READ_CONTACTS` | Resolve a spoken contact name for a requested call or text | Contact data is used only for recipient resolution; ICARUS must not invent a contact |
| `CALL_PHONE` | Execute an explicitly requested native call when that behavior is enabled | Calls are sensitive actions and require the app's confirmation policy |
| `MODIFY_AUDIO_SETTINGS` | Route/adjust assistant audio for phone, Bluetooth and vehicle use | Does not grant recording access by itself |
| `WRITE_SETTINGS` | User-requested display brightness changes | Android system approval is required before writes are allowed |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (legacy Android) | Compatibility with older Android Bluetooth APIs | Limited to older SDK levels by the manifest |
| `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | Discover/connect paired audio, OBD-II and optional wearable devices | OBD-II and wearables are optional; disabling them must not block Driving Mode |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | Phone Driving Mode GPS position/speed/heading and map centering | Used only while the user is actively using Driving Mode; **no background-location permission** |

## Driving Mode privacy rules

Driving Mode is a core phone feature. Meta, XREAL, and OBD-II are independent optional layers.

- Phone map/GPS and ICARUS voice continue when every accessory is disabled.
- Location is requested when Driving Mode needs it, not merely because the app launches.
- No `ACCESS_BACKGROUND_LOCATION` permission is used.
- OBD-II access is read-only telemetry. ICARUS does not send vehicle-control commands to the ECU.
- Disabling Meta or XREAL must stop ICARUS from initializing or invoking that integration.

## Wake-word privacy rules

- Wake-word processing runs locally in the Android host.
- No continuous wake audio is intentionally uploaded.
- After a detected wake event, the short command may use Android speech recognition depending on device configuration.
- The persistent foreground notification gives the user an obvious way to see that microphone listening is active.
- Reboot does not silently re-enable listening.

## Media and contacts

- Original videos are never modified by the montage pipeline.
- Media export should create a new output rather than overwrite originals.
- Contact lookup is limited to fulfilling an explicit call/text request.

## Credentials and account data

- API keys, signing keys, browser cookies, session tokens, Meta client tokens, and similar credentials must not be committed to source control.
- Native authentication must use a supported short-lived session flow rather than copying browser cookies into the APK.

## Release checklist

Before publishing a release, verify that:

1. The shipped manifest matches this permission list.
2. The published privacy policy at https://icarusassistant.com/privacy-policy describes the same data uses.
3. Runtime permission prompts appear only when their corresponding feature is used.
4. Meta/XREAL disabled states do not initialize their SDK/runtime.
5. Driving Mode works without Meta, XREAL, or OBD-II.
6. Wake listening remains clearly opt-in and user-stoppable.
