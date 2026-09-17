import {validateCommand} from '../server/commands.js';

const statusChecks=new WeakMap();
export function checkDevice(host=window) {
  if(statusChecks.has(host))return statusChecks.get(host);
  const promise=readDevice(host).finally(()=>statusChecks.delete(host));statusChecks.set(host,promise);return promise;
}
function readDevice(host) {
  if(!host.IcarusNative?.postMessage)return Promise.resolve(false);
  return new Promise(resolve=>{
    const requestId=crypto.randomUUID(),previous=host.ICARUS_NATIVE_STATUS;
    const finish=value=>{clearTimeout(timer);if(host.ICARUS_NATIVE_STATUS===receive)host.ICARUS_NATIVE_STATUS=previous;resolve(value);};
    const receive=raw=>{let r;try{r=typeof raw==='string'?JSON.parse(raw):raw;}catch{return;}if(r?.requestId===requestId)finish(r.actionProtocolVersion>=1);else if(typeof previous==='function')previous(raw);};
    const timer=setTimeout(()=>finish(false),3000);host.ICARUS_NATIVE_STATUS=receive;
    try{host.IcarusNative.postMessage(JSON.stringify({bridgeRequest:'status',requestId}));}catch{finish(false);}
  });
}

export function actionRequest(proposal) {
  const {action,value}=validateCommand(proposal);
  const map={get_battery:['get_battery',{},'Check battery'],toggle_flashlight:['toggle_flashlight',{enabled:value==='on'},`Turn flashlight ${value}`],set_volume:['set_volume',{level:Number(value)},`Set media volume to ${value}%`],make_call:['make_call',{[/^\+?[0-9 ()-]{3,}$/.test(value)?'number':'contact']:value},`Call ${value}`],navigate_to:['navigate_to',{destination:value},`Open maps for ${value}`],open_app:['open_app',{appName:value},`Open ${value}`],set_timer:['set_timer',{seconds:Number(value)},`Request a ${value}-second timer`],stop_listening:['wake_word',{enabled:false},'Stop hands-free listening']};
  if(!map[action])throw Error('Unsupported action');
  const [nativeAction,args,label]=map[action];
  return {action:nativeAction,arguments:args,label};
}

export async function executeProposal(proposal, host=window, timeoutMs=10000) {
  const request=actionRequest(proposal);
  if(!host.IcarusNative?.postMessage)return Promise.resolve('Open the installed Android app to perform phone actions. Nothing was sent.');
  if(!await checkDevice(host))return 'This Android version has not confirmed support for reviewed Chat actions. Update ICARUS before trying again. Nothing was sent.';
  return new Promise(resolve=>{
    const requestId=crypto.randomUUID(),previous=host.ICARUS_NATIVE_RESULT;
    let settled=false;
    const finish=message=>{if(settled)return;settled=true;clearTimeout(timer);if(host.ICARUS_NATIVE_RESULT===receive)host.ICARUS_NATIVE_RESULT=previous;resolve(message);};
    const receive=raw=>{
      let r;try{r=typeof raw==='string'?JSON.parse(raw):raw;}catch{return;}
      if(r?.requestId!==requestId){if(typeof previous==='function')previous(raw);return;}
      if(r.ok!==true||r.error){finish(`Android could not complete this action (${String(r.error||'unknown error').replaceAll('_',' ')}). Check permissions or the requested target. Nothing is confirmed.`);return;}
      const d=r.data||{};
      if(proposal.action==='get_battery'&&Number.isFinite(d.level)&&d.level>=0&&d.level<=100)finish(`Battery: ${d.level}%. ${d.charging?'Charging.':'Not charging.'}`);
      else if(proposal.action==='toggle_flashlight'&&typeof d.enabled==='boolean')finish(`Android confirmed flashlight ${d.enabled?'on':'off'}.`);
      else if(proposal.action==='set_volume'&&Number.isFinite(d.level))finish(`Android reported media volume at ${d.level}%.`);
      else if(proposal.action==='stop_listening')finish('Android acknowledged the stop-listening request.');
      else finish('Android accepted the request. Check the target app for completion; ICARUS has not independently verified it.');
    };
    const timer=setTimeout(()=>finish('Android did not confirm the result. The action may have started; check your phone before retrying.'),timeoutMs);
    host.ICARUS_NATIVE_RESULT=receive;
    try{host.IcarusNative.postMessage(JSON.stringify({action:request.action,arguments:request.arguments,requestId}));}
    catch{finish('The Android connection failed. The result is unknown; check your phone before retrying.');}
  });
}
