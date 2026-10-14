import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';

const read = path => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');
const module = 'native/android/xreal/src/main/java/com/icarusalmighty/xreal/';
const app = 'native/android/app/src/main/java/com/icarusalmighty/app/';
const renderer = read(`${module}hud/CinematicDrivingHudView.kt`);
const scene = read(`${module}hud/CinematicDrivingScene.kt`);
const state = read(`${module}XrealHudState.kt`);
const intents = read(`${module}XrealHudIntents.kt`);
const activity = read(`${module}XrealHudActivity.kt`);
const controller = read(`${module}XrealModeController.kt`);
const wrapper = read(`${app}driving/DrivingHudView.kt`);
const mapper = read(`${app}XrealDrivingStateMapper.kt`);
const wearables = read(`${app}XrealWearablesController.kt`);

// Source contracts only. These do not compile Kotlin or capture Android output.
test('the library owns one renderer, scene and RGBA pickup without a reverse app dependency', () => {
  assert.match(renderer, /open class CinematicDrivingHudView/);
  assert.match(renderer, /import com\.icarusalmighty\.xreal\.R/);
  assert.match(renderer, /R\.drawable\.icarus_hud_pickup/);
  assert.match(read('native/android/app/build.gradle.kts'), /implementation\(project\(":xreal"\)\)/);
  assert.doesNotMatch(read('native/android/xreal/build.gradle.kts'), /project\(":app"\)/);
  const files = readdirSync(new URL(`../${module}`, import.meta.url), { recursive: true }).filter(name => name.endsWith('.kt'));
  for (const file of files) assert.doesNotMatch(read(`${module}${file}`), /import com\.icarusalmighty\.app/);
  assert.ok(existsSync(new URL('../native/android/xreal/src/main/res/drawable-nodpi/icarus_hud_pickup.png', import.meta.url)));
  assert.ok(!existsSync(new URL('../native/android/app/src/main/res/drawable-nodpi/icarus_hud_pickup.png', import.meta.url)), 'remove the old app asset so it cannot override the shared one');
  assert.doesNotMatch(scene, /import android\.|import com\.icarusalmighty\.app/);
  assert.match(wrapper, /: CinematicDrivingHudView\(/);
  assert.match(wrapper, /surface = DrivingHudSurface\.PHONE/);
  assert.match(wrapper, /fun render\(value: DrivingHudState\) = render\(value\.toCinematicScene\(\)\)/);
});

test('every scene value maps identically through phone and XREAL adapters', () => {
  const fields = [...scene.matchAll(/    val (\w+):/g)].map(match => match[1]);
  assert.equal(fields.length, 22);
  for (const field of fields) {
    assert.match(wrapper, new RegExp(`${field} = ${field},`), `phone maps ${field}`);
    const source = field === 'alertMessage' ? 'alertText' : field;
    assert.match(state, new RegExp(`${field} = ${source},`), `XREAL maps ${field}`);
    const destination = field === 'alertMessage' ? 'alertText' : field;
    assert.match(mapper, new RegExp(`${destination} = live\\.${field},`), `live snapshot maps ${field}`);
  }
});

test('typed Intent snapshots serialize and read all values and clear every absent nullable extra', () => {
  const fields = [...state.split(') {')[0].matchAll(/val (\w+): (\w+\??) = ([^,\n]+),/g)];
  assert.ok(fields.length >= 26);
  for (const [, field, type] of fields) {
    const key = field.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
    const extra = `XrealModeController.EXTRA_${key}`;
    assert.match(controller, new RegExp(`const val EXTRA_${key} = "com\\.icarusalmighty\\.extra\\.${key}"`));
    if (type.endsWith('?')) {
      assert.ok(intents.includes(`removeExtra(${extra})`), `${field} must clear an earlier Intent value`);
      assert.ok(intents.includes(`state.${field}?.let { putExtra(${extra}, it) }`));
    } else {
      assert.ok(intents.includes(`putExtra(${extra}, state.${field})`));
    }
    const readMethod = { 'String?': 'getStringExtra', String: 'getStringExtra', 'Int?': 'intExtraOrNull', 'Long?': 'longExtraOrNull', 'Double?': 'doubleExtraOrNull', 'Boolean?': 'booleanExtraOrNull', Boolean: 'getBooleanExtra' }[type];
    assert.ok(intents.includes(`${field} = ${readMethod}(${extra}`), `typed read for ${field}`);
  }
  assert.match(intents, /if \(hasExtra\(key\)\) getIntExtra\(key, 0\) else null/);
  assert.match(intents, /if \(hasExtra\(key\)\) getBooleanExtra\(key, false\) else null/);
  assert.match(intents, /getDoubleExtra\(key, Double\.NaN\)\.takeIf \{ it\.isFinite\(\) \}/);
  assert.match(controller, /Intent\(ACTION_UPDATE_XREAL\)[\s\S]*?\.setPackage\(context\.packageName\)[\s\S]*?\.putHudState\(state\)/);
  assert.match(controller, /\.putExtra\(EXTRA_MODE, mode\.name\)[\s\S]*?\.putHudState\(state\)/);
});

test('vehicle nulls never fall back to launch or external-update measurements', () => {
  assert.doesNotMatch(mapper, /\?:|fallback/);
  assert.match(mapper, /eta = null/);
  assert.match(wearables, /stateFromDriving\(live, hudMetadata, observedAt\)/);
  assert.match(wearables, /val snapshot = if \(activeController\.isVehicleMode\(\) \|\| telemetryLease != null\) \{\s*stateFromDriving\(DrivingTelemetrySession\.snapshot\(\), hudMetadata, lastVehicleObservedAtElapsedRealtimeMs\)/);
  assert.match(wearables, /activeController\.update\(snapshot\)/);
  assert.match(wearables, /args\.optDouble\(key\)\.takeIf \{ it\.isFinite\(\) \}/);
});

test('phone actions remain mapped while optical display has no dead touch controls', () => {
  const actions = ['TOGGLE_DIAGNOSTICS', 'TOGGLE_NAVIGATION', 'SHOW_ENGINE', 'VOICE', 'EXIT'];
  for (const action of actions) assert.match(wrapper, new RegExp(`DrivingHudAction\\.${action} -> HudAction\\.${action}`));
  assert.match(renderer, /if \(surface == DrivingHudSurface\.PHONE\) \{\s*glassPanel\(canvas, exitHit\)/);
  assert.match(renderer, /if \(surface != DrivingHudSurface\.PHONE\) return\s*glassPanel\(canvas, voiceHit/);
  assert.match(renderer, /if \(surface != DrivingHudSurface\.PHONE \|\| onAction == null\) return false/);
  assert.match(renderer, /if \(surface == DrivingHudSurface\.PHONE\) "Tap while parked" else "READ-ONLY VIEW"/);
  assert.match(renderer, /else "Enable access on phone when parked"/);
  assert.match(renderer, /STATIONARY EVALUATION ONLY/);
  assert.match(renderer, /OUTPUT UNVERIFIED/);
  assert.doesNotMatch(renderer, /requestPermissions|startActivity|CameraX|CameraManager/);
});

test('actual activity switches views for vehicle and assistant while retaining update and close contracts', () => {
  assert.match(activity, /renderHud\(intent\.toMode\(\), intent\.toHudState\(\)\)/);
  assert.match(activity, /renderHud\(hudMode, intent\.toHudState\(\)\)/);
  assert.match(activity, /root\.removeAllViews\(\)/);
  assert.match(activity, /root\.addView\(view, FrameLayout\.LayoutParams/);
  assert.match(activity, /XrealMode\.VEHICLE -> \{[\s\S]*?CinematicDrivingHudView\([\s\S]*?renderVehicleSnapshot\(\)/);
  assert.match(activity, /XrealMode\.ASSISTANT -> \{[\s\S]*?XrealAssistantView\(this\)[\s\S]*?assistant\.update\(state\)/);
  assert.match(activity, /ContextCompat\.RECEIVER_NOT_EXPORTED/);
  assert.match(activity, /currentActivity = WeakReference\(this\)/);
  assert.match(activity, /if \(session\.activityCreated\(hudMode\)\) finish\(\)/);
  assert.match(activity, /session\.requestClose\(\)/);
  assert.match(activity, /session\.activityDestroyed\(\)/);
  assert.match(activity, /unregisterReceiver\(stateReceiver\)/);
  assert.doesNotMatch(activity, /private fun drawVehicle/);
});


test('restored vehicle windows and orphaned updates cannot replay old telemetry', () => {
  assert.match(activity, /savedInstanceState != null && mode == XrealMode\.VEHICLE/);
  assert.match(activity, /XrealHudState\(sourceLabel = "WAITING FOR LIVE TELEMETRY"\)/);
  assert.match(activity, /renderHud\(mode, initialState\)/);
  assert.match(controller, /fun isVehicleMode\(\): Boolean = XrealHudActivity\.session\.activeMode == XrealMode\.VEHICLE/);
  assert.match(wearables, /activeController\.isVehicleMode\(\) \|\| telemetryLease != null/);
});

test('Back and host destruction release telemetry without a strong global Activity owner', () => {
  const session = read(`${module}XrealHudSession.kt`);
  const meta = read(`${app}MetaWearablesController.kt`);
  assert.match(session, /closedListener: WeakReference<\(\) -> Unit>\? = null/);
  assert.match(session, /val listener = closedListener\?\.get\(\)\s*closedListener = null/);
  assert.match(session, /runCatching \{ listener\?\.invoke\(\) \}/);
  assert.match(wearables, /private val onHudClosed: \(\) -> Unit = \{ releaseTelemetry\(\) \}/);
  assert.match(wearables, /activeController\.setOnClosedListener\(onHudClosed\)/);
  assert.match(meta, /fun close\(\) \{[\s\S]*?xrealController\?\.closeHud\(\)[\s\S]*?xrealController = null/);
});


test('vehicle transport expiry is bounded, monotonic and separate from speed-measurement expiry', () => {
  const freshness = read(`${module}XrealVehicleFreshness.kt`);
  assert.match(freshness, /MAX_SNAPSHOT_AGE_MS = 5_000L/);
  assert.match(freshness, /roadDetail = "REOPEN XREAL HUD ON PHONE"/);
  assert.match(freshness, /observedAt <= nowElapsedRealtimeMs/);
  assert.match(freshness, /deadline > nowElapsedRealtimeMs/);
  assert.match(freshness, /deadline - observedAt <= MAX_SNAPSHOT_AGE_MS/);
  assert.match(freshness, /speedDeadline <= nowElapsedRealtimeMs/);
  assert.match(freshness, /state\.copy\(speedMph = null, vehicleMoving = null, speedValidUntilElapsedRealtimeMs = null\)/);
  assert.match(mapper, /speedValidUntilElapsedRealtimeMs = live\.speedValidUntilElapsedRealtimeMs/);
  assert.match(activity, /XrealVehicleFreshness\.sanitize\(vehicleSnapshot, now\)/);
  assert.match(activity, /vehicleView\?\.render\(fresh\.toDrivingScene\(\)\)/);
  assert.match(activity, /postDelayed\(vehicleExpiry, \(deadline - now\)\.coerceAtLeast\(1L\)\)/);
  assert.match(activity, /removeCallbacks\(vehicleExpiry\)/);
  assert.doesNotMatch(activity, /postInvalidateOnAnimation|ValueAnimator|Choreographer/);
});

test('only source callbacks renew transport liveness; active-session heartbeat revalidates unchanged guidance', () => {
  const lease = read(`${app}driving/DrivingTelemetrySession.kt`);
  const callback = wearables.split('telemetryLease = DrivingTelemetrySession.acquire')[1].split('} else {')[0];
  assert.match(callback, /val observedAt = SystemClock\.elapsedRealtime\(\)/);
  assert.match(callback, /lastVehicleObservedAtElapsedRealtimeMs = observedAt/);
  const update = wearables.split('fun update(args: JSONObject)')[1].split('private fun stateFrom')[0];
  assert.doesNotMatch(update, /SystemClock|lastVehicleObservedAtElapsedRealtimeMs =|refreshSnapshot/);
  assert.match(update, /stateFromDriving\(DrivingTelemetrySession\.snapshot\(\), hudMetadata, lastVehicleObservedAtElapsedRealtimeMs\)/);
  assert.match(wearables, /lease\.refreshSnapshot\(\)/);
  assert.match(wearables, /controller\?\.isOpen\(\) != true && controller\?\.isLaunchPending\(\) != true/);
  assert.match(wearables, /mainHandler\.postDelayed\(this, 1_000L\)/);
  assert.match(wearables, /mainHandler\.removeCallbacks\(telemetryHeartbeat\)/);
  assert.match(lease, /if \(!closed\) DrivingTelemetrySession\.controller\?\.refreshSnapshot\(\)/);
});
