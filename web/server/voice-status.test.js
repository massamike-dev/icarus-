import test from 'node:test';
import assert from 'node:assert/strict';
import {voiceDiagnosticRows} from '../src/voice-status.js';
const rows=wakeWord=>Object.fromEntries(voiceDiagnosticRows({privateTest:true,version:'1.6.7-test',wakeWord}));

test('an open microphone is not presented as verified wake detection',()=>{
  const result=rows({listenerState:'LISTENING',permissionGranted:true,recorderActive:true,audioReceived:true,signalReceived:false,detectionCount:0});
  assert.match(result['Wake recorder input'],/no sound/);
  assert.equal(result['Wake phrases detected'],'0');
  assert.equal(result['Voice stage'],'Waiting for wake phrase');
});
test('system-silenced input gives a specific recovery instruction',()=>{
  const result=rows({listenerState:'MICROPHONE_BLOCKED',permissionGranted:true,recorderActive:true,audioReceived:true,microphoneSilenced:true});
  assert.match(result['Wake recorder input'],/Android is silencing/);
  assert.match(result['Wake recorder input'],/other ICARUS listeners/);
});
test('command handoff does not report the stopped wake recorder as failure',()=>{
  const result=rows({listenerState:'CAPTURING',recorderActive:false,audioAgeMs:9000,lastTrigger:'talk_now',mediaVolumePercent:0,lastError:'Speech output failed.'});
  assert.match(result['Wake recorder input'],/command turn/);
  assert.equal(result['Last turn started by'],'Talk now');
  assert.match(result['Speech volume'],/Muted/);
  assert.equal(result['Last voice error'],'Speech output failed.');
});
test('legacy and missing diagnostics stay unknown instead of inventing audio health',()=>{
  assert.deepEqual(voiceDiagnosticRows(null),[]);
  assert.match(rows({listenerState:'LISTENING',permissionGranted:true})['Wake recorder input'],/unavailable/);
  assert.match(rows({listenerState:'AUDIO_STALLED',recorderActive:true,audioAgeMs:7000})['Wake recorder input'],/No recent/);
});

 test('paused wake samples never masquerade as a zero command microphone level',()=>{
  for(const listenerState of ['CAPTURING','AWAITING_CONFIRMATION','SPEAKING']){
    const result=rows({listenerState,audioLevel:0});
    assert.equal(result['Wake recorder level'],'Paused during command turn');
    assert.match(result['Command speech input'],/not reported/);
    assert.equal(result['Current input level'],undefined);
  }
  assert.equal(rows({listenerState:'LISTENING',audioLevel:.42})['Wake recorder level'],'42%');
  assert.equal(rows({listenerState:'LISTENING',commandTurnActive:true,audioLevel:0})['Wake recorder level'],'Paused during command turn');
});

test('app launch cancellation and finalization have distinct instructions',()=>{
  const cancel=rows({listenerState:'LISTENING_FOR_CANCEL',audioLevel:0});
  assert.equal(cancel['Voice stage'],'Listening for cancellation');
  assert.match(cancel['Cancel action'],/Say cancel/);
  assert.equal(cancel['Confirm or cancel'],undefined);
  assert.equal(cancel['Wake recorder level'],'Paused during command turn');
  const final=rows({listenerState:'FINALIZING_APP_LAUNCH'});
  assert.match(final['Voice stage'],/Finishing the cancellation check/);
  assert.equal(final['Confirm or cancel'],undefined);
  assert.equal(final['Cancel action'],undefined);
});
