// Native voice runs in a foreground service. The web app supplies authentication,
// explicit opt-in controls, and a compatibility receiver for pre-36 hosts.
import {readVoiceSession} from './device-actions';

// Selection stays in memory in the web client. Android owns its durable voice
// selection; temporary messages and credentials are never kept in this object.
let chatContext={conversationId:undefined,temporary:false,revision:0};
let contextNeedsSync=false;
const request = (action, args={}) => {
  if(!window.IcarusNative?.postMessage)return false;
  try {window.IcarusNative.postMessage(JSON.stringify({action,arguments:args,requestId:crypto.randomUUID()}));return true;}
  catch {return false;}
};
export function getChatContext() {return {...chatContext};}
export function selectChatContext({conversationId,temporary=false}) {
  chatContext={conversationId:temporary?undefined:conversationId||undefined,temporary:temporary===true,revision:chatContext.revision+1};
  contextNeedsSync=true;
  syncVoiceSession();
  return getChatContext();
}
export function syncVoiceSession() {
  const context=contextNeedsSync?{conversationId:chatContext.conversationId||'',temporary:chatContext.temporary}:{};
  if(request('configure_voice_session',{token:localStorage.getItem('icarus_token')||'',...context}))contextNeedsSync=false;
}
export async function restoreChatContext() {
  if(contextNeedsSync)syncVoiceSession();
  const revision=chatContext.revision;
  const voice=await readVoiceSession();
  // A late status reply must never undo a new chat, temporary toggle, or send.
  if(revision!==chatContext.revision)return null;
  if(voice&&revision===chatContext.revision&&!contextNeedsSync) {
    const conversationId=voice.temporary?undefined:voice.conversationId||undefined;
    if(conversationId!==chatContext.conversationId||voice.temporary!==chatContext.temporary) {
      chatContext={conversationId,temporary:voice.temporary,revision:revision+1};
    }
  }
  return getChatContext();
}
export function installVoiceControls() {
  window.addEventListener('icarus-native-command', async event => {
    const text=event.detail?.transcript;
    if(!text) return;
    // Older hosts require the upgraded service; never silently discard a command.
    request('speak_text',{text:'Install ICARUS 1.6.5 or later to use the repaired hands-free command service.'});
  });
  window.addEventListener('icarus-native-ready',syncVoiceSession);
  const originalSet=Storage.prototype.setItem, originalRemove=Storage.prototype.removeItem;
  Storage.prototype.setItem=function(key,value){const previous=this.getItem(key);originalSet.call(this,key,value);if(this===localStorage&&key==='icarus_token'){if(previous!==String(value))selectChatContext({});else syncVoiceSession()}};
  Storage.prototype.removeItem=function(key){originalRemove.call(this,key);if(this===localStorage&&key==='icarus_token'){request('session_logout');selectChatContext({})}};
  // Bridge is installed asynchronously after page load.
  let tries=0;const timer=setInterval(()=>{if(window.IcarusNative?.postMessage){syncVoiceSession();clearInterval(timer)}else if(++tries>=30)clearInterval(timer)},500);
}
