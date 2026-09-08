from pathlib import Path
import sys

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
if '"com.xreal.xr": "file:../com.xreal.xr.tar.gz"' not in manifest:
    raise SystemExit("XREAL package must reference the official local SDK tarball")

service = (root / required[0]).read_text()
for needle in ["InetAddress.getLoopbackAddress()", "Authorization:", "obd.snapshot()", "ACTION_STOP"]:
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

print("XREAL Spatial HUD source validation passed")
print("Note: binary Unity build still requires the official XREAL SDK tarball and a Unity build environment.")
