import {getNativeTransport} from './native-transport.js';
import {subscribeNative} from './device-actions.js';

export function matchPlayOffers(config,native) {
  if(config?.checkoutEnabled!==true||native?.billingProtocolVersion!==2||!Array.isArray(native.products))return [];
  return (config.plans||[]).flatMap(plan=>{
    const matches=native.products.filter(p=>p.productId===plan.productId&&p.basePlanId===plan.basePlanId&&p.billingPeriod===plan.period&&typeof p.offerToken==='string'&&p.offerToken&&!p.offerToken.includes('|')&&typeof p.formattedPrice==='string'&&p.formattedPrice&&Number.isFinite(p.priceAmountMicros)&&p.priceAmountMicros>0&&typeof p.currencyCode==='string');
    // No fallback to a different offer or billing period, including free trials.
    return matches.length===1?[{...plan,...matches[0]}]:[];
  });
}
export function billingNative(action,args={},host=window,{signal,timeout=20000}={}) {
  return new Promise((resolve,reject)=>{
    const bridge=getNativeTransport(host);if(!bridge){reject(Error('Open the installed ICARUS app to use Google Play.'));return;}
    if(signal?.aborted){reject(Error('Request cancelled.'));return;}
    const requestId=crypto.randomUUID();let settled=false,unsubscribe=()=>{};
    const finish=(value,error)=>{if(settled)return;settled=true;clearTimeout(timer);unsubscribe();signal?.removeEventListener('abort',abort);error?reject(error):resolve(value);};
    const abort=()=>finish(null,Error('Request cancelled.'));
    const timer=setTimeout(()=>finish(null,Error('Google Play did not confirm the result. Use Restore purchases before trying again.')),timeout);
    unsubscribe=subscribeNative(host,'result',raw=>{
      let response;try{response=typeof raw==='string'?JSON.parse(raw):raw;}catch{return;}
      if(response?.requestId!==requestId)return;
      if(getNativeTransport(host)!==bridge)return finish(null,Error('Android connection changed. Reopen Settings.'));
      if(response.ok!==true||response.error||!response.data)return finish(null,Error(String(response.error||'Google Play returned an invalid result.').replaceAll('_',' ')));
      finish(response.data);
    });
    signal?.addEventListener('abort',abort,{once:true});
    try{bridge.postMessage(JSON.stringify({action,arguments:args,requestId}));}catch{finish(null,Error('Android billing connection is unavailable.'));}
  });
}
