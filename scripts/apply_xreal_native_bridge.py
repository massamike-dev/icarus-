from pathlib import Path

path = Path("native/android/app/src/main/java/com/icarusalmighty/app/MainActivity.kt")
text = path.read_text()

replacements = [
    (
        'import com.icarusalmighty.app.driving.DrivingHudActivity\n',
        'import com.icarusalmighty.app.driving.DrivingHudActivity\nimport com.icarusalmighty.app.spatial.SpatialTelemetryService\n',
    ),
    (
        '                "open_driving_hud" -> openDrivingHud(requestId, args)\n',
        '                "open_driving_hud" -> openDrivingHud(requestId, args)\n                "xreal_status" -> xrealStatus(requestId)\n                "open_xreal_hud" -> openXrealHud(requestId, args)\n                "close_xreal_hud" -> closeXrealHud(requestId)\n',
    ),
    (
        '''    private fun resolvePhone(args: JSONObject): String? {''',
        '''    private fun xrealStatus(requestId: String?): String {
        val launch = Intent(Intent.ACTION_VIEW, Uri.parse("icarus-spatial://launch"))
            .setPackage(SpatialTelemetryService.COMPANION_PACKAGE)
        val installed = context.packageManager.resolveActivity(launch, 0) != null
        return ok(
            requestId,
            JSONObject()
                .put("installed", installed)
                .put("package", SpatialTelemetryService.COMPANION_PACKAGE)
                .put("host", "beam_pro")
                .put("tracking", "3dof")
                .put("liveTelemetryOnly", true)
        )
    }

    private fun openXrealHud(requestId: String?, args: JSONObject): String {
        if (Build.VERSION.SDK_INT >= 31) {
            requirePermission(Manifest.permission.BLUETOOTH_CONNECT)
            requirePermission(Manifest.permission.BLUETOOTH_SCAN)
        }
        val address = firstString(args, "obdAddress", "address").trim()
        if (address.isBlank()) return error(requestId, "missing_device_address", "Select a live OBD adapter before opening the XREAL HUD.")

        val token = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        val launchUri = Uri.Builder()
            .scheme("icarus-spatial")
            .authority("launch")
            .appendQueryParameter("port", SpatialTelemetryService.PORT.toString())
            .appendQueryParameter("token", token)
            .build()
        val launch = Intent(Intent.ACTION_VIEW, launchUri)
            .setPackage(SpatialTelemetryService.COMPANION_PACKAGE)

        if (context.packageManager.resolveActivity(launch, 0) == null) {
            return error(
                requestId,
                "xreal_companion_not_installed",
                "Install the ICARUS XREAL companion on Beam Pro before opening the Spatial HUD."
            )
        }

        // The XREAL bridge becomes the only OBD socket owner while the companion is active.
        obd.disconnect()
        context.stopService(
            Intent(context, SpatialTelemetryService::class.java)
                .setAction(SpatialTelemetryService.ACTION_STOP)
        )
        val service = Intent(context, SpatialTelemetryService::class.java)
            .setAction(SpatialTelemetryService.ACTION_START)
            .putExtra(SpatialTelemetryService.EXTRA_OBD_ADDRESS, address)
            .putExtra(SpatialTelemetryService.EXTRA_TOKEN, token)
        ContextCompat.startForegroundService(context, service)

        return try {
            activity.runOnUiThread { activity.startActivity(launch) }
            ok(
                requestId,
                JSONObject()
                    .put("opened", true)
                    .put("host", "beam_pro")
                    .put("tracking", "3dof")
                    .put("liveTelemetryRequired", true)
                    .put("port", SpatialTelemetryService.PORT)
            )
        } catch (e: Exception) {
            context.stopService(
                Intent(context, SpatialTelemetryService::class.java)
                    .setAction(SpatialTelemetryService.ACTION_STOP)
            )
            error(requestId, "xreal_launch_failed", e.message)
        }
    }

    private fun closeXrealHud(requestId: String?): String {
        context.stopService(
            Intent(context, SpatialTelemetryService::class.java)
                .setAction(SpatialTelemetryService.ACTION_STOP)
        )
        return ok(requestId, JSONObject().put("telemetryStopped", true))
    }

    private fun resolvePhone(args: JSONObject): String? {''',
    ),
    (
        '            "obd_disconnect", "open_driving_hud", "find_videos", "compose_video_montage", "native_tts",',
        '            "obd_disconnect", "open_driving_hud", "xreal_status", "open_xreal_hud", "close_xreal_hud", "find_videos", "compose_video_montage", "native_tts",',
    ),
]

for old, new in replacements:
    if new in text:
        continue
    if old not in text:
        raise SystemExit(f"Expected XREAL bridge anchor not found: {old[:160]!r}")
    text = text.replace(old, new, 1)

path.write_text(text)
