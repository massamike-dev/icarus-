import test from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
import {fileURLToPath} from 'node:url';
import {hudResultMessage,validHudStatus} from '../src/hud-controls.js';
import {requestNativeSetting} from '../src/native-settings.js';
import {subscribeNative} from '../src/device-actions.js';

const root=fileURLToPath(new URL('../',import.meta.url));
const bundle=await build({root,logLevel:'silent',build:{write:false,minify:false}});
const code=bundle.output.find(value=>value.type==='chunk'&&value.isEntry).code;
const pause=(ms=15)=>new Promise(resolve=>setTimeout(resolve,ms));
const waitFor=async(condition,message='HUD did not reach the expected state')=>{
  for(let i=0;i<100;i++){if(condition())return;await pause(10);}
  assert.fail(message);
};
const voice={supported:true,available:true,profile:'deep_warm',voiceName:'',rate:.88,pitch:.82,voices:[]};
const hudActions=new Set(['xreal_status','open_driving_hud','open_xreal_hud','close_xreal_hud','meta_integration_set']);

// Mount the actual App, not an isolated Vehicle mock: this covers navigation,
// the permanent companion and the shared native callback dispatcher together.
async function mount({native=true,version=1,enabled=false,runtimeAvailable=true,reply}={}){
  const dom=new JSDOM('<div id="root"></div>',{url:'https://icarus.test',runScripts:'outside-only',pretendToBeVisual:true});
  const w=dom.window,requests=[],errors=[],legacyRequests=[],observedResults=[];
  const previousResult=raw=>observedResults.push(raw);
  w.ICARUS_NATIVE_RESULT=previousResult;
  w.scrollTo=()=>{};
  w.AbortSignal.timeout=()=>new w.AbortController().signal;
  w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
  w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
  w.addEventListener('error',event=>{errors.push(event.error);event.preventDefault();});
  w.fetch=async url=>({ok:true,json:async()=>url==='/api/me'?{user:{name:'Michael',email:'test@example.invalid'}}:url==='/api/memories'?{memories:[]}:url==='/api/capabilities'?{}:{conversations:[]}});
  const status={connected:true,actionProtocolVersion:1,...(version==='missing'?{}:{hudControlVersion:version}),voiceSettings:{supported:true},capabilities:['local_model_status'],wakeWord:{enabled:true,permissionGranted:true,listenerState:'LISTENING',talkNowSupported:true,sensitivity:60}};
  const deliver=(request,result)=>w.ICARUS_NATIVE_RESULT?.(JSON.stringify({requestId:request.requestId,...result}));
  if(native){
    w.IcarusNative={postMessage:raw=>legacyRequests.push(raw)};
    w.ICARUS_NATIVE_CHANNEL={postMessage:raw=>{
      const request=JSON.parse(raw);requests.push(request);
      if(request.bridgeRequest){w.setTimeout(()=>w.ICARUS_NATIVE_STATUS?.(JSON.stringify({...status,requestId:request.requestId})),0);return;}
      if(!request.action)return;
      w.setTimeout(()=>{
        if(reply?.(request,deliver)===false)return;
        let data={};
        if(request.action==='xreal_status')data={provider:'xreal',enabled,runtimeAvailable};
        if(request.action==='open_driving_hud')data={opened:true};
        if(request.action==='open_xreal_hud')data={launched:true,runtimeAvailable,mode:request.arguments.mode};
        if(request.action==='close_xreal_hud')data={closeRequested:true};
        if(request.action==='meta_integration_set'){enabled=request.arguments.enabled;data={provider:'xreal',enabled};}
        if(request.action==='get_voice_settings')data=voice;
        if(request.action==='local_model_status')data={state:'not_downloaded',model:'Gemma'};
        if(request.action==='start_voice_turn')data={requestAccepted:true};
        deliver(request,{ok:true,data});
      },0);
    }};
  }
  w.eval(code);await waitFor(()=>w.document.querySelector('nav'));
  const button=(label,scope=w.document)=>[...scope.querySelectorAll('button')].find(value=>value.textContent===label||value.getAttribute('aria-label')===label);
  const click=async(label,scope)=>{const control=button(label,scope);assert.ok(control,`Missing button: ${label}`);control.click();await pause();};
  await click('Vehicle');
  await waitFor(()=>w.document.querySelector('.hud-options')?.getAttribute('aria-busy')==='false');
  return {dom,w,requests,errors,legacyRequests,observedResults,previousResult,deliver,button,click,feedback:()=>w.document.querySelector('.hud-feedback').textContent};
}

test('Vehicle mounts in App and launches the phone HUD once through the canonical native channel',async()=>{
  const app=await mount({reply:request=>request.action==='open_driving_hud'?false:undefined});
  try{
    assert.equal(app.w.document.querySelector('h1').textContent,'Your HUD, within reach.');
    assert.equal(app.w.document.querySelectorAll('.icarus-companion').length,1);
    assert.equal(app.requests.filter(value=>value.action==='xreal_status').length,1);
    const open=app.button('Open phone HUD');open.click();open.click();await pause();
    const sent=app.requests.filter(value=>value.action==='open_driving_hud');
    assert.equal(sent.length,1);assert.deepEqual(sent[0].arguments,{});assert.equal(open.disabled,true);
    app.deliver({requestId:'unrelated'},{ok:true,data:{opened:true}});await pause();
    assert.match(app.feedback(),/Waiting for Android/);assert.doesNotMatch(app.feedback(),/accepted the phone HUD/);
    app.deliver(sent[0],{ok:true,data:{opened:true}});
    await waitFor(()=>app.feedback().includes('accepted the phone HUD launch'));
    assert.match(app.feedback(),/require a connected OBD adapter/);
    assert.equal(open.disabled,false);assert.deepEqual(app.legacyRequests,[]);assert.deepEqual(app.errors,[]);
  }finally{app.dom.window.close();}
});

test('optional XREAL requires explicit enable and disable confirmation; cancel sends nothing',async()=>{
  const app=await mount();
  try{
    assert.equal(app.button('Open assistant HUD').disabled,true);
    assert.equal(app.button('Open vehicle overlay').disabled,true);
    await app.click('Enable XREAL');
    const dialog=app.w.document.querySelector('[aria-labelledby="xreal-consent-title"]');
    assert.equal(dialog.open,true);assert.equal(app.w.document.activeElement,app.button('Cancel',dialog));
    assert.match(dialog.textContent,/does not enable listening/);
    assert.equal(app.requests.filter(value=>value.action==='meta_integration_set').length,0);
    await app.click('Cancel',dialog);assert.equal(dialog.open,false);
    assert.equal(app.requests.filter(value=>value.action==='meta_integration_set').length,0);
    await app.click('Enable XREAL');await app.click('Enable XREAL',dialog);
    await waitFor(()=>app.feedback().includes('XREAL enabled on this device.'));
    assert.equal(dialog.open,false);assert.equal(app.button('Open assistant HUD').disabled,false);
    assert.deepEqual(app.requests.filter(value=>value.action==='meta_integration_set').map(value=>value.arguments),[{provider:'xreal',enabled:true}]);
    assert.equal(app.requests.filter(value=>value.action==='xreal_status').length,2,'runtime status is re-read after enable');
    await app.click('Open assistant HUD');await waitFor(()=>app.feedback().includes('accepted the bundled HUD launch'));
    assert.match(app.feedback(),/glasses connection and tracking are not verified/);
    await app.click('Open vehicle overlay');await waitFor(()=>app.feedback().includes('accepted the bundled HUD launch'));
    assert.deepEqual(app.requests.filter(value=>value.action==='open_xreal_hud').map(value=>value.arguments),[{mode:'assistant'},{mode:'vehicle'}]);
    await app.click('Close bundled HUD');await waitFor(()=>app.feedback().includes('received the HUD close request'));
    await app.click('Disable XREAL');assert.equal(dialog.open,true);
    assert.equal(app.requests.filter(value=>value.action==='meta_integration_set').length,1);
    await app.click('Disable XREAL',dialog);await waitFor(()=>app.feedback().includes('XREAL disabled on this device.'));
    assert.deepEqual(app.requests.filter(value=>value.action==='meta_integration_set').map(value=>value.arguments),[{provider:'xreal',enabled:true},{provider:'xreal',enabled:false}]);
    assert.equal(app.button('Open assistant HUD').disabled,true);assert.equal(app.button('Open phone HUD').disabled,false);
    assert.equal(app.requests.filter(value=>['wake_word','start_voice_turn','request_bluetooth_permission'].includes(value.action)).length,0);
    assert.deepEqual(app.errors,[]);
  }finally{app.dom.window.close();}
});

test('browser and older or malformed host capability versions cannot dispatch HUD actions',async()=>{
  for(const options of [{native:false},...(['missing',null,'1',0,2,true].map(version=>({version})))]){
    const app=await mount(options);
    try{
      for(const label of ['Open phone HUD','Open assistant HUD','Open vehicle overlay','Close bundled HUD','Enable XREAL']){
        assert.equal(app.button(label).disabled,true,`${label} must remain disabled`);await app.click(label);
      }
      assert.match(app.feedback(),options.native===false?/Open ICARUS Test on your Android device/:/Install ICARUS Test 1.6.9 or later/);
      assert.equal(app.requests.filter(value=>hudActions.has(value.action)).length,0);
      assert.deepEqual(app.errors,[]);
    }finally{app.dom.window.close();}
  }
});

test('unavailable or unconfirmed XREAL runtime never enables launch and preserves the phone fallback',async()=>{
  for(const options of [{enabled:true,runtimeAvailable:false},{reply:(request,deliver)=>{if(request.action!=='xreal_status')return;deliver(request,{ok:true,data:{provider:'xreal',enabled:true,runtimeAvailable:'true'}});return false;}}]){
    const app=await mount(options);
    try{
      assert.equal(app.button('Open assistant HUD').disabled,true);assert.equal(app.button('Open vehicle overlay').disabled,true);
      assert.equal(app.button('Open phone HUD').disabled,false);
      await app.click('Open assistant HUD');assert.equal(app.requests.filter(value=>value.action==='open_xreal_hud').length,0);
      assert.deepEqual(app.errors,[]);
    }finally{app.dom.window.close();}
  }
});

test('failed, false or mismatched HUD acknowledgement never produces a UI success claim',async()=>{
  const cases=[
    ['Open phone HUD','open_driving_hud',{ok:false,data:{opened:true}}],
    ['Open phone HUD','open_driving_hud',{ok:true,data:{opened:false}}],
    ['Open phone HUD','open_driving_hud',{ok:true,data:[]}],
    ['Open phone HUD','open_driving_hud',{ok:true,data:{opened:'true'}}],
    ['Open assistant HUD','open_xreal_hud',{ok:true,data:{launched:true,runtimeAvailable:true,mode:'vehicle'}}],
    ['Open assistant HUD','open_xreal_hud',{ok:true,data:{launched:false,runtimeAvailable:true,mode:'assistant'}}],
    ['Close bundled HUD','close_xreal_hud',{ok:true,data:{closed:true}}],
  ];
  for(const [label,action,result] of cases){
    const app=await mount({enabled:true,reply:(request,deliver)=>{if(request.action!==action)return;deliver(request,result);return false;}});
    try{
      await app.click(label);await waitFor(()=>app.w.document.querySelector('.hud-feedback [role="alert"]'));
      assert.doesNotMatch(app.feedback(),/accepted the .*HUD launch|received the HUD close request/);
      assert.equal(app.button(label).disabled,false);assert.deepEqual(app.errors,[]);
    }finally{app.dom.window.close();}
  }
});

test('mismatched XREAL consent acknowledgement leaves the dialog open and integration off',async()=>{
  const app=await mount({reply:(request,deliver)=>{if(request.action!=='meta_integration_set')return;deliver(request,{ok:true,data:{provider:'xreal',enabled:false}});return false;}});
  try{
    await app.click('Enable XREAL');const dialog=app.w.document.querySelector('dialog');
    const confirm=app.button('Enable XREAL',dialog);confirm.click();confirm.click();
    await waitFor(()=>dialog.querySelector('[role="alert"]'));
    assert.equal(app.requests.filter(value=>value.action==='meta_integration_set').length,1);
    assert.equal(dialog.open,true);assert.equal(app.button('Open assistant HUD').disabled,true);
    assert.doesNotMatch(app.feedback(),/XREAL enabled on this device/);assert.deepEqual(app.errors,[]);
  }finally{app.dom.window.close();}
});

test('contradictory refreshed XREAL status reports an error and controls follow the actual state',async()=>{
  for(const enabled of [false,true]){
    const app=await mount({enabled,reply:(request,deliver)=>{
      if(request.action!=='xreal_status')return;
      deliver(request,{ok:true,data:{provider:'xreal',enabled,runtimeAvailable:true}});return false;
    }});
    try{
      const label=enabled?'Disable XREAL':'Enable XREAL';
      await app.click(label);const dialog=app.w.document.querySelector('dialog');
      await app.click(label,dialog);
      await waitFor(()=>app.w.document.querySelector('.hud-feedback [role="alert"]'));
      assert.match(app.feedback(),/XREAL status changed before confirmation/);
      assert.doesNotMatch(app.feedback(),/XREAL (enabled|disabled) on this device/);
      assert.equal(dialog.open,false);
      assert.equal(app.button('Open assistant HUD').disabled,!enabled);
      assert.equal(app.button('Open vehicle overlay').disabled,!enabled);
      assert.equal(app.button('Open phone HUD').disabled,false);
      assert.ok(app.button(label),'the toggle must reflect the refreshed actual state');
      assert.deepEqual(app.requests.filter(value=>value.action==='meta_integration_set').map(value=>value.arguments),[{provider:'xreal',enabled:!enabled}]);
      assert.deepEqual(app.errors,[]);
    }finally{app.dom.window.close();}
  }
});

test('alreadyClosed acknowledgement is accepted without claiming a new close request',async()=>{
  const app=await mount({reply:(request,deliver)=>{
    if(request.action!=='close_xreal_hud')return;
    deliver(request,{ok:true,data:{closeRequested:false,alreadyClosed:true}});return false;
  }});
  try{
    await app.click('Close bundled HUD');
    await waitFor(()=>app.feedback().includes('The bundled HUD is already closed.'));
    assert.equal(app.w.document.querySelector('.hud-feedback [role="alert"]'),null);
    assert.doesNotMatch(app.feedback(),/received the HUD close request/);
    assert.equal(app.button('Close bundled HUD').disabled,false);
    assert.equal(app.requests.filter(value=>value.action==='close_xreal_hud').length,1);
    assert.deepEqual(app.errors,[]);
  }finally{app.dom.window.close();}
});

test('HUD navigation aborts pending requests without overriding companion or voice subscribers',async()=>{
  const app=await mount({reply:request=>request.action==='open_driving_hud'?false:undefined});
  try{
    await app.click('Open phone HUD');const sent=app.requests.find(value=>value.action==='open_driving_hud');
    const dispatcher=app.w.ICARUS_NATIVE_RESULT;
    await app.click('Voice');assert.equal(app.w.ICARUS_NATIVE_RESULT,dispatcher);
    app.deliver(sent,{ok:true,data:{opened:true}});await pause();
    assert.doesNotMatch(app.w.document.body.textContent,/accepted the phone HUD launch/);
    assert.ok(app.observedResults.some(raw=>JSON.parse(raw).requestId===sent.requestId),'pre-existing callback still receives native results');
    await app.click('Stop speaking');assert.equal(app.requests.filter(value=>value.action==='stop_speaking').length,1);
    await app.click('Settings');await waitFor(()=>app.w.document.getElementById('voice-profile'));
    assert.equal(app.w.document.getElementById('voice-profile').value,'deep_warm');
    for(const page of ['Chat','Memory','Vehicle']){await app.click(page);assert.equal(app.w.document.querySelectorAll('.icarus-companion').length,1);}
    await app.click('Open ICARUS companion');await waitFor(()=>app.button('Talk now')&&!app.button('Talk now').disabled);
    await app.click('Talk now');await waitFor(()=>app.w.document.querySelector('.companion-feedback')?.textContent.includes('Android accepted Talk now'));
    assert.equal(app.requests.filter(value=>value.action==='start_voice_turn').length,1);
    app.w.document.querySelector('.profile').click();await waitFor(()=>app.w.document.querySelector('.auth'));
    assert.equal(app.w.ICARUS_NATIVE_RESULT,app.previousResult,'sign-out restores the pre-existing callback');
    assert.equal(app.w.ICARUS_NATIVE_STATUS,undefined);assert.deepEqual(app.errors,[]);
  }finally{app.dom.window.close();}
});

test('HUD status and messages require exact native truth instead of assuming tracked glasses',()=>{
  assert.equal(validHudStatus({provider:'xreal',enabled:false,runtimeAvailable:true}),true);
  for(const data of [null,{},[],{provider:'meta',enabled:true,runtimeAvailable:true},{provider:'xreal',enabled:'true',runtimeAvailable:true},{provider:'xreal',enabled:true}])assert.equal(Boolean(validHudStatus(data)),false);
  assert.match(hudResultMessage('open_xreal_hud',{mode:'assistant'},{launched:true,runtimeAvailable:true,mode:'assistant'}),/not verified/);
  assert.equal(hudResultMessage('close_xreal_hud',{},{closeRequested:false,alreadyClosed:true}),'The bundled HUD is already closed.');
  for(const [action,args,data] of [
    ['open_driving_hud',{},{}],['open_driving_hud',{},{opened:1}],
    ['open_xreal_hud',{mode:'assistant'},{launched:true,runtimeAvailable:false,mode:'assistant'}],
    ['open_xreal_hud',{mode:'assistant'},{launched:true,runtimeAvailable:true,mode:'vehicle'}],
    ['close_xreal_hud',{},{closeRequested:false}],
    ['close_xreal_hud',{},{closeRequested:false,alreadyClosed:'true'}],
    ['meta_integration_set',{enabled:true},{provider:'xreal',enabled:false}],
    ['meta_integration_set',{enabled:true},{provider:'meta',enabled:true}],
  ])assert.throws(()=>hudResultMessage(action,args,data),/did not confirm/);
});

test('aborted and timed-out native HUD requests clean up without breaking other subscribers',async()=>{
  const requests=[],seen=[],previous=raw=>seen.push(['previous',raw]);
  const host={ICARUS_NATIVE_RESULT:previous,ICARUS_NATIVE_CHANNEL:{postMessage:raw=>requests.push(JSON.parse(raw))}};
  const removeOther=subscribeNative(host,'result',raw=>seen.push(['other',raw]));
  const shared=host.ICARUS_NATIVE_RESULT;
  const alreadyCancelled=new AbortController();alreadyCancelled.abort();
  await assert.rejects(requestNativeSetting('open_driving_hud',{},host,50,{signal:alreadyCancelled.signal}),/cancelled/);
  assert.equal(requests.length,0);
  const controller=new AbortController();
  const pending=requestNativeSetting('open_driving_hud',{},host,50,{signal:controller.signal});
  controller.abort();await assert.rejects(pending,/cancelled/);assert.equal(host.ICARUS_NATIVE_RESULT,shared);
  host.ICARUS_NATIVE_RESULT({requestId:requests[0].requestId,ok:true,data:{opened:true}});
  assert.equal(seen.length,2);
  const timed=requestNativeSetting('open_driving_hud',{},host,10);
  host.ICARUS_NATIVE_RESULT('{bad json');
  host.ICARUS_NATIVE_RESULT({requestId:'unrelated',ok:true,data:{opened:true}});
  await assert.rejects(timed,/did not confirm this request/);
  assert.equal(host.ICARUS_NATIVE_RESULT,shared);
  removeOther();assert.equal(host.ICARUS_NATIVE_RESULT,previous);
  assert.equal(requests.length,2);
});

test('correlated invalid or failed acknowledgement and transport changes reject without leaking callbacks',async()=>{
  for(const result of [{ok:false,data:{opened:true}},{ok:'true',data:{opened:true}},{ok:true,error:'failed',data:{opened:true}},{ok:true,data:null},{ok:true,data:[]}]){
    const requests=[],host={ICARUS_NATIVE_CHANNEL:{postMessage:raw=>requests.push(JSON.parse(raw))}};
    const pending=requestNativeSetting('open_driving_hud',{},host,100);
    host.ICARUS_NATIVE_RESULT({requestId:requests[0].requestId,...result});
    await assert.rejects(pending,/could not confirm/);assert.equal(host.ICARUS_NATIVE_RESULT,undefined);
  }
  const requests=[],host={ICARUS_NATIVE_CHANNEL:{postMessage:raw=>requests.push(JSON.parse(raw))}};
  const pending=requestNativeSetting('open_driving_hud',{},host,100);
  host.ICARUS_NATIVE_CHANNEL={postMessage(){}};
  host.ICARUS_NATIVE_RESULT({requestId:requests[0].requestId,ok:true,data:{opened:true}});
  await assert.rejects(pending,/connection changed/);assert.equal(host.ICARUS_NATIVE_RESULT,undefined);
});
