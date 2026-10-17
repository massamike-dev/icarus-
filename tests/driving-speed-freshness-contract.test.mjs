import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');
const base = 'native/android/app/src/main/java/com/icarusalmighty/app/driving/';
const controller = read(`${base}DrivingTelemetryController.kt`);
const policy = read(`${base}DrivingSpeedFreshness.kt`);
const state = read(`${base}DrivingHudState.kt`);
const session = read(`${base}DrivingTelemetrySession.kt`);
const unitTests = read('native/android/app/src/test/java/com/icarusalmighty/app/DrivingSpeedFreshnessTest.kt');

// These check source wiring only. They do not execute Kotlin, Android callbacks, or hardware.
test('freshness uses bounded monotonic measurement age and rejects invalid numbers', () => {
  assert.match(policy, /MAX_AGE_MS = 3_000L/);
  assert.match(policy, /EXPIRY_CHECK_INTERVAL_MS = 500L/);
  assert.match(policy, /nowElapsedRealtimeMs >= observedAtElapsedRealtimeMs/);
  assert.match(policy, /nowElapsedRealtimeMs - observedAtElapsedRealtimeMs < MAX_AGE_MS/);
  assert.match(policy, /observedAtElapsedRealtimeMs > 0L/);
  assert.match(policy, /it\.isFinite\(\) && it >= 0\.0/);
  assert.doesNotMatch(policy + controller, /System\.currentTimeMillis\(/);
});

test('parked permission checks a deadline at tap time as well as a confirmed zero', () => {
  assert.match(state, /speedValidUntilElapsedRealtimeMs: Long\? = null/);
  assert.match(state, /parkedControlsAllowedAt\(SystemClock\.elapsedRealtime\(\)\)/);
  assert.match(state, /val validUntil = speedValidUntilElapsedRealtimeMs \?: return false/);
  assert.match(state, /vehicleMoving == false && speedMph == 0/);
  assert.match(state, /nowElapsedRealtimeMs < validUntil/);
  assert.doesNotMatch(state, /get\(\) = vehicleMoving == false/);
});

test('cached and live GPS use original elapsed measurement time, with no missing-speed retention', () => {
  assert.match(controller, /getLastKnownLocation\(provider\)\?\.let\(::applyLocation\)/);
  assert.match(controller, /location\.elapsedRealtimeNanos \/ 1_000_000L/);
  assert.match(controller, /location\.takeIf \{ it\.hasSpeed\(\) \}/);
  assert.match(controller, /recordGps\(speed, observedAt, now, location\.provider/);
  assert.match(controller, /requestLocationUpdates\(provider, 750L, 0f/);
  assert.doesNotMatch(controller, /gpsSpeedMph|gpsMoving|speed \?: gps/);
  assert.match(policy, /gpsInputs\.getOrPut\(provider\)/);
});

test('provider and permission loss invalidate evidence including previously queued observations', () => {
  assert.match(controller, /onProviderDisabled\(provider: String\)[\s\S]*?clearGpsEvidence\(\)/);
  assert.match(controller, /status != LocationProvider\.AVAILABLE[\s\S]*?clearGpsEvidence\(\)/);
  assert.match(controller, /if \(!hasLocationPermission\(\)\) clearGpsEvidence\(\)/);
  assert.match(controller, /if \(!hasBluetoothPermission\(\)\) clearObdEvidence\(\)/);
  assert.match(controller, /speedFreshness\.clearGps\(SystemClock\.elapsedRealtime\(\)\)/);
  assert.match(controller, /speedFreshness\.clearObd\(SystemClock\.elapsedRealtime\(\)\)/);
  assert.match(policy, /newestObservationMs = maxOf\(input\.newestObservationMs, invalidatedAtMs\)/);
  assert.doesNotMatch(controller, /requestPermissions|RequestMultiplePermissions|ACCESS_BACKGROUND_LOCATION/);
});

test('positive raw movement veto survives missing data, conflicts, and independent location providers', () => {
  assert.match(policy, /movementVeto: Sample\? = null/);
  assert.match(policy, /speedMph > 0\.0 \|\| recentMotion/);
  assert.match(policy, /input\.sample\?\.let \{ sample ->[\s\S]*?input\.movementVeto = sample\.takeIf/);
  const record = policy.split('private fun record(')[1].split('companion object')[0];
  assert.ok(record.indexOf('input.movementVeto = Sample') < record.indexOf('if (observedAtMs < input.newestObservationMs) return'));
  assert.match(policy, /gpsInputs\.values\.map \{ Source\.GPS to it \}/);
  const clear = policy.split('private fun clear(')[1].split('fun current(')[0];
  assert.doesNotMatch(clear, /movementVeto\s*=/);
});

test('watchdog expires telemetry without an OBD or location callback and stops with the controller', () => {
  const watchdog = controller.split('private val freshnessWatchdog')[1].split('fun start()')[0];
  assert.match(watchdog, /if \(!running\.get\(\)\) return/);
  assert.match(watchdog, /refreshLocationRegistration\(\)/);
  assert.match(watchdog, /mutate \{ this \}/);
  assert.match(watchdog, /postDelayed\(this, DrivingSpeedFreshness\.EXPIRY_CHECK_INTERVAL_MS\)/);
  assert.match(controller, /mainHandler\.post\(freshnessWatchdog\)/);
  assert.match(controller, /mainHandler\.removeCallbacksAndMessages\(null\)/);
  assert.match(controller, /speedValidUntilElapsedRealtimeMs = reading\.validUntilElapsedRealtimeMs/);
  for (const name of ['rpm', 'engineTempF', 'fuelPercent', 'engineLoadPercent', 'batteryVolts']) {
    assert.ok(controller.includes(`${name} = value.${name}.takeIf { obdFresh }`));
  }
});

test('OBD batch start, not receipt or wall timestamp, bounds speed freshness', () => {
  assert.match(controller, /val snapshotStartedAt = SystemClock\.elapsedRealtime\(\)\s+val snap = obd\.snapshot\(\)/);
  assert.match(controller, /recordObd\(obdSpeed, snapshotStartedAt, SystemClock\.elapsedRealtime\(\)\)/);
  assert.doesNotMatch(controller, /getLong\("timestamp"\)|optLong\("timestamp"\)/);
  assert.match(controller, /val obdFresh = obdTransportConnected && DrivingSpeedFreshness\.isFresh\(obdSnapshotStartedAtMs, now\)/);
});

test('main-thread delivery rechecks evidence and drops late results after stop', () => {
  assert.match(controller, /private fun mutate\(\s+publishUnchanged: Boolean = false,\s+block: DrivingHudState\.\(\) -> DrivingHudState,\s+\) = runOnMain/);
  assert.match(controller, /withFreshTelemetry\(withFreshTelemetry\(state\)\.block\(\)\)/);
  assert.match(controller, /if \(next != state \|\| publishUnchanged\)/);
  assert.match(controller, /else mainHandler\.post \{ if \(running\.get\(\)\) block\(\) \}/);
  assert.doesNotMatch(controller, /mainHandler\.post \{ onState\(value\) \}/);
});

test('native regression sources cover loss, no callbacks, exact deadline, raw creep, and arrival order', () => {
  for (const name of [
    'absentEvidenceCannotUnlockControls', 'freshExactZeroExpiresWithoutAnotherCallback',
    'cachedZeroUsesMeasurementAgeNotReceiptAge', 'missingSpeedReplacesOldZeroWithUnknown',
    'invalidSpeedNeverBecomesZeroOrRetainsPreviousZero', 'invalidFutureZeroAndOverflowTimestampsFailClosed',
    'positiveUnroundedSpeedCannotUnlockEvenWhenDisplayRoundsToZero',
    'freshMovementOverridesConflictingZeroInEitherSource',
    'providerOrPermissionLossRejectsCachedAndInFlightGpsSamples',
    'disconnectRejectsInFlightObdBatchAndUsesOnlyFreshGps',
    'slowObdBatchCannotReTimestampEarlyZeroAtCompletion', 'stuckObdWorkerExpiresAndGpsFallbackAlsoExpires',
    'staleUiSnapshotChecksDeadlineAtTapTimeEvenBeforeWatchdogRuns',
    'missingInvalidOrConflictingGpsCannotEraseFreshMovementVeto',
    'zeroThenPositiveAtSameTimestampCannotExposeAnotherZero',
    'networkProviderCannotClearGpsProvidersMovingEvidence',
    'delayedFreshMotionStillVetoesZeroAfterNewerMissingPacket',
  ]) assert.ok(unitTests.includes(`@Test fun ${name}()`), name);
  assert.doesNotMatch(unitTests, /\.parkedControlsAllowed\b(?!At)/, 'JVM tests must not invoke Android SystemClock');
});


test('explicit transport refresh revalidates without renewing sensor evidence', () => {
  assert.match(session, /fun refreshSnapshot\(\) \{\s+if \(!closed\) DrivingTelemetrySession\.controller\?\.refreshSnapshot\(\)/);
  const refresh = controller.split('fun refreshSnapshot()')[1].split('fun toggleDiagnostics()')[0];
  assert.match(refresh, /runOnMain/);
  assert.match(refresh, /refreshLocationRegistration\(\)/);
  assert.match(refresh, /mutate\(publishUnchanged = true\) \{ this \}/);
  assert.doesNotMatch(refresh, /recordGps|recordObd|obdSnapshotStartedAtMs\s*=|speedValidUntilElapsedRealtimeMs\s*=/);
  assert.match(controller, /withFreshTelemetry\(withFreshTelemetry\(state\)\.block\(\)\)/);
});
