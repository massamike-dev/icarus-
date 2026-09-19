import React,{useEffect,useRef,useState} from 'react';
import {readDeviceStatus,subscribeNative} from './device-actions';
import {getNativeTransport} from './native-transport.js';
import {LilIcarus3D} from './lil-icarus-3d.jsx';
import './companion.css';

const preferenceKey='icarus_companion_preferences';
const activeStages=new Set(['STARTING','PREPARING_VOICE','INITIALIZING_VOICE','ACKNOWLEDGING','CAPTURING','AWAITING_CONFIRMATION','INTERPRETING','EXECUTING','SPEAKING']);
const stages={
  STARTING:['preparing','Starting the wake listener.'],
  LISTENING:['idle','Waiting for “Hey ICARUS.”'],
  PREPARING_VOICE:['preparing','Preparing my voice.'],
  INITIALIZING_VOICE:['preparing','Starting speech.'],
  ACKNOWLEDGING:['speaking','Acknowledging your request.'],
  CAPTURING:['listening','Listening to your command.'],
  AWAITING_CONFIRMATION:['listening','Waiting for your confirmation.'],
  INTERPRETING:['thinking','Understanding your command.'],
  EXECUTING:['thinking','Carrying out your request.'],
  SPEAKING:['speaking','Speaking.'],
  MICROPHONE_BLOCKED:['error','Android is blocking microphone input. Check Voice for help.'],
  AUDIO_STALLED:['error','Microphone input has stalled. Check Voice for help.'],
  ERROR:['error','Voice needs attention. Check Voice for details.'],
  STOPPED:['idle','Hands-free is stopped. Enable it in Voice.'],
};
function preferences(){try{const p=JSON.parse(localStorage.getItem(preferenceKey));return{hidden:p?.hidden===true,side:p?.side==='left'?'left':'right'};}catch{return{hidden:false,side:'right'};}}
function decode(raw){try{const p=typeof raw==='string'?JSON.parse(raw):raw;return p&&typeof p==='object'&&!Array.isArray(p)?p:null;}catch{return null;}}
function voiceView(status,available){
  if(!available)return{phase:'idle',label:'Open the installed Android app for voice controls.',canTalk:false};
  const wake=status?.wakeWord;
  if(!wake)return{phase:'idle',label:'Voice status is unavailable. Open Voice to check the connection.',canTalk:false};
  if(wake.permissionGranted===false)return{phase:'error',label:'Allow microphone access in Android settings.',canTalk:false};
  if(wake.enabled!==true)return{phase:'idle',label:'Enable hands-free in Voice, then tap Talk now.',canTalk:false};
  const [phase,label]=stages[wake.listenerState]||['idle','Voice status is unknown. Check Voice for details.'];
  return{phase,label,canTalk:status.connected===true&&wake.talkNowSupported===true&&Object.hasOwn(stages,wake.listenerState)&&!activeStages.has(wake.listenerState)&&wake.listenerState!=='STOPPED'};
}

// This single companion belongs to the authenticated shell. It displays native
// state; opening, moving or restoring it never requests microphone access.
export function IcarusCompanion({onNavigate}){
  const[prefs,setPrefs]=useState(preferences),[open,setOpen]=useState(false),[tucked,setTucked]=useState(false);
  const[status,setStatus]=useState(null),[available,setAvailable]=useState(Boolean(getNativeTransport()));
  const[message,setMessage]=useState(''),[busy,setBusy]=useState(false),[modelReady,setModelReady]=useState(false),[modelFailed,setModelFailed]=useState(false),[posterFailed,setPosterFailed]=useState(false);
  const dock=useRef(null),figure=useRef(null),restore=useRef(null),pending=useRef(null),timeout=useRef(null),mounted=useRef(false);
  const currentStatus=useRef(null),visible=useRef(false),refresh=useRef(()=>{}),focusAfterHide=useRef(false);
  visible.current=!prefs.hidden&&!tucked;
  const updateStatus=value=>{currentStatus.current=value;setStatus(value);};

  useEffect(()=>{try{localStorage.setItem(preferenceKey,JSON.stringify(prefs));}catch{/* Preferences remain usable for this session. */}},[prefs]);
  useEffect(()=>{if(focusAfterHide.current){(prefs.hidden?restore:figure).current?.focus();focusAfterHide.current=false;}},[prefs.hidden]);
  useEffect(()=>{
    mounted.current=true;
    let revision=0;
    const isVisible=()=>visible.current&&document.visibilityState!=='hidden';
    const refreshStatus=async()=>{
      if(!isVisible())return;
      const requestRevision=++revision,bridge=getNativeTransport();
      setAvailable(Boolean(bridge));
      if(!bridge){updateStatus(null);return;}
      const value=await readDeviceStatus();
      if(mounted.current&&isVisible()&&requestRevision===revision&&getNativeTransport()===bridge)updateStatus(value);
    };
    refresh.current=refreshStatus;
    const removeStatus=subscribeNative(window,'status',raw=>{
      if(!isVisible())return;
      const value=decode(raw);
      if(value?.connected===true&&value?.wakeWord&&getNativeTransport())updateStatus(value);
    });
    const removeResult=subscribeNative(window,'result',raw=>{
      const value=decode(raw);
      if(!pending.current||value?.requestId!==pending.current)return;
      clearTimeout(timeout.current);pending.current=null;setBusy(false);
      if(value.ok===true&&!value.error&&value.data?.requestAccepted===true){
        updateStatus(null);
        setMessage('Android accepted Talk now. Wait for the acknowledgement, then speak.');
      }else setMessage('Android could not start the voice turn. Check Voice, then try again.');
      refreshStatus();
    });
    const checkFocus=()=>{
      const element=document.activeElement;
      const editing=Boolean(element?.matches?.('input,textarea,select,[contenteditable="true"]')||element?.closest?.('[contenteditable="true"]'));
      setTucked(editing);
      if(editing){visible.current=false;setOpen(false);}
    };
    const focusOut=()=>queueMicrotask(()=>{if(mounted.current)checkFocus();});
    const visibility=()=>{if(document.visibilityState==='hidden'){revision++;updateStatus(null);}else refreshStatus();};
    document.addEventListener('focusin',checkFocus);
    document.addEventListener('focusout',focusOut);
    document.addEventListener('visibilitychange',visibility);
    window.addEventListener('icarus-native-ready',refreshStatus);
    checkFocus();refreshStatus();
    const timer=setInterval(refreshStatus,2000);
    return()=>{
      mounted.current=false;revision++;pending.current=null;clearTimeout(timeout.current);clearInterval(timer);
      removeStatus();removeResult();document.removeEventListener('focusin',checkFocus);document.removeEventListener('focusout',focusOut);
      document.removeEventListener('visibilitychange',visibility);window.removeEventListener('icarus-native-ready',refreshStatus);
    };
  },[]);
  useEffect(()=>{if(!prefs.hidden&&!tucked)refresh.current();else updateStatus(null);},[prefs.hidden,tucked]);
  useEffect(()=>{
    if(!open)return;
    const close=event=>{
      if(event.type==='keydown'&&event.key==='Escape'){setOpen(false);figure.current?.focus();}
      else if(event.type==='pointerdown'&&!dock.current?.contains(event.target))setOpen(false);
    };
    document.addEventListener('keydown',close);document.addEventListener('pointerdown',close);
    return()=>{document.removeEventListener('keydown',close);document.removeEventListener('pointerdown',close);};
  },[open]);

  const talk=()=>{
    if(pending.current||!voiceView(currentStatus.current,Boolean(getNativeTransport())).canTalk)return;
    const bridge=getNativeTransport(),requestId=crypto.randomUUID();
    pending.current=requestId;setBusy(true);setMessage('Requesting a voice turn…');
    timeout.current=setTimeout(()=>{
      if(!mounted.current||pending.current!==requestId)return;
      pending.current=null;setBusy(false);updateStatus(null);
      setMessage('Android did not confirm Talk now. Check Voice before trying again.');refresh.current();
    },5000);
    try{bridge.postMessage(JSON.stringify({action:'start_voice_turn',arguments:{},requestId}));}
    catch{clearTimeout(timeout.current);pending.current=null;setBusy(false);updateStatus(null);setMessage('The Android connection is unavailable. Open Voice to check it.');}
  };
  const view=voiceView(status,available);
  const motion=message.startsWith('Android accepted')?'confirm':view.phase;
  const hide=()=>{focusAfterHide.current=true;setOpen(false);setPrefs(p=>({...p,hidden:true}));};
  const show=()=>{focusAfterHide.current=true;setPrefs(p=>({...p,hidden:false}));};
  return <div ref={dock} className={`icarus-companion companion-${prefs.side}`} data-phase={view.phase} hidden={tucked}>
    {prefs.hidden?<button ref={restore} type="button" className="companion-restore" onClick={show} aria-label="Show ICARUS companion">ICARUS</button>:<>
      {open&&<section className="companion-panel" id="icarus-companion-panel" aria-labelledby="icarus-companion-title">
        <div className="companion-heading"><h2 id="icarus-companion-title">ICARUS</h2><button type="button" className="companion-close" onClick={()=>{setOpen(false);figure.current?.focus();}} aria-label="Close ICARUS companion panel">×</button></div>
        <p className="companion-status" role="status">{view.label}</p>
        <button type="button" className="companion-talk" disabled={!view.canTalk||busy} aria-busy={busy} onClick={talk}>Talk now</button>
        {message&&<p className="companion-feedback" role="status">{message}</p>}
        {available&&status?.wakeWord&&status.wakeWord.talkNowSupported!==true&&<p className="companion-help">Talk now needs the updated Android build.</p>}
        <button type="button" className="companion-voice" onClick={()=>{setOpen(false);onNavigate('voice');}}>Open Voice controls</button>
        <div className="companion-tools"><button type="button" onClick={()=>setPrefs(p=>({...p,side:'left'}))} disabled={prefs.side==='left'} aria-label="Move ICARUS to the left">← Left</button><button type="button" onClick={()=>setPrefs(p=>({...p,side:'right'}))} disabled={prefs.side==='right'} aria-label="Move ICARUS to the right">Right →</button><button type="button" onClick={hide}>Hide ICARUS</button></div>
      </section>}
      <button ref={figure} type="button" className="companion-figure" onClick={()=>setOpen(value=>!value)} aria-label="Open ICARUS companion" aria-expanded={open} aria-controls="icarus-companion-panel" title="ICARUS companion">
        {!modelFailed&&<LilIcarus3D motion={motion} onReady={()=>setModelReady(true)} onError={()=>setModelFailed(true)}/>}
        {!posterFailed&&(!modelReady||modelFailed)&&<img className="companion-poster" src="/brand/icarus-companion.png" alt="" width="96" height="112" onError={()=>setPosterFailed(true)}/>}
        {posterFailed&&<span className="companion-fallback" aria-hidden="true">W</span>}
        <span className="companion-state-dot" aria-hidden="true"/>
      </button>
    </>}
  </div>;
}
