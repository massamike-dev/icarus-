# ICARUS XREAL glasses module

This module is the Android boundary between the native ICARUS assistant and the XREAL Unity runtime.

## Architecture

- `:app` remains the main Android assistant and owns wake listening, AI, phone actions, navigation, OBD/Bluetooth, Meta Wearables, updates, and account/web integration.
- `:xreal` owns glasses-mode launch contracts and shared HUD state.
- The XREAL Unity project will export an Android library and register the ICARUS XREAL launch action.
- The result remains one installed ICARUS application with one package identity and one launcher icon.

## Supplied XREAL package

The supplied package is `com.xreal.xr` version `3.1.0` and declares Unity `2021.3` as its minimum editor version.

The raw Unity package should be imported into the Unity XR project, not copied directly into the native Android app. Unity then exports an Android library which is embedded behind this module.

## Launch contract

The exported XR activity should register:

`com.icarusalmighty.action.OPEN_XREAL`

Requested mode is passed in:

`com.icarusalmighty.extra.XREAL_MODE`

Supported modes:

- `ASSISTANT`
- `VEHICLE`

This keeps the native Android host independent of Unity activity class names while letting the XR implementation change internally.
