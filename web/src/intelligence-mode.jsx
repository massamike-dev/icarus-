import React,{useEffect,useRef,useState} from 'react';
import {readDeviceStatus} from './device-actions';
import {getNativeTransport} from './native-transport.js';
import {requestNativeSetting} from './native-settings.js';
import {isLocalMode} from './local-core.js';

// A mode change is deliberate and acknowledged by Android. No error handler
// switches providers, submits a prompt, or transfers local history to a server.
export function IntelligenceMode(){
  const [runtime,setRuntime]=useState(null),[message,setMessage]=useState(''),[busy,setBusy]=useState(false),[review,setReview]=useState(false);
  const mounted=useRef(false),lock=useRef(false),dialog=useRef(null),cancel=useRef(null);
  const local=isLocalMode();
  useEffect(()=>{
    mounted.current=true;
    const refresh=async()=>{if(!getNativeTransport())return;const status=await readDeviceStatus();if(mounted.current&&['local','cloud'].includes(status?.runtime?.mode))setRuntime(status.runtime);};
    refresh();window.addEventListener('icarus-native-ready',refresh);
    return()=>{mounted.current=false;window.removeEventListener('icarus-native-ready',refresh);};
  },[]);
  async function switchMode(mode){
    if(lock.current||!runtime)return;
    lock.current=true;setBusy(true);setMessage('Opening the selected mode…');
    try{const result=await requestNativeSetting('set_runtime_mode',{mode});if(result.mode!==mode)throw Error('Android did not confirm this mode. Check the current screen before trying again.');if(mounted.current)setMessage(`Android selected ${mode} mode.`);}
    catch(error){if(mounted.current)setMessage(error.message);}
    finally{lock.current=false;if(mounted.current)setBusy(false);}
  }
  return <section className="settings-section intelligence-mode" aria-labelledby="intelligence-mode-title">
    <h2 id="intelligence-mode-title">Intelligence, your choice</h2>
    <p>{local?'On-device core · No account or per-message AI payment required.':'Cloud workspace · Optional online intelligence and connected services.'}</p>
    <p>Local Chat runs on this phone after a one-time model download (about 2.6 GB). Saved chats and Memory stay in this app’s device storage. Device controls retain Android permissions and action confirmations.</p>
    <p>Offline voice capture depends on Android’s installed speech-recognition support. Navigation, connected services and downloads may still need internet.</p>
    {runtime?<div className="settings-actions">{local?<button className="secondary" disabled={busy||runtime.cloudAvailable!==true} onClick={()=>{setReview(true);dialog.current.showModal();cancel.current.focus();}}>Explore optional cloud</button>:<button className="primary" disabled={busy} onClick={()=>switchMode('local')}>Use free on-device core</button>}</div>:<p>{getNativeTransport()?'Update the Android app to use the bundled on-device core and mode switch.':'Install the Android app to use the free on-device core.'}</p>}
    {local&&runtime?.cloudAvailable===false&&<p>No cloud workspace is configured. The on-device core remains available.</p>}
    {message&&<p role="status" className="settings-status">{message}</p>}
    <dialog ref={dialog} className="settings-dialog" aria-labelledby="cloud-mode-title" onCancel={()=>setReview(false)}>
      <h2 id="cloud-mode-title">Open optional cloud mode?</h2>
      <p>Cloud mode requires internet and a separate sign-in. New cloud messages and saved cloud Memory are processed by the configured server and AI provider; provider or membership charges may apply. Switching does not purchase anything.</p>
      <p>Your local chats and Memory will remain on this phone and are not uploaded by switching. You can return to the free on-device core at any time.</p>
      <div className="settings-actions"><button ref={cancel} className="secondary" onClick={()=>{setReview(false);dialog.current.close();}}>Stay on device</button><button className="primary" disabled={busy||!review} onClick={()=>{setReview(false);dialog.current.close();switchMode('cloud');}}>Open cloud workspace</button></div>
    </dialog>
  </section>;
}
