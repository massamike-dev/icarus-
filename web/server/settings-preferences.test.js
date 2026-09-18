import test from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
import {fileURLToPath} from 'node:url';
import {requestNativeSetting} from '../src/native-settings.js';

const root=fileURLToPath(new URL('../',import.meta.url));
const bundle=await build({root,logLevel:'silent',build:{write:false,minify:false}});
const code=bundle.output.find(value=>value.type==='chunk'&&value.isEntry).code;
const waitFor=async(condition,message='Settings did not reach the expected state')=>{for(let i=0;i<100;i++){if(condition())return;await new Promise(resolve=>setTimeout(resolve,10));}assert.fail(message);};
const voice={supported:true,available:true,profile:'deep_warm',voiceName:'',rate:.88,pitch:.82,engine:'Test speech',voices:[{name:'en-test',label:'English installed voice',locale:'en-US',networkRequired:false}]};
async function mount({native=true,support=true,reply}={}){
  const dom=new JSDOM('<div id="root"></div>',{url:'https://icarus.test',runScripts:'outside-only'}),w=dom.window,requests=[],errors=[];
  w.scrollTo=()=>{};w.AbortSignal.timeout=()=>new w.AbortController().signal;
  w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;};
  w.addEventListener('error',event=>{errors.push(event.error);event.preventDefault();});
  w.fetch=async url=>({ok:true,json:async()=>url==='/api/me'?{user:{name:'Test User',email:'test@example.invalid'}}:url==='/api/memories'?{memories:[]}:{conversations:[]}});
  const status={actionProtocolVersion:1,connected:true,wakeWord:{listenerState:'STOPPED',enabled:false,talkNowSupported:true,...(support?{sensitivity:60}:{})},...(support?{voiceSettings:{supported:true},capabilities:['local_model_status']}: {})};
  let model={state:'not_downloaded',model:'Gemma'};
  const deliver=(request,value)=>w.ICARUS_NATIVE_RESULT?.(JSON.stringify({requestId:request.requestId,...value}));
  if(native)w.ICARUS_NATIVE_CHANNEL={postMessage:raw=>{const request=JSON.parse(raw);requests.push(request);if(request.bridgeRequest){setTimeout(()=>w.ICARUS_NATIVE_STATUS?.(JSON.stringify({...status,requestId:request.requestId})),0);return;}if(!request.action)return;
    setTimeout(()=>{if(reply?.(request,deliver)===false)return;
      let data={};if(request.action==='get_voice_settings')data={...voice};if(request.action==='set_voice_settings')data={...voice,...request.arguments,saved:true};if(request.action==='wake_config')data={saved:true,sensitivity:request.arguments.sensitivity,appliesAfterRestart:true};if(request.action==='local_model_status')data=model;if(request.action==='download_local_model'){model={state:'downloading',downloadedBytes:0,totalBytes:2588147712};data=model;}if(request.action==='delete_local_model'){model={state:'not_downloaded',deleted:true};data=model;}deliver(request,{ok:true,data});},0);
  }};
  w.eval(code);await waitFor(()=>w.document.querySelector('nav'));
  const button=label=>[...w.document.querySelectorAll('button')].find(value=>value.textContent===label);
  const click=async label=>{assert.ok(button(label),`Missing button: ${label}`);button(label).click();await new Promise(resolve=>setTimeout(resolve,20));};
  const select=async(id,value)=>{const control=w.document.getElementById(id);assert.ok(control);control.value=value;control.dispatchEvent(new w.Event('change',{bubbles:true}));await new Promise(resolve=>setTimeout(resolve,10));};
  await click('Settings');if(native&&support)await waitFor(()=>w.document.getElementById('voice-profile'));
  return {dom,w,requests,errors,click,select,button};
}

test('Settings restore voice first, save presets before preview and preserve unsaved tab drafts',async()=>{
  const app=await mount();try{
    assert.equal(app.w.document.querySelector('.settings-section h2').textContent,'ICARUS voice');
    assert.equal(app.w.document.getElementById('voice-profile').value,'deep_warm');
    assert.equal(app.button('Stop preview').disabled,true);
    await app.select('voice-profile','standard');assert.equal(app.w.document.getElementById('voice-rate').value,'1');assert.equal(app.w.document.getElementById('voice-pitch').value,'1');assert.equal(app.button('Preview saved voice').disabled,true);
    await app.click('Memory');await app.click('Settings');await waitFor(()=>app.w.document.getElementById('voice-profile'));assert.equal(app.w.document.getElementById('voice-profile').value,'standard');
    await app.click('Save voice');await waitFor(()=>app.w.document.body.textContent.includes('Voice settings saved on this phone'));
    assert.deepEqual(app.requests.filter(value=>value.action==='set_voice_settings').map(value=>value.arguments),[{profile:'standard',voiceName:'',rate:1,pitch:1}]);
    assert.equal(app.button('Preview saved voice').disabled,false);await app.click('Preview saved voice');await waitFor(()=>app.w.document.body.textContent.includes('Voice preview finished'));
    assert.equal(app.requests.filter(value=>value.action==='preview_voice').length,1);assert.deepEqual(app.errors,[]);
  }finally{app.dom.window.close();}
});

test('failed save keeps the selected voice and never claims saved or enables preview',async()=>{
  const app=await mount({reply:(request,deliver)=>{if(request.action!=='set_voice_settings')return;deliver(request,{ok:false,error:'voice_unavailable',message:'Choose another installed voice.'});return false;}});
  try{await app.select('voice-name','en-test');await app.click('Save voice');await waitFor(()=>app.w.document.body.textContent.includes('Choose another installed voice.'));assert.equal(app.w.document.getElementById('voice-name').value,'en-test');assert.equal(app.button('Preview saved voice').disabled,true);assert.equal(app.button('Save voice').disabled,false);assert.doesNotMatch(app.w.document.body.textContent,/Voice settings saved on this phone/);assert.deepEqual(app.errors,[]);}finally{app.dom.window.close();}
});

test('mismatched save acknowledgement does not commit the draft',async()=>{
  const app=await mount({reply:(request,deliver)=>{if(request.action!=='set_voice_settings')return;deliver(request,{ok:true,data:{...voice,saved:true}});return false;}});
  try{await app.select('voice-profile','standard');await app.click('Save voice');await waitFor(()=>app.w.document.body.textContent.includes('did not confirm these exact settings'));assert.equal(app.w.document.getElementById('voice-profile').value,'standard');assert.equal(app.button('Preview saved voice').disabled,true);}finally{app.dom.window.close();}
});

test('browser and older Android builds explain availability without dispatching unsupported settings',async()=>{
  for(const options of [{native:false},{support:false}]){const app=await mount(options);try{await new Promise(resolve=>setTimeout(resolve,30));assert.equal(app.w.document.getElementById('voice-profile'),null);assert.match(app.w.document.body.textContent,options.native===false?/Open ICARUS Test on your phone/:/Install the updated ICARUS Test build/);assert.equal(app.requests.filter(value=>['get_voice_settings','set_voice_settings','preview_voice','download_local_model'].includes(value.action)).length,0);assert.deepEqual(app.errors,[]);}finally{app.dom.window.close();}}
});

test('model download respects Wi-Fi preference and deletion requires its own confirmation',async()=>{
  const app=await mount();try{await waitFor(()=>app.button('Download local model')&&!app.button('Download local model').disabled);await app.click('Download local model');assert.deepEqual(app.requests.find(value=>value.action==='download_local_model').arguments,{wifiOnly:true});await app.click('Delete local model');assert.equal(app.requests.filter(value=>value.action==='delete_local_model').length,0);const dialog=app.w.document.querySelector('[aria-labelledby="delete-local-model-title"]');assert.equal(dialog.open,true);assert.equal(app.w.document.activeElement.textContent,'Cancel');[...dialog.querySelectorAll('button')].find(value=>value.textContent==='Delete model').click();await waitFor(()=>app.w.document.body.textContent.includes('Local model deleted from this phone.'));assert.equal(app.requests.filter(value=>value.action==='delete_local_model').length,1);assert.deepEqual(app.errors,[]);}finally{app.dom.window.close();}
});

test('native settings acknowledgements correlate and timeout without reporting success',async()=>{
  const requests=[],host={ICARUS_NATIVE_CHANNEL:{postMessage:raw=>requests.push(JSON.parse(raw))}};
  const result=requestNativeSetting('set_voice_settings',{profile:'standard'},host,50);
  host.ICARUS_NATIVE_RESULT(JSON.stringify({requestId:'unrelated',ok:true,data:{saved:true}}));
  await assert.rejects(result,/did not confirm this request/);
  assert.equal(requests.length,1);assert.equal(host.ICARUS_NATIVE_RESULT,undefined);
});
