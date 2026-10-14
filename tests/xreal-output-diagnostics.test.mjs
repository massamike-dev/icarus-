import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');
const diagnostics = read('native/android/xreal/src/main/java/com/icarusalmighty/xreal/XrealOutputDiagnostics.kt');
const session = read('native/android/xreal/src/main/java/com/icarusalmighty/xreal/XrealHudSession.kt');
const controller = read('native/android/xreal/src/main/java/com/icarusalmighty/xreal/XrealModeController.kt');
const wearables = read('native/android/app/src/main/java/com/icarusalmighty/app/XrealWearablesController.kt');
const manifest = read('native/android/xreal/src/main/AndroidManifest.xml');
const readme = read('native/android/xreal/README.md');

test('XREAL diagnostics observe accessible logical displays without permissions or hidden identity guesses', () => {
  assert.match(diagnostics, /getSystemService\(Context\.DISPLAY_SERVICE\)/);
  assert.match(diagnostics, /manager\.displays\.map/);
  for (const field of ['id', 'name', 'isDefault', 'isPresentation', 'isPrivate', 'flags', 'state', 'isValid']) {
    assert.match(diagnostics, new RegExp(`\\.put\\("${field}"`));
  }
  assert.match(diagnostics, /\.put\("type", "unknown"\)/);
  assert.doesNotMatch(diagnostics, /requestPermissions?\s*\(|UsbManager|CAMERA|getDeclaredMethod|Class\.forName|\.getType\(/);
  assert.doesNotMatch(manifest, /uses-permission/);
});

test('empty, secondary and failed display observations can never imply verified physical output', () => {
  assert.match(diagnostics, /\.put\("outputVerification", "unverified"\)/);
  assert.match(diagnostics, /\.put\("glassesConnectionVerified", false\)/);
  assert.match(diagnostics, /\.put\("actualActivityDisplayId", actualActivityDisplayId \?: JSONObject\.NULL\)/);
  assert.match(diagnostics, /displayEnumerationSucceeded = true/);
  assert.match(diagnostics, /diagnosticError = "display_enumeration_failed"/);
  assert.match(diagnostics, /diagnosticError = "display_service_unavailable"/);
  assert.doesNotMatch(diagnostics, /\.put\("(?:connected|disconnected)",/);
});

test('ordinary native launch does not gate on diagnostics or choose a secondary display', () => {
  const launch = controller.split('fun launch(')[1]?.split('/** Returns whether')[0];
  assert.ok(launch);
  assert.match(launch, /context\.startActivity\(intent\)/);
  assert.doesNotMatch(launch, /outputDiagnostics|accessibleLogicalDisplays|DisplayManager|actualDisplayId/);
  assert.doesNotMatch(controller, /setLaunchDisplayId|createVirtualDisplay|UsbManager/);
});

test('actual activity display is nullable lifecycle state rather than launch acceptance', () => {
  assert.match(session, /var actualDisplayId: Int\? = null/);
  assert.match(session, /displayId\?\.takeIf \{ activityOpen && it >= 0 \}/);
  const destroyed = session.split('fun activityDestroyed()')[1];
  assert.match(destroyed, /activityOpen = false/);
  assert.match(destroyed, /actualDisplayId = null/);
  assert.match(session, /if \(!activityOpen\) actualDisplayId = null/);
});

test('bridge status distinguishes launch acceptance from unverified OS-managed output', () => {
  assert.match(controller, /fun outputDiagnostics\(\): XrealOutputSnapshot/);
  assert.match(controller, /XrealOutputDiagnostics\.capture\(context, XrealHudActivity\.session\.actualDisplayId\)/);
  assert.match(wearables, /\.put\("outputDiagnostics",/);
  assert.match(wearables, /\.put\("outputVerification", "unverified"\)/);
  assert.match(wearables, /\.put\("glassesConnectionVerified", false\)/);
  assert.match(wearables, /os_managed_output_unverified/);
  assert.doesNotMatch(wearables, /\.put\("fallback",[^\n]*"xreal"/);
});

test('documentation separates native windows, Unity prerequisites and stationary-only acceptance', () => {
  assert.match(readme, /does not contain a Unity export/);
  assert.match(readme, /separate Unity companion/);
  assert.match(readme, /Empty or failed enumeration never blocks/);
  assert.match(readme, /prohibit using the glasses while operating a\s+vehicle/);
  assert.match(readme, /not public-road testing or a driving-safety certification/);
  assert.match(readme, /XREAL_Air2Pro_Quick_Start_Guide\.pdf/);
});
