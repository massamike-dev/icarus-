import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const source = (name) => readFileSync(new URL(`../app/src/main/java/com/icarusalmighty/app/${name}.kt`, import.meta.url), 'utf8');
const session = source('HandsFreeSession');
const policy = source('HandsFreeActionPolicy');
const commands = source('HandsFreeCommands');

test('explicit app launches resolve before speaking, then capture after speech completion', () => {
  assert.match(session, /c.action == "open_app" && !proposed.modelInterpreted[\s\S]*?prepareAppLaunch\(proposed\)/);
  assert.match(session, /val app = resolveApp\(proposed.command.value\)[\s\S]*?if \(app == null\)[\s\S]*?return[\s\S]*?getLaunchIntentForPackage[\s\S]*?say\(HandsFreeAppLaunch.announcement\(app.loadLabel[\s\S]*?captureLaunchCancellation\(proposed, intent\)/);
  assert.match(session, /private fun completeSpeech[\s\S]*?id != expectedSpeechId[\s\S]*?callback\?\.invoke\(\)/);
  assert.match(policy, /fun announcement\(displayName: String\) = "Opening \$\{displayName.trim\(\)\}\."/);
});

test('launch timer starts on microphone readiness and drains recognition before execution', () => {
  const capture = session.slice(session.indexOf('private fun captureLaunchCancellation'), session.indexOf('private fun execute'));
  assert.match(capture, /onReadyForSpeech[\s\S]*?microphoneReady\(windowId\)[\s\S]*?postDelayed\(it, AppLaunchCancellationGate.WINDOW_MS\)/);
  assert.match(policy, /WINDOW_MS = 3000L/);
  assert.match(capture, /windowElapsed\(windowId\)[\s\S]*?FINALIZING_APP_LAUNCH[\s\S]*?awaitingSpeechResult\(windowId\)[\s\S]*?r.stopListening\(\)/);
  assert.match(capture, /silence && launchCancellation.finishWithSilence\(windowId\)/);
  assert.match(capture, /canLaunch && pending === proposed[\s\S]*?context.startActivity/);
  assert.match(capture, /onBeginningOfSpeech\(\) \{ launchCancellation.heardSpeech\(windowId\) \}/);
  assert.match(policy, /ready && elapsed && !speechObserved/);
});

test('ending or cancelling capture invalidates callbacks and removes launch timer', () => {
  assert.match(session, /private fun stopCapture\(\)[\s\S]*?captures.cancel\(\)[\s\S]*?launchCancellation.cancel\(\)[\s\S]*?launchDeadline\?\.let\(handler::removeCallbacks\)[\s\S]*?previous\?\.destroy\(\)/);
  assert.match(session, /fun close\(\)[\s\S]*?closed = true[\s\S]*?removeCallbacksAndMessages\(null\)[\s\S]*?stopCapture\(\)/);
  assert.match(session, /if \(closed \|\| !captures.consume\(captureId\)\) return/);
  assert.match(session, /RuntimeMode.isCurrent\(runtimeGeneration\) \|\| !WakeWordService.isEnabled\(context\)/);
});

test('Home is deterministic navigation, never force stop; stop listening overrides confirmations', () => {
  assert.match(commands, /t.startsWith\("close "\)[\s\S]*?Command\("close_app", normalized.drop\(6\).trim\(\)\)/);
  assert.match(session, /"go_home", "close_app" ->[\s\S]*?resolveApp\(c.value\) == null[\s\S]*?Intent\(Intent.ACTION_MAIN\).addCategory\(Intent.CATEGORY_HOME\)/);
  assert.doesNotMatch(session, /forceStopPackage|killBackgroundProcesses|am force-stop/);
  assert.match(session, /val command = HandsFreeCommands.parse\(text\)[\s\S]*?"stop_listening", "go_home", "close_app", "cancel", "unsupported_force_stop"[\s\S]*?cancelPending[\s\S]*?execute\(ProposedAction\(checkNotNull\(command\)\)\)[\s\S]*?val previous = pending/);
  assert.match(policy, /action in setOf\("make_call", "navigate_to", "set_timer"\)/);
  assert.match(policy, /modelInterpreted && action !in setOf\("get_battery", "cancel"\)/);
});

test('partial close speech cannot bypass final target resolution', () => {
  const capture = session.slice(session.indexOf('private fun captureLaunchCancellation'));
  const partial = capture.slice(capture.indexOf('override fun onPartialResults(p: Bundle?)'), capture.indexOf('override fun onResults(results: Bundle?)'));
  assert.match(partial, /action == "stop_listening"/);
  assert.doesNotMatch(partial, /setOf\("stop_listening", "go_home"\)/);
});
