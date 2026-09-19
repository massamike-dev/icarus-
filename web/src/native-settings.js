import {getNativeTransport} from './native-transport.js';
import {subscribeNative} from './device-actions.js';

const object=value=>value&&typeof value==='object'&&!Array.isArray(value);
export const VOICE_PRESETS={deep_warm:{rate:0.88,pitch:0.82},standard:{rate:1,pitch:1}};
export function validVoiceSettings(value) {
  return object(value)&&value.supported===true&&['deep_warm','standard','custom'].includes(value.profile)&&typeof value.voiceName==='string'&&Number.isFinite(value.rate)&&value.rate>=0.5&&value.rate<=1.5&&Number.isFinite(value.pitch)&&value.pitch>=0.5&&value.pitch<=1.5;
}
export function voiceDraft(value) {return {profile:value.profile,voiceName:value.voiceName,rate:value.rate,pitch:value.pitch};}
export function sameVoice(a,b) {return Boolean(a&&b)&&a.profile===b.profile&&a.voiceName===b.voiceName&&Math.abs(a.rate-b.rate)<0.001&&Math.abs(a.pitch-b.pitch)<0.001;}

// Settings requests share the same dispatcher as Chat, diagnostics and the companion.
// Only this request's acknowledgement can settle its UI; timeouts never claim a save.
export function requestNativeSetting(action,args={},host=window,timeoutMs=35000,{signal}={}) {
  if(signal?.aborted)return Promise.reject(Error('Native request cancelled.'));
  const bridge=getNativeTransport(host);
  if(!bridge)return Promise.reject(Error('Open the installed Android app to change device settings.'));
  return new Promise((resolve,reject)=>{
    const requestId=crypto.randomUUID();let settled=false,unsubscribe=()=>{};
    const finish=(data,error)=>{if(settled)return;settled=true;clearTimeout(timer);unsubscribe();signal?.removeEventListener('abort',abort);error?reject(error):resolve(data);};
    const abort=()=>finish(null,Error('Native request cancelled. Check Android before retrying an action already sent.'));
    const timer=setTimeout(()=>finish(null,Error('Android did not confirm this request. Check the current settings before trying again.')),timeoutMs);
    signal?.addEventListener('abort',abort,{once:true});
    unsubscribe=subscribeNative(host,'result',raw=>{
      let value;try{value=typeof raw==='string'?JSON.parse(raw):raw;}catch{return;}
      if(value?.requestId!==requestId)return;
      if(getNativeTransport(host)!==bridge){finish(null,Error('The Android connection changed. Reopen Settings to check the saved values.'));return;}
      if(value.ok!==true||value.error||!object(value.data)) {
        const message=typeof value.message==='string'?value.message:typeof value.error?.message==='string'?value.error.message:'Android could not confirm this request. Check permissions and try again.';
        finish(null,Error(message));return;
      }
      finish(value.data);
    });
    try{bridge.postMessage(JSON.stringify({action,arguments:args,requestId}));}
    catch{finish(null,Error('The Android connection is unavailable. Reopen ICARUS and try again.'));}
  });
}

export function modelStatusText(model) {
  if(!model)return 'Checking the model on this phone…';
  switch(model.state){
    case 'ready':return 'Downloaded and verified on this phone.';
    case 'downloaded_unverified':return 'Downloaded. Integrity verification runs when the local model is first used.';
    case 'downloading':return 'Downloading to this phone…';
    case 'paused':return 'Download paused. Check Wi-Fi, storage and the Android download notification.';
    case 'failed':return 'Download failed. Check Wi-Fi and available storage, then try again.';
    case 'not_downloaded':return 'Not downloaded on this phone.';
    default:return 'Android reported an unknown model state. Refresh before continuing.';
  }
}
