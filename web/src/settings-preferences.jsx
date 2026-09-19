import React,{useEffect,useRef,useState} from 'react';
import {getNativeTransport} from './native-transport.js';
import {readDeviceStatus,subscribeNative} from './device-actions';
import {modelStatusText,requestNativeSetting,sameVoice,validVoiceSettings,voiceDraft,VOICE_PRESETS} from './native-settings.js';
import './settings-preferences.css';

const DRAFT_KEY='icarus.voice-settings-draft';
function readDraft(){try{const value=JSON.parse(sessionStorage.getItem(DRAFT_KEY));return validVoiceSettings({...value,supported:true})?value:null;}catch{return null;}}
function storeDraft(value){try{value?sessionStorage.setItem(DRAFT_KEY,JSON.stringify(value)):sessionStorage.removeItem(DRAFT_KEY);}catch{/* Device settings still work when browser storage is unavailable. */}}
function useDeviceSettings(){
  const [status,setStatus]=useState(null),[checked,setChecked]=useState(false);
  useEffect(()=>{
    let active=true;
    readDeviceStatus().then(value=>{if(active){setStatus(value);setChecked(true);}});
    const remove=subscribeNative(window,'status',raw=>{try{const value=typeof raw==='string'?JSON.parse(raw):raw;if(value?.actionProtocolVersion===1){setStatus(value);setChecked(true);}}catch{/* Ignore malformed status. */}});
    return ()=>{active=false;remove();};
  },[]);
  return {status,checked,native:Boolean(getNativeTransport())};
}
function DeviceNotice({native,checked,supported}){
  if(!native)return <p>These preferences belong to your Android device. Open ICARUS Test on your phone to change them.</p>;
  if(!checked)return <p role="status">Checking which settings this Android build supports…</p>;
  if(!supported)return <p>Install the updated ICARUS Test build to restore these settings. Your current build has not confirmed support.</p>;
  return null;
}

export function VoicePreferences({device}){
  const supported=device.status?.voiceSettings?.supported===true;
  const [catalog,setCatalog]=useState(null),[saved,setSaved]=useState(null),[draft,setDraft]=useState(null),[busy,setBusy]=useState(''),[feedback,setFeedback]=useState({text:'',error:false});
  const active=useRef(false),inFlight=useRef(false);
  const dirty=Boolean(saved&&draft&&!sameVoice(saved,draft));
  useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
  const run=async(action,args={},success)=>{
    if(inFlight.current||!supported)return;
    inFlight.current=true;setBusy(action);setFeedback({text:action==='get_voice_settings'?'Loading voices from Android…':action==='preview_voice'?'Playing your saved voice…':'Waiting for Android…',error:false});
    try{const value=await requestNativeSetting(action,args);if(active.current)success(value);}
    catch(error){if(active.current)setFeedback({text:error.message,error:true});}
    finally{inFlight.current=false;if(active.current)setBusy('');}
  };
  const load=()=>run('get_voice_settings',{},value=>{
    if(!validVoiceSettings(value)||!Array.isArray(value.voices))throw Error('Android returned incomplete voice settings. Reopen Settings and try again.');
    setCatalog(value);setSaved(voiceDraft(value));setDraft(readDraft()||voiceDraft(value));setFeedback({text:value.message||'Voice settings loaded from this phone.',error:false});
  });
  useEffect(()=>{if(supported)load();},[supported]);
  useEffect(()=>{if(!draft||!saved)return;storeDraft(dirty?draft:null);},[draft,saved,dirty]);
  useEffect(()=>{if(!dirty)return;const warn=event=>{event.preventDefault();event.returnValue='';};window.addEventListener('beforeunload',warn);return()=>window.removeEventListener('beforeunload',warn);},[dirty]);
  const change=(field,value)=>{setDraft(current=>({...current,[field]:value,...(['rate','pitch'].includes(field)?{profile:'custom'}:{})}));setFeedback({text:'Changes are not saved yet.',error:false});};
  const save=event=>{
    event.preventDefault();
    if(!draft||!validVoiceSettings({...draft,supported:true})){setFeedback({text:'Choose a voice profile and values between 0.50 and 1.50.',error:true});return;}
    run('set_voice_settings',draft,value=>{
      if(value.saved!==true||!validVoiceSettings(value)||!sameVoice(value,draft))throw Error('Android did not confirm these exact settings. Your changes are still here; reload to check before saving again.');
      setSaved(voiceDraft(value));setDraft(voiceDraft(value));storeDraft(null);setFeedback({text:'Voice settings saved on this phone. Preview to hear them.',error:false});
    });
  };
  const voiceList=Array.isArray(catalog?.voices)?catalog.voices.filter(value=>typeof value.name==='string'&&typeof value.label==='string'):[];
  return <section className="settings-section settings-preferences" aria-labelledby="voice-personality-title">
    <h2 id="voice-personality-title">ICARUS voice</h2>
    <p>A deep, warm and unhurried delivery. Choose a voice installed on your phone, then save and preview it.</p>
    <DeviceNotice {...device} supported={supported}/>
    {supported&&<>
      {draft&&<form noValidate onSubmit={save} aria-busy={Boolean(busy)}>
        <div className="settings-field"><label htmlFor="voice-profile">Voice style</label><select id="voice-profile" value={draft.profile} disabled={Boolean(busy)} onChange={event=>{const profile=event.target.value;setDraft(current=>({...current,profile,...VOICE_PRESETS[profile]}));setFeedback({text:'Changes are not saved yet.',error:false});}}><option value="deep_warm">Deep & warm</option><option value="standard">Standard</option><option value="custom">Custom</option></select></div>
        <div className="settings-field"><label htmlFor="voice-name">Android voice</label><select id="voice-name" value={draft.voiceName} disabled={Boolean(busy)} aria-describedby="voice-choice-help" onChange={event=>change('voiceName',event.target.value)}><option value="">Automatic English voice</option>{draft.voiceName&&!voiceList.some(v=>v.name===draft.voiceName)&&<option value={draft.voiceName}>Previously selected voice (not currently available)</option>}{voiceList.map(voice=><option key={voice.name} value={voice.name}>{voice.label}{voice.networkRequired?' — needs internet':''}</option>)}</select><small id="voice-choice-help">Names come from your speech engine. Preview each voice to find the tone you prefer.</small></div>
        <div className="settings-tuning"><div className="settings-field"><label htmlFor="voice-rate">Speaking speed <output htmlFor="voice-rate">{draft.rate.toFixed(2)}×</output></label><input id="voice-rate" type="range" min="0.5" max="1.5" step="0.01" value={draft.rate} disabled={Boolean(busy)} onChange={event=>change('rate',Number(event.target.value))}/></div><div className="settings-field"><label htmlFor="voice-pitch">Pitch <output htmlFor="voice-pitch">{draft.pitch.toFixed(2)}×</output></label><input id="voice-pitch" type="range" min="0.5" max="1.5" step="0.01" value={draft.pitch} disabled={Boolean(busy)} onChange={event=>change('pitch',Number(event.target.value))}/></div></div>
        <div className="settings-actions"><button className="primary" type="submit" disabled={Boolean(busy)||!dirty}>Save voice</button><button className="secondary" type="button" disabled={Boolean(busy)||dirty||catalog?.available!==true} onClick={()=>run('preview_voice',{},()=>setFeedback({text:'Voice preview finished.',error:false}))}>Preview saved voice</button><button className="secondary" type="button" disabled={!dirty||Boolean(busy)} onClick={()=>{setDraft({...saved});storeDraft(null);setFeedback({text:'Unsaved changes discarded.',error:false});}}>Discard changes</button></div>
        {dirty&&<small className="settings-muted">Save first to preview. Your draft stays here when you change tabs.</small>}
      </form>}
      <p className={`settings-status${feedback.error?' settings-error':''}`} role={feedback.error?'alert':'status'}>{feedback.text}</p>
      <div className="settings-actions"><button className="secondary" disabled={busy!=='preview_voice'} onClick={async()=>{try{await requestNativeSetting('stop_speaking',{},window,5000);if(active.current)setFeedback({text:'Android received the stop-speaking request.',error:false});}catch(error){if(active.current)setFeedback({text:error.message,error:true});}}}>Stop preview</button><button className="secondary" disabled={Boolean(busy)} onClick={()=>run('open_voice_settings',{},()=>setFeedback({text:'Android speech settings opened. Return here and refresh voices after making changes.',error:false}))}>Android speech settings</button><button className="secondary" disabled={Boolean(busy)||dirty} onClick={load}>Refresh voices</button><button className="secondary" disabled={Boolean(busy)} onClick={()=>run('open_app_settings',{},()=>setFeedback({text:'Android app settings opened.',error:false}))}>App permissions</button></div>
      {catalog?.engine&&<p className="settings-muted">Speech engine: {catalog.engine}</p>}
    </>}
  </section>;
}

export function WakePreferences({device}){
  const current=device.status?.wakeWord?.sensitivity;
  const supported=Number.isInteger(current)&&current>=25&&current<=90;
  const [value,setValue]=useState(null),[saved,setSaved]=useState(null),[busy,setBusy]=useState(false),[feedback,setFeedback]=useState({text:'',error:false});
  const active=useRef(false),inFlight=useRef(false);
  useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
  useEffect(()=>{if(supported&&value===null){setValue(current);setSaved(current);}},[supported,current,value]);
  const save=async event=>{
    event.preventDefault();if(inFlight.current||!supported)return;
    inFlight.current=true;setBusy(true);setFeedback({text:'Saving wake sensitivity…',error:false});
    try{const result=await requestNativeSetting('wake_config',{sensitivity:value},window,10000);if(!active.current)return;if(result.saved!==true||result.sensitivity!==value)throw Error('Android did not confirm this sensitivity. Refresh Settings to check before retrying.');setSaved(value);setFeedback({text:`Wake sensitivity saved.${result.appliesAfterRestart?' Stop listening, then enable hands-free again to apply it to the current listener.':''}`,error:false});}
    catch(error){if(active.current)setFeedback({text:error.message,error:true});}
    finally{inFlight.current=false;if(active.current)setBusy(false);}
  };
  return <div className="settings-preferences">
    <DeviceNotice {...device} supported={supported}/>
    {supported&&value!==null&&<form noValidate onSubmit={save} aria-busy={busy}><div className="settings-field"><label htmlFor="wake-sensitivity">Wake sensitivity <output htmlFor="wake-sensitivity">{value}</output></label><input id="wake-sensitivity" type="range" min="25" max="90" step="1" value={value} disabled={busy} aria-describedby="wake-sensitivity-help" onChange={event=>setValue(Number(event.target.value))}/><small id="wake-sensitivity-help">Higher values make “Hey ICARUS” easier to trigger, but can also increase accidental wakes. Default: 60.</small></div><div className="settings-actions"><button className="secondary" type="submit" disabled={busy||value===saved}>Save sensitivity</button></div><p className={`settings-status${feedback.error?' settings-error':''}`} role={feedback.error?'alert':'status'}>{feedback.text}</p></form>}
  </div>;
}

export function LocalModelPreferences({device}){
  const supported=device.status?.capabilities?.includes('local_model_status')===true;
  const [model,setModel]=useState(null),[wifiOnly,setWifiOnly]=useState(true),[busy,setBusy]=useState(''),[feedback,setFeedback]=useState({text:'',error:false});
  const active=useRef(false),inFlight=useRef(false),dialog=useRef(null),cancel=useRef(null);
  useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
  const run=async action=>{
    if(inFlight.current||!supported)return;
    inFlight.current=true;setBusy(action);setFeedback({text:'Waiting for Android…',error:false});
    try{const value=await requestNativeSetting(action,action==='download_local_model'?{wifiOnly}:{},window,10000);if(!active.current)return;if(typeof value.state!=='string'||(action==='delete_local_model'&&(value.deleted!==true||value.state!=='not_downloaded')))throw Error('Android did not confirm the model state. Refresh to check before retrying.');setModel(value);setFeedback({text:action==='delete_local_model'?'Local model deleted from this phone.':action==='download_local_model'?'Android accepted the download request. Follow its progress below.':'Model status received.',error:false});}
    catch(error){if(active.current)setFeedback({text:error.message,error:true});}
    finally{inFlight.current=false;if(active.current)setBusy('');}
  };
  useEffect(()=>{if(supported)run('local_model_status');},[supported]);
  useEffect(()=>{if(!supported||!['downloading','paused'].includes(model?.state))return;const timer=setInterval(()=>{if(document.visibilityState!=='hidden')run('local_model_status');},5000);return()=>clearInterval(timer);},[supported,model?.state]);
  const progress=Number.isFinite(model?.downloadedBytes)&&Number.isFinite(model?.totalBytes)&&model.totalBytes>0?Math.min(100,Math.max(0,model.downloadedBytes/model.totalBytes*100)):null;
  return <section className="settings-section settings-preferences" aria-labelledby="local-model-title"><h2 id="local-model-title">On-device model</h2><p>Manage Gemma on this phone. The download is about 2.6 GB. This manages the model file; Chat and hands-free do not automatically switch to it. Speech recognition, web search and connected services may still need internet.</p><DeviceNotice {...device} supported={supported}/>{supported&&<><p>{modelStatusText(model)}</p>{progress!==null&&<><progress className="settings-model-progress" value={progress} max="100" aria-label="Local model download"/><p>{Math.floor(progress)}% downloaded</p></>}<label className="settings-check"><input type="checkbox" checked={wifiOnly} disabled={Boolean(busy)||['downloading','paused'].includes(model?.state)} onChange={event=>setWifiOnly(event.target.checked)}/>Download on Wi-Fi only</label><div className="settings-actions"><button className="secondary" disabled={Boolean(busy)||!model||['ready','downloading','paused','downloaded_unverified'].includes(model.state)} onClick={()=>run('download_local_model')}>Download local model</button><button className="secondary" disabled={Boolean(busy)} onClick={()=>run('local_model_status')}>Refresh model status</button></div><p className={`settings-status${feedback.error?' settings-error':''}`} role={feedback.error?'alert':'status'}>{feedback.text}</p><div className="settings-danger"><button className="secondary" disabled={Boolean(busy)||!model||model.state==='not_downloaded'} onClick={()=>{dialog.current.showModal();cancel.current.focus();}}>Delete local model</button></div><dialog ref={dialog} className="settings-dialog" aria-labelledby="delete-local-model-title" aria-describedby="delete-local-model-description"><h2 id="delete-local-model-title">Delete the local model?</h2><p id="delete-local-model-description">This removes Gemma from this phone and cancels any download. You will need to download it again to use local generation. Your account and saved memories stay in place.</p><div className="settings-actions"><button ref={cancel} className="secondary" onClick={()=>dialog.current.close()}>Cancel</button><button className="primary" onClick={()=>{dialog.current.close();run('delete_local_model');}}>Delete model</button></div></dialog></>}</section>;
}

export function DevicePreferences({children}){
  const device=useDeviceSettings();
  return <><VoicePreferences device={device}/><section className="settings-section" aria-labelledby="voice-settings"><h2 id="voice-settings">Voice & listening</h2><WakePreferences device={device}/>{children}</section><LocalModelPreferences device={device}/></>;
}
