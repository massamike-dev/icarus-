// Native voice runs in a foreground service. The web app supplies authentication,
// explicit opt-in controls, and a compatibility receiver for pre-36 hosts.
import {getNativeTransport} from './native-transport.js';

const request = (action, args={}) => getNativeTransport()?.postMessage(JSON.stringify({action,arguments:args,requestId:crypto.randomUUID()}));
export function syncVoiceSession() { request('configure_voice_session',{token:localStorage.getItem('icarus_token')||''}); }
export function installVoiceControls() {
  window.addEventListener('icarus-native-command', async event => {
    const text=event.detail?.transcript;
    if(!text) return;
    // Older hosts require the upgraded service; never silently discard a command.
    request('speak_text',{text:'Install ICARUS 1.6.5 or later to use the repaired hands-free command service.'});
  });
  window.addEventListener('icarus-native-ready',syncVoiceSession);
  const originalSet=Storage.prototype.setItem, originalRemove=Storage.prototype.removeItem;
  Storage.prototype.setItem=function(key,value){originalSet.call(this,key,value);if(this===localStorage&&key==='icarus_token')syncVoiceSession()};
  Storage.prototype.removeItem=function(key){originalRemove.call(this,key);if(this===localStorage&&key==='icarus_token'){request('session_logout');syncVoiceSession()}};
  // Bridge is installed asynchronously after page load.
  let tries=0;const timer=setInterval(()=>{if(getNativeTransport()){syncVoiceSession();clearInterval(timer)}else if(++tries>=30)clearInterval(timer)},500);
}
