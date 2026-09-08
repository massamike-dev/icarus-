# ICARUS Spatial HUD

ICARUS 1.6.0 combines the native fullscreen Driving Mode HUD with a dedicated XREAL/Beam Pro companion path for Air 2 Pro spatial presentation.

## Rendering contract

- The native Android HUD is layered over a real OpenGL ES 3 volumetric renderer.
- The XREAL companion uses Unity XR, single-pass stereo, and Air 2 Pro `MODE_3DOF` tracking.
- XREAL presentation provides Body Anchor and Smooth Follow modes rather than claiming unsupported 6DoF behavior.
- Coolant-derived heat tint and RPM-derived pulse are used only when the corresponding live OBD values exist.
- Decorative scan rings, grid depth, glow, volumetric motion, and holographic fields are presentation effects only and are never represented as sensor measurements.

## Telemetry contract

- Vehicle values are displayed only when supplied by a live OBD-II connection.
- Missing or unsupported PIDs render as an em dash (`—`).
- There is no simulated or generated vehicle telemetry fallback.
- OBD access remains read-only. The HUD does not issue ECU control, write, flash, or reprogramming commands.
- Navigation values remain unavailable until a live navigation source is integrated; the HUD does not invent route or road-status data.

## Interaction contract

- Voice remains available as the primary in-motion interaction path on the native HUD.
- Detailed native touch interaction requires live OBD speed to confirm that the vehicle is stopped. If speed is unavailable, detailed touch stays locked and voice remains available.
- The XREAL companion supports gaze/tap interaction, recentering, Body Anchor, and Smooth Follow presentation.

## XREAL handoff

The native host exposes `xreal_status`, `open_xreal_hud`, and `close_xreal_hud` capabilities.

When XREAL mode opens:

1. ICARUS verifies that the Beam Pro companion (`com.icarusalmighty.spatial`) is installed.
2. The existing OBD socket is released so there is only one vehicle-data owner.
3. `SpatialTelemetryService` starts as a connected-device foreground service and connects read-only to the selected OBD adapter.
4. Live snapshots are served only over `127.0.0.1:43215` and require a random per-launch bearer token.
5. The token and loopback port are handed to the companion using `icarus-spatial://launch`.
6. The companion renders null/missing values as unavailable rather than substituting demo data.
7. The native telemetry service shuts itself down when the companion stops polling.

The XREAL companion source lives under `xreal/`. A binary XREAL APK requires the official XREAL SDK 3.1 archive after accepting XREAL's API terms and a Unity 2022.3.62f2 Android build environment. The SDK archive is intentionally not vendored into this repository.

## Native phone handoff

The Base44 Vehicle Mode page continues to use `open_driving_hud` for the phone/native renderer and passes the selected/remembered OBD adapter address. If the native host does not expose that capability, the existing web driver layout remains available and continues to show unavailable live values as `—`.
