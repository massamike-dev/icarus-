import {validateCommand} from '../server/commands.js';
import {getNativeTransport} from './native-transport.js';

const subscriptions=new WeakMap(),statusChecks=new WeakMap();
const channels={status:'ICARUS_NATIVE_STATUS',result:'ICARUS_NATIVE_RESULT'};
const object=value=>Boolean(value)&&typeof value==='object'&&!Array.isArray(value);
function payload(raw) {try {const value=typeof raw==='string'?JSON.parse(raw):raw;return object(value)?value:null;}catch{return null;}}
const supported=status=>status?.actionProtocolVersion===1;

// Each native callback has one dispatcher. Subscribers can leave in any order;
// removing one never restores another component's already-unmounted callback.
export function subscribeNative(host,channel,callback) {
  const property=Object.hasOwn(channels,channel)?channels[channel]:null;
  if(!property||typeof callback!=='function')throw new TypeError('Invalid native subscription');
  let state=subscriptions.get(host);
  if(!state){state=new Map();subscriptions.set(host,state);}
  let entry=state.get(property);
  if(!entry){
    const previous=host[property],listeners=new Set();
    const dispatch=raw=>{
      for(const listener of [...listeners]) {
        if(listeners.has(listener))try{listener(raw);}catch{/* A consumer cannot block other correlated requests. */}
      }
      if(typeof previous==='function')try{previous(raw);}catch{/* Preserve native delivery to active subscribers. */}
    };
    entry={previous,listeners,dispatch};state.set(property,entry);host[property]=dispatch;
  }
  entry.listeners.add(callback);
  let active=true;
  return ()=>{
    if(!active)return;active=false;entry.listeners.delete(callback);
    if(!entry.listeners.size){
      if(host[property]===entry.dispatch)host[property]=entry.previous;
      if(state.get(property)===entry)state.delete(property);
      if(!state.size)subscriptions.delete(host);
    }
  };
}

export function readDeviceStatus(host=window,timeoutMs=3000) {
  const bridge=getNativeTransport(host);
  if(!bridge)return Promise.resolve(null);
  const pending=statusChecks.get(host);
  if(pending?.bridge===bridge)return pending.promise;
  const entry={bridge,promise:null};
  const promise=new Promise(resolve=>{
    const requestId=crypto.randomUUID();let settled=false,unsubscribe=()=>{};
    const finish=value=>{if(settled)return;settled=true;clearTimeout(timer);unsubscribe();resolve(value);};
    const timer=setTimeout(()=>finish(null),timeoutMs);
    unsubscribe=subscribeNative(host,'status',raw=>{const value=payload(raw);if(value?.requestId===requestId)finish(getNativeTransport(host)===bridge?value:null);});
    try{bridge.postMessage(JSON.stringify({bridgeRequest:'status',requestId}));}catch{finish(null);}
  }).finally(()=>{if(statusChecks.get(host)===entry)statusChecks.delete(host);});
  entry.promise=promise;statusChecks.set(host,entry);return promise;
}

export async function checkDevice(host=window) {return supported(await readDeviceStatus(host));}

export async function readVoiceSession(host=window) {
  const status=await readDeviceStatus(host),session=status?.voiceSession;
  if(!supported(status)||!object(session)||typeof session.temporary!=='boolean')return null;
  const id=session.conversationId;
  if(id!==null&&(typeof id!=='string'||!id||id.length>128||/[\u0000-\u001f\u007f]/.test(id)))return null;
  // A private voice session must never reattach a saved conversation.
  if(session.temporary&&id!==null)return null;
  return {conversationId:id,temporary:session.temporary};
}

export function actionRequest(proposal) {
  if(!object(proposal)||typeof proposal.action!=='string'||(proposal.value!==undefined&&typeof proposal.value!=='string'))throw Error('Invalid action proposal');
  if((proposal.value?.length||0)>250||/[\u0000-\u001f\u007f]/.test(proposal.value||''))throw Error('Invalid action value');
  const {action,value}=validateCommand(proposal);
  if(['get_battery','stop_listening'].includes(action)&&value)throw Error('Unexpected action value');
  const map={get_battery:['get_battery',{},'Check battery'],toggle_flashlight:['toggle_flashlight',{enabled:value==='on'},`Turn flashlight ${value}`],set_volume:['set_volume',{level:Number(value)},`Set media volume to ${value}%`],make_call:['make_call',{[/^\+?[0-9 ()-]{3,}$/.test(value)?'number':'contact']:value},`Call ${value}`],navigate_to:['navigate_to',{destination:value},`Open maps for ${value}`],open_app:['open_app',{appName:value},`Open ${value}`],set_timer:['set_timer',{seconds:Number(value)},`Request a ${value}-second timer`],stop_listening:['wake_word',{enabled:false},'Stop hands-free listening']};
  if(!map[action])throw Error('Unsupported action');
  const [nativeAction,args,label]=map[action];
  return {action:nativeAction,arguments:args,label};
}

const unknown='Android returned an incomplete or inconsistent result. Completion is unknown; check your phone before retrying.';
function resultMessage(result,request) {
  if(result.ok===false||typeof result.error==='string'&&result.error.length>0){
    const error=typeof result.error==='string'?result.error.slice(0,120).replaceAll('_',' '):'unknown error';
    return `Android could not complete this action (${error}). Check permissions or the requested target. Nothing is confirmed.`;
  }
  if(result.ok!==true||result.error!=null||!object(result.data))return unknown;
  const d=result.data;
  switch(request.action){
    case 'get_battery':return Number.isFinite(d.level)&&d.level>=0&&d.level<=100&&typeof d.charging==='boolean'?`Battery: ${d.level}%. ${d.charging?'Charging.':'Not charging.'}`:unknown;
    case 'toggle_flashlight':return d.enabled===request.arguments.enabled?`Android confirmed flashlight ${d.enabled?'on':'off'}.`:unknown;
    case 'set_volume':return Number.isFinite(d.level)&&d.level>=0&&d.level<=100?`Android reported media volume at ${d.level}%.`:unknown;
    case 'wake_word':return d.enabled===false&&d.listenerState==='STOPPED'?'Android acknowledged the stop-listening request.':unknown;
    default:{
      const targetValid=request.action==='open_app'?typeof d.app==='string'&&Boolean(d.app.trim()):request.action==='navigate_to'?d.destination===request.arguments.destination:request.action==='set_timer'?d.seconds===request.arguments.seconds:request.action==='make_call'?typeof d.number==='string'&&/^\+?[0-9 ()-]{3,}$/.test(d.number)&&d.requestAccepted===true:false;
      return d.executionStatus==='request_accepted'&&targetValid?'Android accepted the request. Check the target app for completion; ICARUS has not independently verified it.':unknown;
    }
  }
}

export async function executeProposal(proposal,host=window,timeoutMs=10000,{signal}={}) {
  const request=actionRequest(proposal),cancelled='Cancelled before the action was sent to Android. Nothing was sent.';
  if(signal?.aborted)return cancelled;
  const bridge=getNativeTransport(host);
  if(!bridge)return 'Open the installed Android app to perform phone actions. Nothing was sent.';
  const ready=await checkDevice(host);
  if(signal?.aborted)return cancelled;
  if(!ready||getNativeTransport(host)!==bridge)return 'This Android version has not confirmed support for reviewed Chat actions. Update ICARUS before trying again. Nothing was sent.';
  return new Promise(resolve=>{
    const requestId=crypto.randomUUID();let settled=false,unsubscribe=()=>{};
    const finish=message=>{if(settled)return;settled=true;clearTimeout(timer);unsubscribe();resolve(message);};
    const timer=setTimeout(()=>finish('Android did not confirm the result. The action may have started; check your phone before retrying.'),timeoutMs);
    unsubscribe=subscribeNative(host,'result',raw=>{const result=payload(raw);if(result?.requestId===requestId)finish(resultMessage(result,request));});
    // After dispatch, keep waiting even if the view unmounts: the action cannot
    // be recalled, and its caller can still persist the actual acknowledgement.
    try{bridge.postMessage(JSON.stringify({action:request.action,arguments:request.arguments,requestId}));}
    catch{finish('The Android connection failed. The result is unknown; check your phone before retrying.');}
  });
}
