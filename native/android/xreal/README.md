# ICARUS native XREAL HUD module

## Implemented path

`:xreal` is a bundled native Android library. Its `XrealHudActivity` renders a
black-background 2D HUD and receives package-scoped state updates from `:app`.
The host uses an explicit in-app intent. This path remains one installed ICARUS
application; it does not contain a Unity export or initialize the XREAL SDK.

Beam Pro's nebulaOS supports ordinary 2D apps in OS-managed spatial windows.
That is the intended existing native delivery path to evaluate with Air 2 Pro.
On a phone, an ordinary Activity launch may simply show a phone preview. Neither
successful `startActivity` nor a display-sized layout proves glasses output.
The native renderer does not itself provide stereo or head tracking; any spatial
placement of its 2D window is controlled by the host OS.

## Output diagnostics

`XrealModeController.outputDiagnostics()` observes public Android display APIs
without requesting permissions or changing display routing. Its JSON includes:

- `outputPath: "os_managed_2d_window"`
- `outputVerification: "unverified"`, always
- `glassesConnectionVerified: false`, always
- `actualActivityDisplayId`: the live HUD Activity's observed logical display ID,
  or null before observation and after destruction
- `accessibleLogicalDisplays`: ID, name, default/presentation/private flags,
  raw flags/state, and validity for each currently accessible logical display
- `type: "unknown"` per display: the supported public API does not reveal its
  physical-versus-virtual type; no hidden API or name heuristic is used
- `displayEnumerationSucceeded` and an optional `diagnosticError`, distinguishing
  successful empty enumeration from inability to read the service

These observations do not identify physical XREAL glasses. A logical display
can be virtual or external, and a private OS-managed spatial pane may be hidden
until the app occupies it. No public XREAL contract currently establishes that
an ordinary Beam Pro app can enumerate the physical glasses as a public display.
Empty or failed enumeration never blocks an otherwise valid native HUD launch.

Launch acceptance, pending state, and a running Activity are separate from output
verification. A successful launch reports `os_managed_output_unverified` rather
than claiming verified XREAL output. The module does not select a secondary
display, activate mirroring, request USB access, or infer a connection from an
Activity, device name, display resolution, or installed MyGlasses app.

## Separate Unity companion

The repository-root `xreal/` project is a separate Unity companion targeting
`com.icarusalmighty.spatial`. It is not currently embedded into this library.
Its source targets SDK 3.1.0, stereo rendering and Air 2 Pro 3DoF; see
[`xreal/README.md`](../../../xreal/README.md) for its build prerequisites and
separate deep-link/loopback telemetry contract. Those source settings are not
proof of a built or hardware-validated companion.

The official SDK is a Unity package, not a Kotlin dependency that can simply be
copied into this module. Building the companion requires the accepted local SDK
archive and a configured Unity editor with Android support. No SDK installation,
Unity export, permissions, or companion launch is performed by these diagnostics.

## Stationary Air 2 Pro + Beam Pro acceptance check

Perform hardware validation only while stationary in a safe environment. XREAL's
Air 2 Pro safety instructions prohibit using the glasses while operating a
vehicle. This check is not public-road testing or a driving-safety certification.

1. Install the authorized ICARUS test build on Beam Pro using its supported
   Android installation workflow. Connect Air 2 Pro to Beam Pro's glasses port.
2. Open ICARUS from Beam Pro's spatial environment, then request its bundled HUD.
   Confirm the actual HUD is visible in the glasses, not merely on Beam Pro.
3. Inspect the status: distinguish Android launch acceptance, the live Activity,
   its logical display ID, and the still-unverified physical output field.
4. Check text, safe margins, optical legibility, and the black background with
   the actual lenses. A black phone screen does not establish optical legibility.
5. Disconnect/reconnect and close/reopen. Confirm diagnostics refresh, a destroyed
   Activity clears its display ID, and unknown output never becomes false success
   or a claim that glasses are definitively disconnected.
6. Repeat on a phone without glasses. Opening a native preview must still report
   output as unverified. Do not substitute a phone preview for glasses validation.

Source contracts and JVM tests do not replace Android compilation or these
hardware checks. Any future tracked/stereo companion requires separate runtime
and device validation.

## Primary references

- [Beam Pro 2D spatial windows and Air 2 Pro support](https://www.xreal.com/beampro)
- [Air 2 Pro 3DoF/no-camera feature compatibility](https://docs.xreal.com/XREALDevices/Compatibility)
- [SDK 3.1.0 supported hosts](https://docs.xreal.com/Release%20Note/XREAL%20SDK%203.1.0)
- [Official Unity/Android setup and MyGlasses deployment](https://docs.xreal.com/Getting%20Started%20with%20XREAL%20SDK)
- [Android logical-display visibility](https://developer.android.com/reference/android/hardware/display/DisplayManager)
- [Android public Display API](https://developer.android.com/reference/android/view/Display)
- [Air 2 Pro safety instructions](https://resource.xreal.com/www-xreal-com/docs/manual/XREAL_Air2Pro_Quick_Start_Guide.pdf)
