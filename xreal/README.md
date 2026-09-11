# ICARUS XREAL Spatial HUD

This is the XREAL/Beam Pro companion for ICARUS Driving Mode. It is intentionally separate from the phone UI so the glasses can run a stereo 3DoF scene while the native ICARUS Android host owns the single read-only OBD connection.

## Hardware target

- XREAL Air 2 Pro + Beam Pro
- 3DoF rotational head tracking (`MODE_3DOF`)
- Single-pass stereo
- Beam Pro controller input
- XREAL multi-resume enabled

The Air 2 Pro is not treated as a 6DoF device. The HUD implements two 3DoF-safe presentation modes: **Body Anchor** and **Smooth Follow**.

## Telemetry contract

The ICARUS Android host starts `SpatialTelemetryService`, connects to the selected real OBD adapter, and exposes the latest read-only snapshot at `127.0.0.1:43215`. Every launch uses a random bearer token passed through the `icarus-spatial://launch` deep link.

- Missing PIDs remain null and render as `—`.
- No simulation fallback exists.
- No ECU write/control endpoint exists.
- The loopback server is not reachable from other devices.

## XREAL SDK dependency

XREAL distributes SDK 3.1.0 as `com.xreal.xr.tar.gz` behind its API Terms acceptance flow. The SDK archive is therefore **not vendored in this repository**. After accepting XREAL's terms, place the official archive at:

`xreal/com.xreal.xr.tar.gz`

The package manifest references it as `file:../com.xreal.xr.tar.gz`.

### Verified build input

The SDK archive supplied for this implementation was inspected directly and reports:

- package: `com.xreal.xr`
- display name: `XREAL XR Plugin`
- version: `3.1.0`
- package Unity baseline: `2021.3`
- SHA-256: `fd7d0fce103712e7acc85bfcbb8b127007e1d84f64596efb6c11580bea058d90`

The archive also defines `MODE_3DOF = 1`, `XREAL_DEVICE_TYPE_AIR2_PRO = 3`, single-pass-instanced stereo support, and `SupportMultiResume`, matching this project's Air 2 Pro + Beam Pro configuration.

The SDK tarball and Unity-generated build/cache folders are git-ignored on purpose. Do not commit or redistribute the XREAL package through this repository.

## Unity version

The project is pinned to Unity `2022.3.62f2` (`7670c08855a9`), matching the project baseline used for this integration. Android builds use OpenGL ES 3, API 29+, IL2CPP and ARM64.

## Build

1. Place the accepted XREAL SDK archive at `xreal/com.xreal.xr.tar.gz`.
2. Open the `xreal` directory as a Unity project using Unity `2022.3.62f2` with Android Build Support installed.
3. Use **ICARUS > Build XREAL Android**.

The build script creates the runtime scene programmatically and outputs `xreal/Build/ICARUS-XREAL.apk` by default.

For an AAB set environment variable `ICARUS_XREAL_AAB=1` before invoking the same build method in batch mode.

## Beam Pro launch flow

1. Native ICARUS releases any existing web/native HUD OBD socket.
2. `SpatialTelemetryService` becomes the only OBD owner.
3. Native ICARUS launches `icarus-spatial://launch?port=43215&token=<ephemeral token>` into `com.icarusalmighty.spatial`.
4. The Unity companion polls only loopback with that token.
5. If the companion stops polling, the native telemetry service shuts itself down automatically.

XREAL's current documentation warns that Android 16 compatibility is still under investigation. The intended XREAL host for this project is Beam Pro, not an Android 16 phone.
