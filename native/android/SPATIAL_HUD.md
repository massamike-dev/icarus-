# ICARUS Spatial HUD

ICARUS combines a native fullscreen Driving Mode HUD, a bundled XREAL-friendly overlay,
and an explicitly selected separate Unity/Beam Pro companion path.

## Rendering contract

- The native Android HUD is layered over a real OpenGL ES 3 volumetric renderer.
- The bundled `XrealHudActivity` is a screen-fixed 2D overlay. It does not initialize
  the XREAL SDK, detect attached glasses, provide stereo rendering, or track head pose.
  `runtimeAvailable` means the bundled activity can launch, not that glasses are connected.
- The separate XREAL companion source targets Unity XR, single-pass stereo, and Air 2 Pro
  `MODE_3DOF` tracking, with Body Anchor and Smooth Follow. Those capabilities require its
  own binary and hardware validation; the Android bridge reports tracking as unverified.
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

### Default: bundled overlay

- `getStatus()` includes `hudControlVersion: 1`; the web UI requires this before showing the
  new controls so older ICARUS Test installations cannot invoke the legacy launch contract.
- Omit `arguments.target` (or use `"bundled"`). Enable the optional integration with
  `meta_integration_set`, `provider: "xreal"`, `enabled: true`, then choose `mode: "assistant"`
  or `mode: "vehicle"`. No OBD adapter or Bluetooth permission is required for this overlay.
- Launch uses an explicit in-app intent on the UI thread. `launched: true` / `launchRequested: true`
  means Android accepted the activity launch, not that a glasses display or 3DoF was verified.
- `close_xreal_hud` calls the live activity's main-thread finish handler. `closeRequested: true`
  means a live or pending activity was asked to finish; `alreadyClosed: true` means neither existed.
  A close received before `onCreate` cancels that pending launch. Disabling XREAL also closes it.
- Updates are package-scoped, non-exported broadcasts and return `updateRequested`, not a
  false rendering acknowledgement. Missing route, heading, and vehicle data stay unavailable.
- This overlay does not open a live OBD connection. Real phone OBD telemetry remains in
  `open_driving_hud`; the separate Unity companion retains its own telemetry service below.

### Explicit legacy Unity companion

Send `arguments.target: "companion"` to the same three actions. XREAL opt-in remains required.
When that companion mode opens:

1. ICARUS verifies that the Beam Pro companion (`com.icarusalmighty.spatial`) is installed.
2. The existing OBD socket is released so there is only one vehicle-data owner.
3. `SpatialTelemetryService` starts as a connected-device foreground service and connects read-only to the selected OBD adapter.
4. Live snapshots are served only over `127.0.0.1:43215` and require a random per-launch bearer token.
5. The token and loopback port are handed to the companion using `icarus-spatial://launch`.
6. The companion renders null/missing values as unavailable rather than substituting demo data.
7. The native telemetry service shuts itself down when the companion stops polling.

Closing this target stops the telemetry service only. The bridge cannot verify or force closure
of another app's activity and returns `closeRequested: false` with instructions to close it manually.

The XREAL companion source lives under `xreal/`. The supplied official XREAL SDK archive has been verified as `com.xreal.xr` 3.1.0 and is intentionally not vendored into this repository. Producing the binary companion APK requires that local archive plus Unity 2022.3.62f2 with Android Build Support and an activated Unity editor license.

## Native phone handoff

The Base44 Vehicle Mode page continues to use `open_driving_hud` for the phone/native renderer and passes the selected/remembered OBD adapter address. If the native host does not expose that capability, the existing web driver layout remains available and continues to show unavailable live values as `—`.
