import test from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const bundle=await build({root,logLevel:'silent',build:{write:false,minify:false}});
const code=bundle.output.find(x=>x.type==='chunk'&&x.isEntry).code;
const waitFor=async condition=>{for(let i=0;i<100;i++){if(condition())return;await new Promise(r=>setTimeout(r,10));}assert.fail('Companion did not reach expected state');};
const button=(w,label)=>[...w.document.querySelectorAll('button')].find(b=>b.getAttribute('aria-label')===label||b.textContent===label);

async function mount({native=true,enabled=true,supported=true,visible=true}={}){
  const dom=new JSDOM('<div id="root"></div>',{url:'https://icarus.test',runScripts:'outside-only',pretendToBeVisual:true});
  const w=dom.window,requests=[],errors=[];
  let state={connected:true,actionProtocolVersion:1,wakeWord:{enabled,permissionGranted:true,talkNowSupported:supported,listenerState:'LISTENING'}};
  w.addEventListener('error',event=>{errors.push(event.error);event.preventDefault();});
  w.scrollTo=()=>{};
  Object.defineProperty(w.document,'visibilityState',{configurable:true,get:()=>visible?'visible':'hidden'});
  w.fetch=async url=>({ok:true,json:async()=>url==='/api/me'?{user:{name:'Michael',email:'test@example.invalid'}}:url==='/api/memories'?{memories:[]}:url==='/api/capabilities'?{}:{conversations:[]}});
  if(native)w.ICARUS_NATIVE_CHANNEL={postMessage:raw=>{
    const p=JSON.parse(raw);requests.push(p);
    if(p.bridgeRequest)setTimeout(()=>w.ICARUS_NATIVE_STATUS?.(JSON.stringify({...state,requestId:p.requestId})),0);
  }};
  w.eval(code);
  await waitFor(()=>w.document.querySelector('.companion-figure'));
  const click=async label=>{const b=button(w,label);assert.ok(b,`Missing ${label}`);b.click();await new Promise(r=>setTimeout(r,20));};
  const broadcast=async patch=>{state={...state,wakeWord:{...state.wakeWord,...patch}};w.ICARUS_NATIVE_STATUS?.(JSON.stringify(state));await new Promise(r=>setTimeout(r,20));};
  return{dom,w,requests,errors,click,broadcast,visibility:value=>{visible=value;w.document.dispatchEvent(new w.Event('visibilitychange'));}};
}

test('one companion survives navigation, can move and hide, and has a resilient image fallback',async()=>{
  const app=await mount({native:false});
  try{
    for(const page of ['Chat','Memory','Settings','Voice']){await app.click(page);assert.equal(app.w.document.querySelectorAll('.icarus-companion').length,1);}
    assert.equal(app.w.document.querySelector('.icarus-head'),null);
    const image=app.w.document.querySelector('.companion-figure img');image.dispatchEvent(new app.w.Event('error'));
    await waitFor(()=>app.w.document.querySelector('.companion-fallback'));
    assert.equal(app.w.document.querySelector('.companion-figure img'),null);
    await app.click('Open ICARUS companion');await app.click('Move ICARUS to the left');
    assert.ok(app.w.document.querySelector('.companion-left'));
    await app.click('Hide ICARUS');
    assert.equal(app.w.document.querySelector('.companion-panel'),null);
    assert.equal(app.w.document.activeElement,button(app.w,'Show ICARUS companion'));
    assert.deepEqual(JSON.parse(app.w.localStorage.getItem('icarus_companion_preferences')),{hidden:true,side:'left'});
    await app.click('Show ICARUS companion');
    assert.equal(app.w.document.activeElement,button(app.w,'Open ICARUS companion'));
    assert.deepEqual(app.errors,[]);
  }finally{app.dom.window.close();}
});

test('waiting for wake is idle; command capture and errors reflect native state',async()=>{
  const app=await mount();
  try{
    await app.click('Open ICARUS companion');await waitFor(()=>app.w.document.querySelector('.companion-status').textContent.includes('Waiting for'));
    assert.equal(app.w.document.querySelector('.icarus-companion').dataset.phase,'idle');
    assert.equal(app.requests.filter(p=>p.action==='start_voice_turn').length,0);
    await app.broadcast({listenerState:'CAPTURING'});
    assert.equal(app.w.document.querySelector('.icarus-companion').dataset.phase,'listening');
    assert.equal(app.w.document.querySelector('.companion-talk').disabled,true);
    await app.broadcast({listenerState:'INTERPRETING'});
    assert.equal(app.w.document.querySelector('.icarus-companion').dataset.phase,'thinking');
    await app.broadcast({listenerState:'SPEAKING'});
    assert.equal(app.w.document.querySelector('.icarus-companion').dataset.phase,'speaking');
    await app.broadcast({listenerState:'MICROPHONE_BLOCKED'});
    assert.equal(app.w.document.querySelector('.icarus-companion').dataset.phase,'error');
    assert.match(app.w.document.querySelector('.companion-status').textContent,/blocking microphone/);
    assert.deepEqual(app.errors,[]);
  }finally{app.dom.window.close();}
});

test('Talk now requires opt-in and native support, dispatches once, and ignores unrelated acknowledgements',async()=>{
  const app=await mount({enabled:false,supported:false});
  try{
    await app.click('Open ICARUS companion');await waitFor(()=>app.w.document.querySelector('.companion-status').textContent.includes('Enable hands-free'));
    const talk=app.w.document.querySelector('.companion-talk');
    assert.equal(talk.disabled,true);talk.click();
    assert.equal(app.requests.filter(p=>p.action==='start_voice_turn').length,0);
    await app.broadcast({enabled:true,talkNowSupported:false});assert.equal(talk.disabled,true);
    await app.broadcast({talkNowSupported:true});assert.equal(talk.disabled,false);
    talk.click();talk.click();
    const sent=app.requests.filter(p=>p.action==='start_voice_turn');assert.equal(sent.length,1);
    assert.deepEqual(sent[0].arguments,{});
    app.w.ICARUS_NATIVE_RESULT(JSON.stringify({requestId:'unrelated',ok:true,data:{requestAccepted:true}}));
    await waitFor(()=>talk.disabled);assert.match(app.w.document.querySelector('.companion-feedback').textContent,/Requesting/);
    await app.broadcast({listenerState:'CAPTURING'});
    app.w.ICARUS_NATIVE_RESULT(JSON.stringify({requestId:sent[0].requestId,ok:true,data:{requestAccepted:true}}));
    await waitFor(()=>app.w.document.querySelector('.companion-feedback').textContent.includes('Android accepted'));
    assert.equal(app.requests.filter(p=>p.action==='start_voice_turn').length,1);
    assert.deepEqual(app.errors,[]);
  }finally{app.dom.window.close();}
});

test('typing tucks the companion, Escape restores focus, and sign-out removes it',async()=>{
  const app=await mount();
  try{
    await app.click('Chat');await app.click('Open ICARUS companion');
    app.w.document.dispatchEvent(new app.w.KeyboardEvent('keydown',{key:'Escape',bubbles:true}));
    await waitFor(()=>!app.w.document.querySelector('.companion-panel'));
    assert.equal(app.w.document.activeElement,button(app.w,'Open ICARUS companion'));
    const input=app.w.document.querySelector('textarea');input.focus();
    await waitFor(()=>app.w.document.querySelector('.icarus-companion').hidden);
    input.blur();await waitFor(()=>!app.w.document.querySelector('.icarus-companion').hidden);
    app.w.document.querySelector('.profile').click();
    await waitFor(()=>app.w.document.querySelector('.auth'));
    assert.equal(app.w.document.querySelector('.icarus-companion'),null);
    assert.equal(app.w.ICARUS_NATIVE_STATUS,undefined);
    assert.equal(app.w.ICARUS_NATIVE_RESULT,undefined);
    assert.deepEqual(app.errors,[]);
  }finally{app.dom.window.close();}
});
