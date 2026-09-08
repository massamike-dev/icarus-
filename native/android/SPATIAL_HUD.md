# ICARUS Spatial HUD

ICARUS 1.5.2 introduces a native fullscreen Driving Mode HUD designed for phone/external display today and future XREAL spatial presentation.

## Rendering contract

- The interactive HUD is layered over a real OpenGL ES 3 volumetric renderer.
- The volumetric layer uses fragment-shader ray marching with procedural density/noise, scan-shell, corridor, and holographic core fields.
- Coolant-derived heat tint and RPM-derived pulse are used only when the corresponding live OBD values exist.
- Decorative scan rings, grid depth, glow, volumetric motion, and holographic fields are presentation effects only and are never represented as sensor measurements.
- True XREAL head-tracked stereo presentation remains a separate XREAL SDK/Unity XR integration; this Android renderer is the production native foundation and external-display path.

## Telemetry contract

- Vehicle values are displayed only when supplied by a live OBD-II connection.
- Missing or unsupported PIDs render as an em dash (`—`).
- There is no simulated or generated vehicle telemetry fallback.
- OBD access remains read-only. The HUD does not issue ECU control, write, flash, or reprogramming commands.
- Navigation values remain unavailable until a live navigation source is integrated; the HUD does not invent route or road-status data.

## Interaction contract

- Voice remains available as the primary in-motion interaction path.
- Detailed touch interaction requires live OBD speed to confirm that the vehicle is stopped. If speed is unavailable, detailed touch stays locked and voice remains available.

## Native handoff

The Base44 Vehicle Mode page uses the `open_driving_hud` native capability and passes the selected/remembered OBD adapter address. The native host releases any existing Vehicle Mode OBD socket before opening the HUD so the HUD can reconnect cleanly to the same adapter. If the installed native host does not expose that capability, the existing web driver layout remains available and continues to show unavailable live values as `—`.
