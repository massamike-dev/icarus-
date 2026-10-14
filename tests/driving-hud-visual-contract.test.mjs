import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const hud = readFileSync(new URL('../native/android/xreal/src/main/java/com/icarusalmighty/xreal/hud/CinematicDrivingHudView.kt', import.meta.url), 'utf8');
const activity = readFileSync(new URL('../native/android/app/src/main/java/com/icarusalmighty/app/driving/DrivingHudActivity.kt', import.meta.url), 'utf8');
const rect = name => {
  const match = hud.match(new RegExp(`val ${name} = RectF\\(([^)]+)\\)`));
  assert.ok(match, `${name} must have an inspectable design-space rectangle`);
  return match[1].split(',').map(value => Number(value.trim().replace(/f$/, '')));
};
const overlaps = (a, b) => a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1];

// These are source/geometry contracts, not Android compile or screenshot tests.
test('measured circular geometry and hit testing use the same aspect-preserved plane', () => {
  assert.match(hud, /scale = min\(width \/ WIDTH, height \/ HEIGHT\)/);
  assert.match(hud, /canvas\.translate\(offsetX, offsetY\)/);
  assert.match(hud, /canvas\.scale\(scale, scale\)/);
  assert.match(hud, /val x = \(event\.x - offsetX\) \/ scale/);
  assert.match(hud, /val y = \(event\.y - offsetY\) \/ scale/);
  assert.match(hud, /48f \* resources\.displayMetrics\.density \/ scale/);
  for (const [w, h] of [[1600, 900], [2400, 1080], [1280, 720], [1024, 768]]) {
    const s = Math.min(w / 1600, h / 900);
    const ox = (w - 1600 * s) / 2, oy = (h - 900 * s) / 2;
    assert.equal(((800 * s + ox) - ox) / s, 800);
    assert.equal(((450 * s + oy) - oy) / s, 450);
    assert.ok(ox >= 0 && oy >= 0);
  }
});

test('navigation is elevated and the normal center remains free of telemetry panels', () => {
  const safeCenter = [470, 340, 1208, 550];
  const panelNames = ['navigationHit', 'diagnosticsHit', 'enginePanel', 'voiceHit', 'exitHit', 'fuelPanel', 'scanPanel', 'statusPanel', 'pickupBounds'];
  for (const name of panelNames) {
    const bounds = rect(name);
    assert.ok(bounds[0] >= 0 && bounds[1] >= 0 && bounds[2] <= 1600 && bounds[3] <= 900, `${name} must fit the design plane`);
    assert.ok(!overlaps(bounds, safeCenter), `${name} must not cover the optical center`);
  }
  assert.ok(rect('navigationHit')[3] <= 248);
  assert.ok(rect('fuelPanel')[1] >= 550);
  assert.ok(rect('scanPanel')[1] >= 550);
  assert.ok(!overlaps(rect('scanPanel'), rect('enginePanel')));
  assert.ok(!overlaps(rect('enginePanel'), rect('statusPanel')));
  assert.ok(!overlaps(rect('statusPanel'), rect('voiceHit')));
});

test('restored artwork never manufactures a route or diagnostic health', () => {
  assert.doesNotMatch(hud, /drawRouteRibbon|drawDepthGrid|"ROAD CLEAR"|"SCANNING|"PASSED"|"72°F"|"CLEAR SKIES"|✓|✔/);
  assert.match(hud, /ILLUSTRATIVE VEHICLE MODEL/);
  assert.match(hud, /READ-ONLY TELEMETRY/);
  assert.match(hud, /state\.roadStatus/);
  assert.match(hud, /state\.roadDetail/);
  assert.match(hud, /listOf\(state\.rpm, state\.engineTempF, state\.engineLoadPercent, state\.batteryVolts\)\.count \{ it != null \}/);
  assert.match(hud, /state\.engineTempF\?\.let \{ temperature ->/);
});

test('missing measurements remain unknown and only measured values fill gauges', () => {
  assert.match(hud, /state\.speedMph\?\.toString\(\) \?: "—"/);
  assert.match(hud, /state\.rpm\?\.toString\(\) \?: "—"/);
  assert.match(hud, /state\.speedMph\?\.let \{ speed ->/);
  assert.match(hud, /if \(fraction == null \|\| !fraction\.isFinite\(\)\) return/);
  assert.match(hud, /FUEL DATA UNAVAILABLE/);
  assert.match(hud, /NO OBD CONNECTION/);
  assert.match(hud, /null -> "SPEED UNKNOWN"/);
});

test('long external guidance fits its own card and road names do not imply turns', () => {
  assert.match(hud, /TextUtils\.ellipsize/);
  assert.match(hud, /value\.replace\('\\n', ' '\)/);
  assert.match(hud, /LEFT_TURN\.containsMatchIn\(lower\)/);
  assert.match(hud, /RIGHT_TURN\.containsMatchIn\(lower\)/);
  assert.doesNotMatch(hud, /"left" in lower|"right" in lower|"continue" in lower|"straight" in lower/);
  assert.match(hud, /STRAIGHT_AHEAD\.containsMatchIn\(lower\)/);
  const regex = direction => {
    const source = hud.match(new RegExp(`val ${direction}_TURN = Regex\\(("[^\\n]+")\\)`))?.[1];
    return new RegExp(JSON.parse(source)); // Kotlin and JSON agree on these literal escapes.
  };
  assert.ok(regex('LEFT').test('turn left onto Lake Road'));
  assert.ok(regex('RIGHT').test('keep right at the fork'));
  assert.ok(!regex('LEFT').test('continue onto Left Bank Road'));
  assert.ok(!regex('RIGHT').test('Right Avenue'));
  const straight = new RegExp(JSON.parse(hud.match(/val STRAIGHT_AHEAD = Regex\(("[^\n]+")\)/)[1]));
  assert.ok(straight.test('continue straight onto Lake Road'));
  assert.ok(straight.test('straight ahead'));
  assert.ok(!straight.test('continue to the roundabout'));
  assert.ok(!straight.test('Straight Road'));
});

test('visual restoration preserves event-driven rendering and existing action boundaries', () => {
  assert.doesNotMatch(hud, /postInvalidateOnAnimation|ValueAnimator|Choreographer|Handler\(/);
  for (const action of ['EXIT', 'VOICE', 'SHOW_ENGINE', 'TOGGLE_DIAGNOSTICS', 'TOGGLE_NAVIGATION']) {
    assert.match(hud, new RegExp(`onAction\\(DrivingHudAction\\.${action}\\)`));
  }
  assert.match(activity, /if \(!parkedControlsAllowed\) parkedOnlyLockout\(\)/);
  assert.match(activity, /else if \(!latestState\.navigationAccessGranted\)/);
  assert.doesNotMatch(hud, /requestPermissions|startActivity|Bluetooth|GLES|XrealController|XRealController/);
});


test('detailed pickup is the unchanged transparent asset with a bounded one-time decode', () => {
  const png = readFileSync(new URL('../native/android/xreal/src/main/res/drawable-nodpi/icarus_hud_pickup.png', import.meta.url));
  assert.equal(png.subarray(1, 4).toString(), 'PNG');
  assert.equal(png.readUInt32BE(16), 1774);
  assert.equal(png.readUInt32BE(20), 887);
  assert.equal(png[24], 8, 'eight-bit channels');
  assert.equal(png[25], 6, 'RGBA preserves alpha for bounded HUD composition');
  assert.ok(png.length < 3 * 1024 * 1024);
  assert.equal(createHash('sha256').update(png).digest('hex'), '0f95b942440203d0b77ab0188d8ea60fe977d5e6ef250a1f25f8aef3364fcda9');
  assert.equal((hud.match(/BitmapFactory\.decodeResource/g) || []).length, 1);
  assert.match(hud, /private val pickupBitmap: Bitmap\? = runCatching/);
  assert.match(hud, /inScaled = false/);
  assert.match(hud, /inSampleSize = 2/);
  assert.match(hud, /inPreferredConfig = Bitmap\.Config\.ARGB_8888/);
  assert.doesNotMatch(hud.slice(hud.indexOf('override fun onDraw')), /BitmapFactory\.decodeResource/);
  assert.match(hud, /val fit = min\(pickupBounds\.width\(\) \/ bitmap\.width, pickupBounds\.height\(\) \/ bitmap\.height\)/);
  assert.match(hud, /if \(bitmap == null\) \{[\s\S]*?drawTruckWireframe\(canvas\)[\s\S]*?return/);
  assert.match(hud, /canvas\.drawBitmap\(bitmap, null, pickupDrawBounds, bitmapPaint\)/);
  assert.deepEqual(rect('pickupBounds'), [474, 565, 882, 769]);
});

test('HUD instruments are transparent and no opaque decorative underlay is composed', () => {
  assert.match(hud, /setBackgroundColor\(Color\.TRANSPARENT\)/);
  assert.doesNotMatch(hud, /canvas\.drawColor|drawBackground|drawRect\(0f, 0f, WIDTH, HEIGHT|peripheral bloom/);
  assert.match(activity, /window\.setBackgroundDrawable\(ColorDrawable\(Color\.BLACK\)\)/);
  assert.match(activity, /setBackgroundColor\(Color\.BLACK\)/);
  assert.match(activity, /window\.statusBarColor = Color\.BLACK/);
  assert.match(activity, /window\.navigationBarColor = Color\.BLACK/);
  assert.doesNotMatch(activity, /VolumetricHudSurface|volumeSurface|CameraX|CameraManager|PreviewView|Manifest\.permission\.CAMERA/);
  assert.match(hud, /PHONE: INSTRUMENTS ONLY \/ NO CAMERA/);
  const read = path => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');
  const manifest = read('native/android/app/src/main/AndroidManifest.xml');
  assert.match(manifest, /android:name="\.driving\.DrivingHudActivity"\s+android:theme="@style\/DrivingHudTheme"/);
  for (const config of ['values', 'values-v31']) {
    const theme = read(`native/android/app/src/main/res/${config}/styles.xml`).split('name="DrivingHudTheme"')[1];
    assert.match(theme, /name="android:windowBackground">@android:color\/black/);
    assert.match(theme, /name="android:colorBackground">@android:color\/black/);
    if (config === 'values-v31') assert.match(theme, /name="android:windowSplashScreenBackground">@android:color\/black/);
  }
});

test('actual XREAL vehicle route shares the cinematic scene and black optical host', () => {
  const read = path => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');
  const xr = read('native/android/xreal/src/main/java/com/icarusalmighty/xreal/XrealHudActivity.kt');
  const vehicle = xr.split('XrealMode.VEHICLE ->')[1].split('XrealMode.ASSISTANT ->')[0];
  assert.match(xr, /window\.setBackgroundDrawable\(ColorDrawable\(Color\.BLACK\)\)/);
  assert.match(xr, /root = FrameLayout\(this\)\.apply \{ setBackgroundColor\(Color\.BLACK\) \}/);
  assert.match(vehicle, /CinematicDrivingHudView\(/);
  assert.match(vehicle, /surface = DrivingHudSurface\.XREAL/);
  assert.match(vehicle, /vehicleSnapshot = state/);
  assert.match(vehicle, /renderVehicleSnapshot\(\)/);
  assert.match(xr, /vehicleView\?\.render\(fresh\.toDrivingScene\(\)\)/);
  assert.doesNotMatch(xr, /private fun drawVehicle|private fun drawTurnCue/);
  assert.match(hud, /val cx = 256f/);
  assert.match(hud, /canvas\.clipOutRect\(470f, 340f, 1208f, 550f\)/);
  assert.match(read('native/android/xreal/src/main/AndroidManifest.xml'), /@style\/XrealHudTheme/);
  for (const config of ['values', 'values-v31']) {
    assert.match(read(`native/android/xreal/src/main/res/${config}/styles.xml`), /name="android:windowBackground">@android:color\/black/);
  }
});
