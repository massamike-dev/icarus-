const stages={STARTING:'Starting wake listener',LISTENING:'Waiting for wake phrase',MICROPHONE_BLOCKED:'Microphone blocked by Android',AUDIO_STALLED:'Microphone input stalled',PREPARING_VOICE:'Preparing speech output',INITIALIZING_VOICE:'Starting speech engine',ACKNOWLEDGING:'Acknowledging wake',CAPTURING:'Listening for your command',AWAITING_CONFIRMATION:'Waiting for confirmation',LISTENING_FOR_CANCEL:'Listening for cancellation',FINALIZING_APP_LAUNCH:'Finishing the cancellation check',INTERPRETING:'Understanding your command',EXECUTING:'Performing the requested action',SPEAKING:'Speaking',ERROR:'Listener error',STOPPED:'Stopped'};
const turnStages=new Set(['PREPARING_VOICE','INITIALIZING_VOICE','ACKNOWLEDGING','CAPTURING','AWAITING_CONFIRMATION','LISTENING_FOR_CANCEL','FINALIZING_APP_LAUNCH','INTERPRETING','EXECUTING','SPEAKING']);

// Never equate an open recorder (or zero-valued samples) with a working wake detector.
export function voiceDiagnosticRows(status) {
  const wake=status?.wakeWord;
  if(!wake||typeof wake!=='object')return [];
  const rows=[['App',`${status.privateTest===true?'ICARUS Test':'ICARUS'}${typeof status.version==='string'?` ${status.version}`:''}`],['Voice stage',stages[wake.listenerState]||'Unknown']];
  const commandTurnActive=typeof wake.commandTurnActive==='boolean'?wake.commandTurnActive:turnStages.has(wake.listenerState);
  let microphone='Audio diagnostics unavailable in this installed build.';
  if(wake.permissionGranted===false)microphone='Microphone permission is not allowed.';
  else if(commandTurnActive)microphone='Paused normally while the command turn runs. This recorder does not measure command speech.';
  else if(wake.microphoneSilenced===true)microphone='Android is silencing the microphone. Stop other ICARUS listeners or recordings, then retry.';
  else if(wake.recorderActive===false)microphone='Wake microphone is not recording.';
  else if(wake.audioAgeMs>5000||wake.listenerState==='AUDIO_STALLED')microphone='No recent microphone samples. Stop listening, then enable it again.';
  else if(wake.audioReceived===false)microphone='Waiting for microphone samples.';
  else if(wake.audioReceived===true)microphone=wake.signalReceived===true?'Microphone samples and sound received.':'Samples are arriving, but no sound has been measured yet.';
  rows.push(['Wake recorder input',microphone]);
  if(commandTurnActive)rows.push(['Wake recorder level','Paused during command turn'],['Command speech input','Android speech recognition; input level is not reported.']);
  else if(Number.isFinite(wake.audioLevel))rows.push(['Wake recorder level',`${Math.round(Math.max(0,Math.min(1,wake.audioLevel))*100)}%`]);
  if(wake.listenerState==='AWAITING_CONFIRMATION')rows.push(['Confirm or cancel','Reply aloud with yes or cancel to the action ICARUS just read out.']);
  if(wake.listenerState==='LISTENING_FOR_CANCEL')rows.push(['Cancel action','Say cancel now to stop the pending app launch.']);
  if(Number.isInteger(wake.detectionCount)&&wake.detectionCount>=0)rows.push(['Wake phrases detected',String(wake.detectionCount)]);
  if(wake.lastTrigger==='talk_now')rows.push(['Last turn started by','Talk now']);
  else if(wake.lastTrigger==='wake_phrase')rows.push(['Last turn started by','Wake phrase']);
  if(Number.isFinite(wake.mediaVolumePercent))rows.push(['Speech volume',wake.mediaVolumePercent===0?'Muted — raise your phone’s media volume.':`${wake.mediaVolumePercent}%`]);
  if(typeof wake.lastError==='string'&&wake.lastError)rows.push(['Last voice error',wake.lastError]);
  return rows;
}
