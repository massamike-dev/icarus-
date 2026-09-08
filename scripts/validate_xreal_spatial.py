from pathlib import Path

root = Path(__file__).resolve().parents[1]
required = [
    "native/android/app/src/main/java/com/icarusalmighty/app/spatial/SpatialTelemetryService.kt",
    "xreal/Packages/manifest.json",
    "xreal/ProjectSettings/ProjectVersion.txt",
    "xreal/Assets/XR/XRGeneralSettingsPerBuildTarget.asset",
    "xreal/Assets/XR/Loaders/XREALXRLoader.asset",
    "xreal/Assets/XR/Settings/XREALSettings.asset",
    "xreal/Assets/Icarus/Runtime/IcarusSpatialBootstrap.cs",
    "xreal/Assets/Icarus/Runtime/LoopbackTelemetryClient.cs",
    "xreal/Assets/Icarus/Runtime/SpatialHudView.cs",
    "xreal/Assets/Icarus/Runtime/GazeTapInteractor.cs",
    "xreal/Assets/Icarus/Shaders/VolumetricHUD.shader",
    "xreal/Assets/Icarus/Editor/IcarusXrealBuild.cs",
]
missing = [p for p in required if not (root / p).exists()]
if missing:
    raise SystemExit("Missing XREAL files:\n" + "\n".join(missing))

settings = (root / "xreal/Assets/XR/Settings/XREALSettings.asset").read_text()
checks = {
    "3DoF": "InitialTrackingType: 1",
    "single-pass stereo": "StereoRendering: 2",
    "multi-resume": "SupportMultiResume: 1",
    "VISION device category": "SupportDevices: 02000000",
}
for label, needle in checks.items():
    if needle not in settings:
        raise SystemExit(f"XREAL {label} setting missing: {needle}")

manifest = (root / "xreal/Packages/manifest.json").read_text()
for needle in [
    '"com.xreal.xr": "file:../com.xreal.xr.tar.gz"',
    '"com.unity.inputsystem": "1.7.0"',
    '"com.unity.xr.management": "4.5.1"',
]:
    if needle not in manifest:
        raise SystemExit(f"XREAL package invariant missing: {needle}")

service = (root / required[0]).read_text()
for needle in [
    "InetAddress.getLoopbackAddress()",
    "Authorization:",
    "manager.snapshot()",
    "ACTION_STOP",
    'requestLine.startsWith("GET ")',
]:
    if needle not in service:
        raise SystemExit(f"Native XREAL telemetry bridge invariant missing: {needle}")
if "POST " in service:
    raise SystemExit("Spatial telemetry bridge must remain read-only")

snapshot = (root / "xreal/Assets/Icarus/Runtime/TelemetrySnapshot.cs").read_text()
for field in ["double? SpeedMph", "double? Rpm", "double? CoolantF", "double? FuelPercent", "double? Voltage"]:
    if field not in snapshot:
        raise SystemExit(f"Nullable live telemetry contract missing: {field}")

hud = (root / "xreal/Assets/Icarus/Runtime/SpatialHudView.cs").read_text()
if '"—"' not in hud or "43 MPH" in hud or "1850 RPM" in hud:
    raise SystemExit("HUD must render unavailable data as an em dash and contain no demo readings")

interactor = (root / "xreal/Assets/Icarus/Runtime/GazeTapInteractor.cs").read_text()
if "UnityEngine.InputSystem" not in interactor:
    raise SystemExit("XREAL gaze/tap interaction must use Unity's Input System")

build = (root / "xreal/Assets/Icarus/Editor/IcarusXrealBuild.cs").read_text()
for needle in ["activeInputHandler", "SetPreloadedAssets", "AndroidArchitecture.ARM64", "GraphicsDeviceType.OpenGLES3"]:
    if needle not in build:
        raise SystemExit(f"XREAL Unity build invariant missing: {needle}")

print("XREAL Spatial HUD source validation passed")
print("Note: binary Unity build still requires the official XREAL SDK tarball and a Unity build environment.")
