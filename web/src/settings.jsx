import React, {useEffect, useRef, useState} from 'react';
import {getNativeTransport} from './native-transport.js';

export class ScreenBoundary extends React.Component {
  state = {failed:false};
  static getDerivedStateFromError() { return {failed:true}; }
  render() {
    if (this.state.failed) return <section className="workspace" role="alert"><h1>This screen couldn't load.</h1><p>Your saved account data hasn't been changed. Choose another tab or reload ICARUS.</p><button className="secondary" onClick={()=>window.location.reload()}>Reload ICARUS</button></section>;
    return this.props.children;
  }
}

export function VoiceControls() {
  const [available,setAvailable]=useState(Boolean(getNativeTransport()));
  const [message,setMessage]=useState('');
  const [busy,setBusy]=useState(false);
  const [status,setStatus]=useState(null);
  const dialog=useRef(null), cancel=useRef(null), pending=useRef(null), timeout=useRef(null);
  useEffect(()=>{
    const refresh=()=>setAvailable(Boolean(getNativeTransport()));
    const timer=setInterval(refresh,1000);
    const previousResult=window.ICARUS_NATIVE_RESULT, previousStatus=window.ICARUS_NATIVE_STATUS;
    const result=raw=>{
      try {
        const value=typeof raw==='string'?JSON.parse(raw):raw;
        if(value?.requestId===pending.current) {
          clearTimeout(timeout.current);pending.current=null;setBusy(false);
          setMessage(value.ok===false||value.error?'Android could not complete the request. Check app permissions and try again.':'Request received by Android. Check the ICARUS notification for listening status.');
        }
      } catch { setMessage('Android returned an unreadable response. Try again.'); }
      if(typeof previousResult==='function')previousResult(raw);
    };
    const receiveStatus=raw=>{
      try { setStatus(typeof raw==='string'?JSON.parse(raw):raw); } catch { setMessage('Could not read Android status. Try again.'); }
      if(typeof previousStatus==='function')previousStatus(raw);
    };
    window.ICARUS_NATIVE_RESULT=result;window.ICARUS_NATIVE_STATUS=receiveStatus;
    return ()=>{clearInterval(timer);clearTimeout(timeout.current);if(window.ICARUS_NATIVE_RESULT===result)window.ICARUS_NATIVE_RESULT=previousResult;if(window.ICARUS_NATIVE_STATUS===receiveStatus)window.ICARUS_NATIVE_STATUS=previousStatus;};
  },[]);
  const send=(action,args={})=>{
    if(busy)return;
    const transport=getNativeTransport();
    if(!transport){setAvailable(false);setMessage('Open the installed Android app to use these controls.');return;}
    const requestId=crypto.randomUUID();pending.current=requestId;setBusy(true);setMessage('Waiting for Android…');
    timeout.current=setTimeout(()=>{pending.current=null;setBusy(false);setMessage('Android did not confirm the request. Check its notification, then try again.');},5000);
    try { transport.postMessage(JSON.stringify({action,arguments:args,requestId})); }
    catch {clearTimeout(timeout.current);pending.current=null;setBusy(false);setMessage('Android connection is unavailable. Reopen ICARUS and try again.');}
  };
  const check=()=>{
    setStatus(null);
    try {getNativeTransport()?.postMessage(JSON.stringify({bridgeRequest:'status',requestId:crypto.randomUUID()}));setMessage('Status requested. If no status appears, reopen ICARUS and try again.');}
    catch {setMessage('Android connection is unavailable. Reopen ICARUS and try again.');}
  };
  return <div className="voice-controls">
    <p>{available?'Manage listening on this Android device.':'Voice controls are available in the installed Android app.'}</p>
    <div className="settings-actions">
      <button className="secondary" disabled={!available||busy} onClick={()=>{dialog.current.showModal();cancel.current.focus();}}>Enable hands-free</button>
      <button className="secondary" disabled={!available||busy} onClick={()=>send('wake_word',{enabled:false})}>Stop listening</button>
      <button className="secondary" disabled={!available||busy} onClick={()=>send('stop_speaking')}>Stop speaking</button>
      <button className="secondary" disabled={!available||busy} onClick={check}>Check voice status</button>
    </div>
    <p className="settings-status" role="status">{message}</p>
    {status&&<p>Listening: {status.wakeWord?.listenerState||'Unknown'}. Microphone: {status.wakeWord?.permissionGranted?'Allowed':'Not allowed'}.{status.wakeWord?.lastError&&` ${status.wakeWord.lastError}`}</p>}
    <dialog ref={dialog} className="settings-dialog" aria-labelledby="voice-consent-title" aria-describedby="voice-consent-description">
      <h2 id="voice-consent-title">Enable hands-free listening?</h2>
      <p id="voice-consent-description">ICARUS listens locally for “Hey ICARUS” in the background. Commands after wake may use Android speech recognition and your configured AI provider. You can stop listening here or from the ICARUS notification.</p>
      <div className="settings-actions"><button ref={cancel} className="secondary" onClick={()=>dialog.current.close()}>Cancel</button><button className="primary" onClick={()=>{dialog.current.close();send('wake_word',{enabled:true});}}>Enable listening</button></div>
    </dialog>
  </div>;
}

export function Settings({user,onSignOut}) {
  return <section className="workspace settings-workspace">
    <p className="eyebrow">ICARUS SETTINGS</p><h1>Settings</h1>
    <section className="settings-section" aria-labelledby="voice-settings"><h2 id="voice-settings">Voice & listening</h2><VoiceControls/></section>
    <section className="settings-section" aria-labelledby="account-settings"><h2 id="account-settings">Your account</h2><p>{user.name}</p><p className="account-email">{user.email}</p><button className="secondary" onClick={onSignOut}>Sign out</button></section>
    <section className="settings-section" aria-labelledby="privacy-settings"><h2 id="privacy-settings">Privacy & support</h2><div className="settings-links"><a href="/privacy-policy">Privacy policy</a><a href="mailto:wennigworks@gmail.com">Contact support</a><a href="/account-deletion">Account deletion options</a></div></section>
  </section>;
}
