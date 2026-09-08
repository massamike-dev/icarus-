# ICARUS Spatial HUD

ICARUS 1.5.0 introduces a native fullscreen Driving Mode HUD designed for phone, external display, and future XREAL spatial presentation.

## Telemetry contract

- Vehicle values are displayed only when supplied by a live OBD-II connection.
- Missing or unsupported PIDs render as an em dash (`—`).
- There is no simulated or generated vehicle telemetry fallback.
- OBD access remains read-only. The HUD does not issue ECU control, write, flash, or reprogramming commands.
- Navigation values remain unavailable until a live navigation source is integrated; the HUD does not invent route or road-status data.

## Interaction contract

- Voice remains available as the primary in-motion interaction path.
- Detailed touch interaction is locked while live OBD speed indicates the vehicle is moving.
- Decorative scan rings, grid depth, glow, and motion are presentation effects only and are never represented as sensor data.

## Native handoff

The Base44 Vehicle Mode page uses the `open_driving_hud` native capability and passes the selected/remembered OBD adapter address. If the installed native host does not expose that capability, the existing web driver layout remains available and continues to show unavailable live values as `—`.
