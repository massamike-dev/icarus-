import React,{useCallback,useEffect,useRef,useState} from 'react';
import {getNativeTransport} from './native-transport.js';
import {readDeviceStatus} from './device-actions.js';
import {requestNativeSetting} from './native-settings.js';
import {hudResultMessage,validHudStatus} from './hud-controls.js';
import './vehicle.css';

export function VehicleControls(){
  const [link,setLink]=useState('checking'),[xreal,setXreal]=useState(null);
  const [busy,setBusy]=useState(false),[feedback,setFeedback]=useState({text:'Checking HUD support on this device…',error:false});
  const [change,setChange]=useState(null);
  const life=useRef(null),inFlight=useRef(false),dialog=useRef(null),cancel=useRef(null),trigger=useRef(null);
  useEffect(()=>{if(!busy&&trigger.current&&!dialog.current?.open){trigger.current.focus();trigger.current=null;}},[busy,change]);
  const refresh=useCallback(async()=>{
    const session=life.current;
    if(!session||inFlight.current||dialog.current?.open)return;
    inFlight.current=true;setBusy(true);setXreal(null);setLink('checking');setFeedback({text:'Checking HUD support on this device…',error:false});
    try{
      if(!getNativeTransport()){setLink('browser');setFeedback({text:'Open ICARUS Test on your Android device to use the HUD controls.',error:false});return;}
      const status=await readDeviceStatus();
      if(session.signal.aborted)return;
      if(!status){setLink('unavailable');throw Error('Android did not confirm HUD support. Reopen ICARUS Test or refresh status.');}
      if(status.hudControlVersion!==1){setLink('older');setFeedback({text:'Install ICARUS Test 1.6.9 or later for these HUD controls. Your voice and companion controls remain available.',error:false});return;}
      setLink('ready');
      const data=await requestNativeSetting('xreal_status',{},window,8000,{signal:session.signal});
      if(!validHudStatus(data))throw Error('XREAL status was incomplete. Refresh before opening the bundled HUD.');
      if(!session.signal.aborted){setXreal(data);setFeedback({text:data.enabled?'XREAL is enabled. Bundled display availability does not confirm connected glasses.':'Phone HUD available. XREAL is off until you enable it.',error:false});}
    }catch(error){if(!session.signal.aborted)setFeedback({text:error.message,error:true});}
    finally{if(!session.signal.aborted){inFlight.current=false;setBusy(false);}}
  },[]);
  useEffect(()=>{
    const session=new AbortController();life.current=session;inFlight.current=false;refresh();
    const onVisible=()=>{if(document.visibilityState==='visible')refresh();};
    window.addEventListener('icarus-native-ready',refresh);document.addEventListener('visibilitychange',onVisible);
    return()=>{session.abort();life.current=null;window.removeEventListener('icarus-native-ready',refresh);document.removeEventListener('visibilitychange',onVisible);};
  },[refresh]);
  const run=async(action,args={})=>{
    if(inFlight.current||link!=='ready'||!life.current)return;
    const session=life.current;inFlight.current=true;setBusy(true);setFeedback({text:'Waiting for Android confirmation…',error:false});
    try{
      const data=await requestNativeSetting(action,args,window,8000,{signal:session.signal});
      const text=hudResultMessage(action,args,data);
      if(session.signal.aborted)return;
      if(action==='meta_integration_set'){
        // Re-read runtime availability after enabling; do not invent a ready state.
        setXreal(null);
        dialog.current.close();setChange(null);
        const next=await requestNativeSetting('xreal_status',{},window,8000,{signal:session.signal});
        if(!validHudStatus(next))throw Error(`${text} Refresh status to check display availability.`);
        if(!session.signal.aborted)setXreal(next);
        if(next.enabled!==args.enabled)throw Error('XREAL status changed before confirmation. Check the current state before trying again.');
      }
      if(!session.signal.aborted)setFeedback({text,error:false});
    }catch(error){if(!session.signal.aborted)setFeedback({text:error.message,error:true});}
    finally{if(!session.signal.aborted){inFlight.current=false;setBusy(false);}}
  };
  const requestToggle=event=>{
    if(busy||!xreal)return;
    trigger.current=event.currentTarget;setChange(!xreal.enabled);setFeedback({text:'',error:false});dialog.current.showModal();cancel.current.focus();
  };
  const unavailable=busy||link!=='ready',canLaunch=!unavailable&&xreal?.enabled===true&&xreal?.runtimeAvailable===true;
  return <section className="workspace vehicle-workspace" aria-labelledby="vehicle-title">
    <p className="eyebrow">VEHICLE & DISPLAY</p><h1 id="vehicle-title">Your HUD, within reach.</h1>
    <p className="vehicle-intro">Set up while parked. Choose the phone cockpit or ICARUS’s bundled display overlay.</p>
    <div className="hud-options" aria-busy={busy}>
      <section className="hud-option" aria-labelledby="phone-hud-title"><p className="eyebrow">PHONE</p><h2 id="phone-hud-title">Driving HUD</h2><p>Open the landscape cockpit. Vehicle readings require a paired, connected OBD adapter; unavailable readings stay unavailable.</p><button className="primary" disabled={unavailable} onClick={()=>run('open_driving_hud')}>Open phone HUD</button></section>
      <section className="hud-option" aria-labelledby="spatial-hud-title"><p className="eyebrow">XREAL · BUNDLED OVERLAY</p><h2 id="spatial-hud-title">Spatial HUD</h2><p>A screen-fixed assistant or vehicle display. This is not the separate tracked Unity companion, and does not verify glasses connection or provide live navigation by itself.</p><p className="hud-availability">{!xreal?'XREAL status not confirmed.':!xreal.enabled?'XREAL is off.':xreal.runtimeAvailable?'Bundled display available.':'Bundled display unavailable. Use the phone HUD.'}</p><div className="settings-actions"><button className="primary" disabled={!canLaunch} onClick={()=>run('open_xreal_hud',{mode:'assistant'})}>Open assistant HUD</button><button className="secondary" disabled={!canLaunch} onClick={()=>run('open_xreal_hud',{mode:'vehicle'})}>Open vehicle overlay</button><button className="secondary" disabled={unavailable} onClick={()=>run('close_xreal_hud')}>Close bundled HUD</button><button className="secondary" disabled={unavailable||!xreal} onClick={requestToggle}>{xreal?.enabled?'Disable XREAL':'Enable XREAL'}</button></div></section>
    </div>
    <div className="hud-feedback"><p className={`settings-status${feedback.error?' settings-error':''}`} role={feedback.error?'alert':'status'}>{feedback.text}</p><button className="secondary" disabled={busy} onClick={refresh}>Refresh HUD status</button></div>
    <dialog ref={dialog} className="settings-dialog" aria-labelledby="xreal-consent-title" aria-describedby="xreal-consent-description" onCancel={event=>{if(busy)event.preventDefault();}} onClose={()=>{setChange(null);trigger.current?.focus();}}>
      <h2 id="xreal-consent-title">{change?'Enable':'Disable'} XREAL?</h2><p id="xreal-consent-description">{change?'This enables the optional XREAL display controls on this device. It does not enable listening, request Bluetooth access, or confirm connected glasses.':'This disables XREAL display controls and requests that the bundled HUD close. The phone HUD remains available.'}</p>
      {change!==null&&<p className="settings-status" role={feedback.error?'alert':'status'}>{feedback.text}</p>}
      <div className="settings-actions"><button ref={cancel} className="secondary" disabled={busy} onClick={()=>dialog.current.close()}>Cancel</button><button className="primary" disabled={busy||change===null} onClick={()=>run('meta_integration_set',{provider:'xreal',enabled:change})}>{change?'Enable XREAL':'Disable XREAL'}</button></div>
    </dialog>
  </section>;
}
