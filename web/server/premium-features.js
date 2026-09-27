// The owner chooses the paywall; no previously-free feature is silently locked.
export const PREMIUM_FEATURE_LABELS=Object.freeze({'cloud-chat':'Cloud-assisted Chat','cloud-voice':'Cloud voice requests','saved-memory':'Saved memories'});
export function premiumFeatures(env={}){return new Set(String(env.ICARUS_PREMIUM_FEATURES||'').split(',').map(v=>v.trim()).filter(v=>Object.hasOwn(PREMIUM_FEATURE_LABELS,v)));}
export function requiredPremiumFeature(path,method,env={}) {
  let feature=null;
  if(method==='POST'&&['/api/chat','/api/chat/temporary','/api/functions/nativeConversationTurn'].includes(path))feature='cloud-chat';
  if(method==='POST'&&path==='/api/commands/interpret')feature='cloud-voice';
  if(['GET','POST'].includes(method)&&path==='/api/memories')feature='saved-memory';
  return premiumFeatures(env).has(feature)?feature:null;
}
