# Optical HUD composition and prototype limits

## Requirement and primary route

The user’s primary target is XREAL glasses, with the phone as secondary Maps assistance. The real world must remain visible behind the optical instruments. An empty dark center in a concept image is not proof of functioning see-through hardware.

`XrealHudActivity` now selects `CinematicDrivingHudView` for its actual VEHICLE mode. The canonical renderer, render-only scene model and unchanged transparent pickup PNG live in the existing `:xreal` library. The app’s `DrivingHudView` is a thin phone-state/action adapter to that renderer. This closes the previous mismatch where only the phone route had the detailed cinematic graphics. XREAL assistant mode remains separate and preserved.

## Composition

- The shared View draws instruments only: no full-canvas tint, gradient, bloom or synthetic scene. The normal center (design x470–1208, y340–550) and aspect-fit margins draw no HUD pixels.
- Both native hosts use pure-black roots/windows/system bars and activity-specific black startup/splash backgrounds. The phone’s decorative `VolumetricHudSurface` is no longer active. Its unused source is retained.
- The PNG keeps its original alpha channel and bounded lower-band placement. It is decoded once at half resolution, with native wireframe fallback on failure.
- Overall View alpha stays 1 for crisp edges. Unused View pixels remain transparent; the host supplies the black optical backdrop.
- Phone-only touch/voice/exit controls are preserved in the phone adapter. The XREAL scene remains read-only, without dead phone-only buttons or misleading tap prompts. Its footer states stationary evaluation only.
- The OS may show an app icon during startup. Black splash backgrounds do not suppress that icon.

On additive optical glasses, black output contributes no display light, allowing the real world to remain visible through the optics. Lens tint/dimming, ambient light, fit and brightness still affect actual visibility. No physical device setting is changed or claimed verified. See [Android’s official wired-XR visual guidance](https://developer.android.com/design/ui/xr/guides/visual-design).

## Physical-output truth

The bundled module is an ordinary Android 2D Activity. Beam Pro can spatialize ordinary 2D apps through nebulaOS, but that is not proof that this APK is visible on attached Air 2 Pro. Output remains explicitly unverified. Permission-free diagnostics report only accessible logical displays and the Activity’s observed display ID; those IDs/names/flags do not identify physical glasses. An empty list does not prove disconnection either.

No direct-display launch, proprietary intent, USB grant, stereo renderer or app-owned head tracking is introduced. The separate Unity companion remains unchanged and requires its own SDK/export/device validation. See [Beam Pro documentation](https://www.xreal.com/beampro), [XREAL SDK compatibility](https://docs.xreal.com/XREALDevices/Compatibility) and the module README.

## Phone path

The control page puts XREAL first. The secondary phone action opens an unsent route-planning Chat draft; it does not send a destination or launch Maps automatically. Existing reviewed navigation commands can hand a chosen destination to the phone’s mapping app. Optional phone instruments remain available. No road-camera feed is added or activated. Android transparency and map guidance do not reveal the physical road through an opaque phone screen.

## Data and lifecycle safety follow-through

Live telemetry mapping replaces missing readings with null rather than resurrecting launch-time metadata. Speed evidence is bounded by monotonic freshness, with tap-time checks and expiry independent of GPS/Bluetooth callbacks. Missing, delayed, invalid or conflicting readings cannot be assumed to establish a stopped vehicle. Stale OBD diagnostics are cleared. See `DRIVING_TELEMETRY_FRESHNESS.md` for the implemented policy and regression limits.

The source also guards XREAL lifecycle boundaries: Back/host closure releases telemetry, restored vehicle windows start unknown, and a five-second receiver deadline clears data when the producer stops. A one-second active-session heartbeat requests freshness-revalidated source state without renewing measurement timestamps. Metadata updates cannot extend that deadline. A configuration/process recreation that loses the producer stays unknown and asks the user to reopen XREAL HUD on the phone; automatic recovery is not promised. A pending replacement launch keeps its own telemetry lease when the old window finishes. Source contracts are distinct from device execution; actual recreation, disconnect and callback-delay behavior must be verified before acceptance.

## Manufacturer restriction and acceptance

**Stationary prototype only.** XREAL’s official [Air 2 Pro safety guide](https://resource.xreal.com/www-xreal-com/docs/manual/XREAL_Air2Pro_Quick_Start_Guide.pdf), “Recommended Use Environment” (printed Safety Instructions page 1; PDF page 12), says not to use the glasses while operating a vehicle. It also cautions passengers in moving vehicles. A clear center or software freshness test does not remove that restriction or establish driving safety.

All proposed hardware validation is stationary: compile the app, run native tests/lint, install on Beam Pro, launch in its spatial environment with compatible glasses connected, and inspect composition, brightness, actual display routing, lifecycle and disconnect/reconnect. Do not infer safe driving use or automotive certification from a preview, APK or passing source test.

## Verified versus unverified

Source contracts inspect the shared route, transparent instruments, inactive full-screen underlay, black host/themes, bounded asset, truthful output status, data mapping and safety policy. Static SVG-derived previews embed the exact pickup and expose a transparent layer whose reserved-center alpha is zero. They are not Android screenshots.

Android compilation, native JUnit, GPU output, real hardware display/lens behavior and the on-device safety/lifecycle cases remain unverified in this executor. No APK, publication, new account, spending, credential or permission change is included.
