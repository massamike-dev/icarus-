import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root = new URL('../native/android/app/src/main/java/com/icarusalmighty/app/', import.meta.url);
const service = readFileSync(new URL('WakeWordService.kt', root), 'utf8');
const engine = readFileSync(new URL('SherpaWakeWordEngine.kt', root), 'utf8');
const settings = readFileSync(new URL('WakeDetectionSettings.kt', root), 'utf8');
test('wake diagnostics identify command ownership separately from paused recorder', () => {
  assert.match(service, /put\("commandTurnActive", session != null\)/);
  assert.match(service, /put\("captureScope", if \(session != null\) "command_turn" else "wake_recorder"\)/);
});
test('wake startup and trigger timings use monotonic time without changing sensitivity', () => {
  assert.match(service, /wakeEngineStartMs = SystemClock\.elapsedRealtime\(\) - startingAt/);
  assert.match(service, /put\("wakeToTurnMs", wakeToTurnMs\)/);
  assert.match(engine, /lastDetectedElapsed = SystemClock\.elapsedRealtime\(\)/);
  assert.match(settings, /1\.8f \+ offset \* 0\.02f, 0\.30f - offset \* 0\.004f/);
});
