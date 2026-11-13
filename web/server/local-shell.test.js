import test from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
import {fileURLToPath} from 'node:url';

const bundle=await build({root:fileURLToPath(new URL('../',import.meta.url)),logLevel:'silent',build:{write:false,minify:false}});
const code=bundle.output.find(value=>value.type==='chunk'&&value.isEntry).code;
const waitFor=async(fn)=>{for(let i=0;i<150;i++){if(fn())return;await new Promise(resolve=>setTimeout(resolve,10));}assert.fail('Local shell did not reach expected state');};
async function mount(snapshot){
 const dom=new JSDOM('<div id="root"></div>',{url:'https://appassets.androidplatform.net/assets/web/index.html',runScripts:'outside-only'}),w=dom.window,requests=[],fetches=[];
 if(snapshot)w.localStorage.setItem('icarus_local_data_v1',snapshot);
 w.scrollTo=()=>{};w.AbortSignal.timeout=()=>new w.AbortController().signal;
 w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;};
 w.fetch=async(...args)=>{fetches.push(args);throw Error('Offline: network forbidden in this test');};
 w.ICARUS_NATIVE_CHANNEL={postMessage(raw){const request=JSON.parse(raw);requests.push(request);queueMicrotask(()=>{
  if(request.bridgeRequest==='status')w.ICARUS_NATIVE_STATUS?.(JSON.stringify({requestId:request.requestId,connected:true,actionProtocolVersion:1,runtime:{mode:'local',cloudAvailable:true},capabilities:[],voiceSession:{temporary:false,conversationId:null},wakeWord:{listenerState:'STOPPED'}}));
  else if(request.action==='set_runtime_mode')w.ICARUS_NATIVE_RESULT?.(JSON.stringify({requestId:request.requestId,ok:true,data:{mode:request.arguments.mode}}));
 });}};
 w.eval(code);await waitFor(()=>w.document.querySelector('nav'));
 const button=text=>[...w.document.querySelectorAll('button')].find(b=>b.textContent.trim()===text);
 const click=async text=>{assert.ok(button(text),`Missing ${text}`);button(text).click();await new Promise(resolve=>setTimeout(resolve,30));};
 return{dom,w,requests,fetches,button,click};
}

test('bundled dashboard opens offline without an account or API fetch',async()=>{
 const app=await mount();try{
  assert.ok(app.w.document.querySelector('nav'));
  assert.equal(app.w.document.querySelector('input[type=password]'),null);
  await app.click('Settings');
  await waitFor(()=>app.button('Sign in to ICARUS account'));
  assert.equal(app.button('Sign out'),undefined);
  assert.match(app.w.document.body.textContent,/No account or per-message AI payment required/);
  assert.equal(app.w.document.querySelector('.premium-plans'),null);
  assert.equal(app.w.document.querySelector('.integrations-panel'),null);
  assert.deepEqual(app.fetches,[]);
 }finally{app.dom.window.close();}
});

test('optional cloud requires explicit review; cancel and duplicate clicks do not switch',async()=>{
 const app=await mount();try{
  await app.click('Settings');await waitFor(()=>app.button('Sign in to ICARUS account'));
  await app.click('Sign in to ICARUS account');
  assert.equal(app.w.document.querySelector('[aria-labelledby=cloud-mode-title]').open,true);
  assert.equal(app.w.document.activeElement.textContent,'Stay on device');
  assert.equal(app.requests.filter(x=>x.action==='set_runtime_mode').length,0);
  await app.click('Stay on device');
  assert.equal(app.requests.filter(x=>x.action==='set_runtime_mode').length,0);
  await app.click('Sign in to ICARUS account');
  app.button('Continue to sign in').click();app.button('Continue to sign in').click();
  await waitFor(()=>app.requests.some(x=>x.action==='set_runtime_mode'));
  assert.deepEqual(app.requests.filter(x=>x.action==='set_runtime_mode').map(x=>x.arguments),[{mode:'cloud'}]);
  assert.deepEqual(app.fetches,[]);
 }finally{app.dom.window.close();}
});

test('model setup explains verification and unsupported-device states',async()=>{
 const {modelStatusText}=await import('../src/native-settings.js');
 assert.match(modelStatusText({state:'verifying'}),/Verifying/);
 assert.match(modelStatusText({state:'unsupported_device'}),/does not support/);
 assert.match(modelStatusText({state:'not_downloaded'}),/Download once/);
});


test('visible Sign in opens the account entry without changing mode',async()=>{
 const app=await mount();try{
  await app.click('Sign in');
  await waitFor(()=>app.button('Sign in to ICARUS account'));
  assert.match(app.w.document.querySelector('h1').textContent,/Your ICARUS account/);
  assert.equal(app.requests.filter(x=>x.action==='set_runtime_mode').length,0);
 }finally{app.dom.window.close();}
});

test('explicit memory survives a fresh workspace and remains local',async()=>{
 let snapshot;const app=await mount();try{
  await app.click('Memory');await waitFor(()=>app.button('Remember')&&!app.button('Remember').disabled);
  const input=app.w.document.querySelector('input[name=content]');input.value='My favorite color is blue';
  app.w.document.querySelector('.memory-add').dispatchEvent(new app.w.Event('submit',{bubbles:true,cancelable:true}));
  await waitFor(()=>app.w.document.querySelector('.memory-list article'));
  assert.match(app.w.document.body.textContent,/Saved on this device/);
  snapshot=app.w.localStorage.getItem('icarus_local_data_v1');assert.deepEqual(app.fetches,[]);
 }finally{app.dom.window.close();}
 const reopened=await mount(snapshot);try{
  await reopened.click('Memory');await waitFor(()=>reopened.w.document.querySelector('.memory-list article'));
  assert.match(reopened.w.document.querySelector('.memory-list').textContent,/My favorite color is blue/);
  assert.deepEqual(reopened.fetches,[]);
 }finally{reopened.dom.window.close();}
});

test('unreadable memory is an error and is never presented as empty or erased',async()=>{
 const app=await mount();try{
  app.w.localStorage.setItem('icarus_local_data_v1','corrupt retained data');
  await app.click('Memory');await waitFor(()=>app.w.document.querySelector('.memory-workspace [role=alert]'));
  assert.doesNotMatch(app.w.document.querySelector('.memory-workspace').textContent,/No memories saved/);
  assert.equal(app.w.localStorage.getItem('icarus_local_data_v1'),'corrupt retained data');
  assert.equal(app.button('Remember').disabled,true);
  assert.equal(app.button('Refresh memories').disabled,false);
 }finally{app.dom.window.close();}
});

test('driving design preview is opt-in and does not start navigation',async()=>{
 const app=await mount();try{
  await app.click('Vehicle');assert.equal(app.w.document.querySelector('.race-preview'),null);
  await app.click('Preview driving graphics');
  assert.match(app.w.document.querySelector('.race-preview').textContent,/NOT LIVE NAVIGATION/);
  await app.click('Glasses HUD layout');assert.equal(app.w.document.querySelector('.race-map'),null);
  assert.match(app.w.document.querySelector('.race-preview').textContent,/FORWARD VIEW KEPT CLEAR/);
  assert.equal(app.requests.filter(x=>['navigate_to','open_driving_hud','open_xreal_hud'].includes(x.action)).length,0);
 }finally{app.dom.window.close();}
});
