# ICARUS Test 1.6.21 (build 52)

Install ICARUS-Test.apk. Package com.icarusalmighty.app.test uses the existing signer and stays separate from regular ICARUS. No production release, feed, server or Play deployment is changed.

Vehicle → Open live map & route line: explicitly start GPS, tap a destination while parked and calculate the cyan road route. Uses online OpenStreetMap tiles and FOSSGIS routing, with disclosure before Start.

Vehicle → Test motion fade: seated, hold your phone forward and tap Set forward. Rotate the PHONE; the sample graphic fades. Return forward and hold steady to restore it. This tests phone sensors, not XREAL head tracking. The Unity companion is not bundled. No road-aligned AR or vehicle-heading compensation is claimed.

The bundled interface starts locally. Existing separate test-cloud endpoint is retained; cloud login/memory availability depends on that service and is not verified by this native preview release. No production account endpoint is used.

Confirm installation alongside regular ICARUS; test portrait/landscape, sensor loss, background/return (requires Set forward again), and small jitter. If Android reports a signer conflict, do not uninstall or clear data; report the error. Hardware testing remains pending. Do not test the glasses prototype while driving.
