import React,{useEffect,useRef,useState} from 'react';
import {IcarusCompanion} from './icarus-companion.jsx';
import {readDeviceStatus} from './device-actions.js';
import {getNativeTransport} from './native-transport.js';
import {isLocalMode} from './local-core.js';

// The dashboard links into the existing, permission-aware feature screens.
// It never starts a device action or claims a connection just by being visible.
export function Dashboard({onNavigate,onStartChat,user,Icon}) {
  const [draft,setDraft]=useState('');
  const [status,setStatus]=useState(null),[checking,setChecking]=useState(false),[checked,setChecked]=useState(false);
  const mounted=useRef(true),pending=useRef(false),draftField=useRef(null);
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false}},[]);
  const check=async()=>{
    if(pending.current)return;
    pending.current=true;setChecking(true);setStatus(null);
    try{const result=getNativeTransport()?await readDeviceStatus():null;if(mounted.current){setStatus(result);setChecked(true)}}
    catch{if(mounted.current){setStatus(null);setChecked(true)}}
    finally{pending.current=false;if(mounted.current)setChecking(false)}
  };
  const native=Boolean(getNativeTransport()),local=isLocalMode();
  const linkLabel=checking?'Checking connection…':status?.connected===true?'Android connected':checked?(native?'Connection not confirmed':'Available in the Android app'):(native?'Android link available':'Browser session');
  const firstName=user?.name?.trim().split(/\s+/)[0];
  const prompt=!local&&firstName&&firstName!=='Local'?`What are we working on, ${firstName}?`:'What are we working on?';
  return <section className="command-dashboard" aria-labelledby="dashboard-title">
    <div className="dashboard-home">
    <div className="dashboard-heading"><div className="dashboard-wordmark">ICARUS</div><span className="workspace-badge"><i aria-hidden="true"/>{local?'On-device mode':'Cloud mode'}</span></div>
    <div className="command-stage">
      <div className="stage-copy"><h1 id="dashboard-title">Speak with ICARUS</h1><p>{prompt}</p></div>
      <div className="dashboard-companion-stage"><div className="companion-halo" aria-hidden="true"/><IcarusCompanion onNavigate={onNavigate} presentation="dashboard"/><p className="companion-caption">Tap Lil ICARUS for voice & controls</p></div>
      <div className="dashboard-suggestions" aria-label="Conversation starters">{['Plan my day','Help with a project','Troubleshoot something'].map(text=><button key={text} onClick={()=>onStartChat(text)}>{text}</button>)}</div>
      <form noValidate className="dashboard-composer" onSubmit={event=>{event.preventDefault();if(draft.trim())onStartChat(draft.trim())}}><label className="sr-only" htmlFor="dashboard-draft">Message ICARUS</label><button type="button" className="composer-clear" onClick={()=>{setDraft('');draftField.current?.focus()}} disabled={!draft} aria-label="Clear message"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button><input ref={draftField} aria-describedby="dashboard-draft-help" id="dashboard-draft" value={draft} onChange={event=>setDraft(event.target.value)} placeholder="Reply to ICARUS…" autoComplete="off"/><button type="submit" disabled={!draft.trim()} aria-label="Continue in Chat" title="Continue in Chat"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 10 18-7-7 18-3-8-8-3Zm8 3L21 3"/></svg></button></form>
      <p className="sr-only" id="dashboard-draft-help">Opens Chat with your draft for review. Nothing is sent yet.</p>
    </div>
    </div>
    <details className="dashboard-tools"><summary>Driving, voice & device controls</summary>
    <p className="dashboard-tools-intro">Open a control when you need it. Messages from Home open in Chat for review.</p>
    <div className="dashboard-actions">
      {[['drive','Driving mode','Navigation, voice & vehicle','Open vehicle controls'],['voice','Voice','Wake listening & speech','Open voice controls'],['memory','Memory','What ICARUS remembers','Open saved memory']].map(([id,title,description,label])=><button className="dashboard-action" key={id} onClick={()=>onNavigate(id)} aria-label={label}><span className="action-icon"><Icon name={id}/></span><span><strong>{title}</strong><small>{description}</small></span><span className="action-arrow" aria-hidden="true">↗</span></button>)}
    </div>
    <section className="dashboard-device" aria-labelledby="device-title"><div className="device-symbol"><Icon name="settings"/></div><div><h2 id="device-title">Your device</h2><p role="status">{linkLabel}</p></div><button className="secondary" disabled={checking} aria-busy={checking} onClick={check}>{checking?'Checking…':'Check connection'}</button></section>
    {checked&&status?.connected!==true&&native&&<p className="dashboard-help">Reopen ICARUS and check Voice if your phone isn’t responding.</p>}
    <p className="dashboard-footnote">{local?'No account needed for your local workspace. Optional cloud intelligence is managed in Settings.':'Cloud intelligence and connected services are managed in Settings.'}</p>
    </details>
  </section>;
}
