from pathlib import Path

path = Path("native/android/app/src/main/java/com/icarusalmighty/app/MainActivity.kt")
text = path.read_text()

replacements = [
    (
        'import com.icarusalmighty.app.update.PlayUpdateManager\n',
        'import com.icarusalmighty.app.update.PlayUpdateManager\nimport com.icarusalmighty.app.driving.DrivingHudActivity\n',
    ),
    (
        '                "obd_disconnect" -> obdDisconnect(requestId)\n',
        '                "obd_disconnect" -> obdDisconnect(requestId)\n                "open_driving_hud" -> openDrivingHud(requestId, args)\n',
    ),
    (
        '    private fun obdDisconnect(requestId: String?): String {\n        obd.disconnect()\n        return ok(requestId, JSONObject().put("connected", false))\n    }\n\n    private fun resolvePhone(args: JSONObject): String? {',
        '    private fun obdDisconnect(requestId: String?): String {\n        obd.disconnect()\n        return ok(requestId, JSONObject().put("connected", false))\n    }\n\n    private fun openDrivingHud(requestId: String?, args: JSONObject): String {\n        val address = firstString(args, "obdAddress", "address").trim()\n        val intent = Intent(context, DrivingHudActivity::class.java).apply {\n            if (address.isNotBlank()) putExtra(DrivingHudActivity.EXTRA_OBD_ADDRESS, address)\n        }\n        activity.runOnUiThread { activity.startActivity(intent) }\n        return ok(\n            requestId,\n            JSONObject()\n                .put("opened", true)\n                .put("liveTelemetryRequired", true)\n                .put("obdAddressProvided", address.isNotBlank())\n        )\n    }\n\n    private fun resolvePhone(args: JSONObject): String? {',
    ),
    (
        '            "obd_disconnect", "find_videos", "compose_video_montage", "native_tts",',
        '            "obd_disconnect", "open_driving_hud", "find_videos", "compose_video_montage", "native_tts",',
    ),
]

for old, new in replacements:
    if new in text:
        continue
    if old not in text:
        raise SystemExit(f"Expected bridge anchor not found: {old[:120]!r}")
    text = text.replace(old, new, 1)

path.write_text(text)
