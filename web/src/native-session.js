// Native voice runs in a foreground service. The web app supplies authentication,
// explicit opt-in controls, and a compatibility receiver for pre-36 hosts.
const request = (action, args={}) => window.IcarusNative?.postMessage(JSON.stringify({action,arguments:args,requestId:crypto.randomUUID()}));
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
  let tries=0;const timer=setInterval(()=>{if(window.IcarusNative?.postMessage){syncVoiceSession();clearInterval(timer)}else if(++tries>=30)clearInterval(timer)},500);
  const addControls=()=>{
    const host=document.querySelector('.action-status')?.parentElement||document.querySelector('.hero .copy');
    if(!host||host.querySelector('[data-voice-controls]'))return;
    const box=document.createElement('div');box.dataset.voiceControls='true';
    for(const [label,action,args] of [['Enable hands-free','wake_word',{enabled:true}],['Stop listening','wake_word',{enabled:false}],['Stop speaking','stop_speaking',{}]]){
      const button=document.createElement('button');button.className='secondary';button.textContent=label;
      button.onclick=()=>{
        if(!window.IcarusNative?.postMessage){message.textContent='These controls require the installed Android app.';return}
        if(action==='wake_word'&&args.enabled&&!confirm('Allow ICARUS to listen locally for Hey ICARUS in the background? Commands after wake may use Android speech recognition and your configured AI provider.'))return;
        request(action,args);message.textContent='Request sent. Check the ICARUS notification for current voice state.';
      };box.appendChild(button);
    }
    const message=document.createElement('p');message.setAttribute('role','status');box.appendChild(message);host.appendChild(box);
  };
  new MutationObserver(addControls).observe(document.getElementById('root'),{childList:true,subtree:true});
}
