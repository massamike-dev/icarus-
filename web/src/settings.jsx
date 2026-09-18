import React, {useEffect, useRef, useState} from 'react';
import {ReleaseNotes} from './release-notes';
import {readDeviceStatus,subscribeNative} from './device-actions';

export class ScreenBoundary extends React.Component {
  state = {failed:false};
  static getDerivedStateFromError() { return {failed:true}; }
  render() {
    if (this.state.failed) return <section className="workspace" role="alert"><h1>This screen couldn't load.</h1><p>Your saved account data hasn't been changed. Choose another tab or reload ICARUS.</p><button className="secondary" onClick={()=>window.location.reload()}>Reload ICARUS</button></section>;
    return this.props.children;
  }
}

export function VoiceControls() {
  const [available,setAvailable]=useState(Boolean(window.IcarusNative?.postMessage));
  const [message,setMessage]=useState('');
  const [busy,setBusy]=useState(false);
  const [status,setStatus]=useState(null);
  const dialog=useRef(null), cancel=useRef(null), pending=useRef(null), timeout=useRef(null), active=useRef(false), statusRequest=useRef(0);
  useEffect(()=>{
    active.current=true;
    const refresh=()=>setAvailable(Boolean(window.IcarusNative?.postMessage));
    const timer=setInterval(refresh,1000);
    const result=raw=>{
      try {
        const value=typeof raw==='string'?JSON.parse(raw):raw;
        if(pending.current&&value?.requestId===pending.current) {
          clearTimeout(timeout.current);pending.current=null;setBusy(false);
          setMessage(value.ok===true&&!value.error?'Request received by Android. Check the ICARUS notification for listening status.':'Android could not confirm the request. Check app permissions and the ICARUS notification before retrying.');
        }
      } catch { /* Uncorrelated malformed responses cannot settle this request. */ }
    };
    const receiveStatus=raw=>{
      try {const value=typeof raw==='string'?JSON.parse(raw):raw;if(value?.wakeWord&&typeof value.wakeWord.listenerState==='string')setStatus(value);} catch { /* Ignore malformed status broadcasts. */ }
    };
    const removeResult=subscribeNative(window,'result',result),removeStatus=subscribeNative(window,'status',receiveStatus);
    return ()=>{active.current=false;statusRequest.current++;pending.current=null;clearInterval(timer);clearTimeout(timeout.current);removeResult();removeStatus();};
  },[]);
  const send=(action,args={})=>{
    if(busy||pending.current)return;
    if(!window.IcarusNative?.postMessage){setAvailable(false);setMessage('Open the installed Android app to use these controls.');return;}
    const requestId=crypto.randomUUID();pending.current=requestId;setBusy(true);setMessage('Waiting for Android…');
    timeout.current=setTimeout(()=>{pending.current=null;setBusy(false);setMessage('Android did not confirm the request. Check its notification, then try again.');},5000);
    try { window.IcarusNative.postMessage(JSON.stringify({action,arguments:args,requestId})); }
    catch {clearTimeout(timeout.current);pending.current=null;setBusy(false);setMessage('Android connection is unavailable. Reopen ICARUS and try again.');}
  };
  const check=async()=>{
    const revision=++statusRequest.current;
    setStatus(null);
    setMessage('Checking Android status…');
    const value=await readDeviceStatus();
    if(!active.current||revision!==statusRequest.current)return;
    if(value?.wakeWord&&typeof value.wakeWord.listenerState==='string'){setStatus(value);setMessage('Android status received.');}
    else setMessage('Android did not provide voice status. Reopen ICARUS and try again.');
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
    <ReleaseNotes/>
    <section className="settings-section" aria-labelledby="voice-settings"><h2 id="voice-settings">Voice & listening</h2><VoiceControls/></section>
    <section className="settings-section" aria-labelledby="account-settings"><h2 id="account-settings">Your account</h2><p>{user.name}</p><p className="account-email">{user.email}</p><button className="secondary" onClick={onSignOut}>Sign out</button></section>
    <section className="settings-section" aria-labelledby="privacy-settings"><h2 id="privacy-settings">Privacy & support</h2><div className="settings-links"><a href="/privacy-policy">Privacy policy</a><a href="mailto:wennigworks@gmail.com">Contact support</a><a href="/account-deletion">Account deletion options</a></div></section>
  </section>;
}
