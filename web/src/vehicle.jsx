import React,{useEffect,useRef,useState}from'react';
import{getNativeTransport}from'./native-transport.js';

export function VehicleControls(){
  const[available,setAvailable]=useState(Boolean(getNativeTransport()));
  const[message,setMessage]=useState('Open ICARUS on Android to activate native HUD controls.');
  const[xreal,setXreal]=useState(null),[busy,setBusy]=useState('');
  const pending=useRef(new Map());
  useEffect(()=>{
    const refresh=()=>setAvailable(Boolean(getNativeTransport()));refresh();
    const timer=setInterval(refresh,1000),previous=window.ICARUS_NATIVE_RESULT;
    const receive=raw=>{try{const value=typeof raw==='string'?JSON.parse(raw):raw,action=pending.current.get(value?.requestId);if(action){pending.current.delete(value.requestId);setBusy('');if(value.ok===false)setMessage(value.message||'Android could not complete that HUD request.');else if(action==='xreal_status'||action==='meta_integration_set'){const data=value.data||{};setXreal(data);setMessage(data.enabled===false?'XREAL is available but disabled. Enable it when your glasses are connected.':'XREAL spatial HUD is ready.');}else setMessage(action==='open_driving_hud'?'Phone Driving HUD opened.':'XREAL Spatial HUD opened.');}}catch{setBusy('');setMessage('Android returned an unreadable HUD response.');}if(typeof previous==='function')previous(raw);};
    window.ICARUS_NATIVE_RESULT=receive;return()=>{clearInterval(timer);if(window.ICARUS_NATIVE_RESULT===receive)window.ICARUS_NATIVE_RESULT=previous;};
  },[]);
  const send=(action,args={})=>{const transport=getNativeTransport();if(!transport){setAvailable(false);setMessage('Open this page inside the installed ICARUS Android app.');return;}const requestId=crypto.randomUUID();pending.current.set(requestId,action);setBusy(action);setMessage('Waiting for Android…');try{transport.postMessage(JSON.stringify({action,arguments:args,requestId}));}catch{pending.current.delete(requestId);setBusy('');setMessage('Android connection is unavailable. Reopen ICARUS and try again.');}};
  return <section className="hud-console" aria-labelledby="hud-title"><div className="hud-console__heading"><div><p className="eyebrow">SPATIAL SYSTEMS</p><h2 id="hud-title">Choose your HUD</h2></div><span className={`hud-link ${available?'is-online':''}`}>{available?'ANDROID LINKED':'WEB PREVIEW'}</span></div><div className="hud-options"><article><span>PHONE / LIVE</span><h3>Driving HUD</h3><p>Large, glanceable navigation and vehicle telemetry for the mounted phone.</p><button className="primary" disabled={!available||Boolean(busy)} onClick={()=>send('open_driving_hud')}>Open phone HUD <b>→</b></button></article><article><span>XREAL / 3DOF</span><h3>Spatial HUD</h3><p>The bundled transparent ICARUS display for assistant guidance, navigation, and vehicle data.</p><div className="hud-actions"><button className="primary" disabled={!available||Boolean(busy)||xreal?.enabled===false} onClick={()=>send('open_xreal_hud',{mode:'vehicle'})}>Open spatial HUD <b>→</b></button><button className="secondary" disabled={!available||Boolean(busy)} onClick={()=>send(xreal?.enabled===false?'meta_integration_set':'xreal_status',xreal?.enabled===false?{provider:'xreal',enabled:true}:{})}>{xreal?.enabled===false?'Enable XREAL':'Check XREAL'}</button></div></article></div><p className="hud-status" role="status">{message}</p></section>;
}
