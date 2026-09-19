import test from 'node:test';
import assert from 'node:assert/strict';
import {voiceDiagnosticRows} from '../src/voice-status.js';
const rows=wakeWord=>Object.fromEntries(voiceDiagnosticRows({privateTest:true,version:'1.6.7-test',wakeWord}));

test('an open microphone is not presented as verified wake detection',()=>{
  const result=rows({listenerState:'LISTENING',permissionGranted:true,recorderActive:true,audioReceived:true,signalReceived:false,detectionCount:0});
  assert.match(result['Microphone input'],/no sound/);
  assert.equal(result['Wake phrases detected'],'0');
  assert.equal(result['Voice stage'],'Waiting for wake phrase');
});
test('system-silenced input gives a specific recovery instruction',()=>{
  const result=rows({listenerState:'MICROPHONE_BLOCKED',permissionGranted:true,recorderActive:true,audioReceived:true,microphoneSilenced:true});
  assert.match(result['Microphone input'],/Android is silencing/);
  assert.match(result['Microphone input'],/other ICARUS listeners/);
});
test('command handoff does not report the stopped wake recorder as failure',()=>{
  const result=rows({listenerState:'CAPTURING',recorderActive:false,audioAgeMs:9000,lastTrigger:'talk_now',mediaVolumePercent:0,lastError:'Speech output failed.'});
  assert.match(result['Microphone input'],/command turn/);
  assert.equal(result['Last turn started by'],'Talk now');
  assert.match(result['Speech volume'],/Muted/);
  assert.equal(result['Last voice error'],'Speech output failed.');
});
test('legacy and missing diagnostics stay unknown instead of inventing audio health',()=>{
  assert.deepEqual(voiceDiagnosticRows(null),[]);
  assert.match(rows({listenerState:'LISTENING',permissionGranted:true})['Microphone input'],/unavailable/);
  assert.match(rows({listenerState:'AUDIO_STALLED',recorderActive:true,audioAgeMs:7000})['Microphone input'],/No recent/);
});
