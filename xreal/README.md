# ICARUS XREAL module

This Android library is the boundary between the native ICARUS assistant and the XREAL glasses runtime.

## Architecture

- `:app` remains the main Android assistant and owns wake listening, AI, phone commands, navigation, Bluetooth/OBD, updates, and permissions.
- `:xreal` owns the glasses-mode launch contract and HUD state models.
- The Unity/XREAL runtime will be exported as an Android library and linked behind this module rather than replacing the native ICARUS app.
- ICARUS keeps one launcher application, one package identity, and one app icon.

## XREAL SDK source

The supplied Unity package is `com.xreal.xr` version `3.1.0` and declares Unity `2021.3` as its minimum editor version.

Do not copy the raw Unity package directly into the Android app. Import it into the Unity XR project, build the ICARUS glasses scene there, then export Unity as an Android library for embedding.

## Launch contract

The exported glasses activity should register this intent action:

`com.icarusalmighty.action.OPEN_XREAL`

It receives the requested mode through:

`com.icarusalmighty.extra.XREAL_MODE`

Current mode values:

- `ASSISTANT`
- `VEHICLE`

This keeps the native app independent of Unity activity class names and lets the XR implementation evolve without changing the Android assistant entry point.
