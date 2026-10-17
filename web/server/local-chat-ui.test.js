import test from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
import {fileURLToPath} from 'node:url';
import {LOCAL_STORAGE_KEY} from '../src/local-storage.js';

const root=fileURLToPath(new URL('../',import.meta.url));
const entry='virtual:local-chat-ui';
const bundle=await build({root,logLevel:'silent',plugins:[{name:'local-chat-test-entry',resolveId:id=>id===entry?'\0'+entry:null,load:id=>id==='\0'+entry?`import React from 'react';import {createRoot} from 'react-dom/client';import {Chat} from '${root}src/chat.jsx';import {installVoiceControls} from '${root}src/native-session.js';installVoiceControls();createRoot(document.getElementById('root')).render(React.createElement(Chat));`:null}],build:{write:false,minify:false,rollupOptions:{input:entry}}});
const code=bundle.output.find(x=>x.type==='chunk'&&x.isEntry).code;
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const waitFor=async condition=>{for(let i=0;i<200;i++){if(condition())return;await delay(10);}assert.fail('Local Chat did not reach expected state');};
async function mount({saved,modelError,interpretation}={}) {
  const dom=new JSDOM('<div id="root"></div>',{url:'https://appassets.androidplatform.net/assets/web/index.html',runScripts:'outside-only'}),w=dom.window;
  const network=[],native=[],errors=[];
  if(saved)w.localStorage.setItem(LOCAL_STORAGE_KEY,saved);
  w.localStorage.setItem('icarus_token','STALE_CLOUD_TOKEN');
  w.fetch=async(...args)=>{network.push(args);throw Error('Unexpected cloud fetch');};
  w.addEventListener('error',event=>{errors.push(event.error);event.preventDefault();});
  w.ICARUS_NATIVE_CHANNEL={postMessage:raw=>{
    const request=JSON.parse(raw);native.push(request);
    if(request.bridgeRequest==='status')setTimeout(()=>w.ICARUS_NATIVE_STATUS?.({requestId:request.requestId,actionProtocolVersion:1,connected:true,voiceSession:{conversationId:'STALE_CLOUD_CONVERSATION',temporary:false}}),0);
    if(request.action==='local_chat')setTimeout(()=>w.ICARUS_NATIVE_RESULT?.(modelError?{ok:false,requestId:request.requestId,error:modelError}:{ok:true,requestId:request.requestId,data:{content:'An offline answer.'}}),0);
    if(request.action==='interpret_command')setTimeout(()=>w.ICARUS_NATIVE_RESULT?.({ok:true,requestId:request.requestId,data:interpretation||{action:'get_battery',arguments:{},executionStatus:'not_executed'}}),0);
    if(request.action==='get_battery')setTimeout(()=>w.ICARUS_NATIVE_RESULT?.({ok:true,requestId:request.requestId,data:{level:76,charging:false}}),0);
  }};
  w.eval(code);
  const button=label=>[...w.document.querySelectorAll('button')].find(b=>b.textContent===label);
  const click=async label=>{assert.ok(button(label),`Missing ${label}`);button(label).click();await delay(20);};
  const draft=async text=>{const field=w.document.querySelector('textarea');Object.getOwnPropertyDescriptor(w.HTMLTextAreaElement.prototype,'value').set.call(field,text);field.dispatchEvent(new w.Event('input',{bubbles:true}));field.dispatchEvent(new w.Event('change',{bubbles:true}));await delay(20);};
  const send=()=>w.document.querySelector('.composer').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
  await waitFor(()=>w.document.querySelector('textarea'));
  await waitFor(()=>w.document.body.textContent.includes('Phone actions are connected'));
  return {dom,w,network,native,errors,button,click,draft,send};
}

test('bundled Chat saves device history without network or stale cloud voice context',async()=>{
  const app=await mount();
  try {
    await app.draft('Plan a quiet afternoon');app.send();await waitFor(()=>app.w.document.body.textContent.includes('An offline answer.'));
    assert.equal(app.network.length,0);assert.equal(app.native.filter(r=>r.action==='local_chat').length,1);
    const session=app.native.filter(r=>r.action==='configure_voice_session');assert.ok(session.length>0);
    for(const r of session){assert.equal(r.arguments.token,'');assert.equal(r.arguments.conversationId,'');assert.equal(r.arguments.temporary,true);}
    const stored=app.w.localStorage.getItem(LOCAL_STORAGE_KEY);assert.match(stored,/Plan a quiet afternoon/);assert.doesNotMatch(stored,/STALE_CLOUD/);
    assert.equal(JSON.parse(stored).conversations[0].messages.length,2);assert.deepEqual(app.errors,[]);
    const reopened=await mount({saved:stored});
    try{await reopened.click('Plan a quiet afternoon');await waitFor(()=>reopened.w.document.querySelector('.messages').textContent.includes('An offline answer.'));assert.equal(reopened.network.length,0);}
    finally{reopened.dom.window.close();}
  }finally{app.dom.window.close();}
});

test('local action interpretation preserves review, cancellation, and result saving without a model dispatch',async()=>{
  const app=await mount();
  try {
    await app.draft('Check battery');app.send();await waitFor(()=>app.button('Confirm action'));
    assert.equal(app.native.filter(r=>r.action==='get_battery').length,0);
    assert.equal(app.native.filter(r=>r.action==='interpret_command').length,1);
    await app.click('Cancel action');await waitFor(()=>app.w.document.body.textContent.includes('Cancellation saved'));
    assert.equal(app.native.filter(r=>r.action==='get_battery').length,0);
    assert.equal(JSON.parse(app.w.localStorage.getItem(LOCAL_STORAGE_KEY)).actions[0].result.status,'cancelled');
    await app.draft('Check battery');app.send();await waitFor(()=>app.button('Confirm action'));
    await app.click('Confirm action');await waitFor(()=>app.w.document.body.textContent.includes('Battery: 76%'));
    assert.equal(app.native.filter(r=>r.action==='get_battery').length,1);
    assert.equal(app.network.length,0);
  }finally{app.dom.window.close();}
});

test('temporary UI chat excludes saved memory and never saves its request or device report',async()=>{
  const saved=JSON.stringify({version:1,memories:[{id:'private',content:'SAVED_PRIVATE_MARKER'}],conversations:[],turns:[],actions:[]});
  const app=await mount({saved});
  try {
    await app.click('Temporary off');await app.draft('PRIVATE_TEMP_REQUEST');app.send();await waitFor(()=>app.w.document.body.textContent.includes('An offline answer.'));
    const prompt=app.native.find(r=>r.action==='local_chat').arguments.prompt;
    assert.match(prompt,/PRIVATE_TEMP_REQUEST/);assert.doesNotMatch(prompt,/SAVED_PRIVATE_MARKER/);
    assert.equal(app.w.localStorage.getItem(LOCAL_STORAGE_KEY),saved);
    await app.draft('Check battery');app.send();await waitFor(()=>app.button('Confirm action'));await app.click('Confirm action');
    await waitFor(()=>app.w.document.body.textContent.includes('Temporary chat: the device result will not be saved'));
    assert.equal(app.w.localStorage.getItem(LOCAL_STORAGE_KEY),saved);assert.equal(app.network.length,0);
  }finally{app.dom.window.close();}
});

test('missing local model produces an actionable notice and retained draft without cloud fallback',async()=>{
  const app=await mount({modelError:'local_model_not_downloaded'});
  try {
    await app.draft('Help me plan');app.send();await waitFor(()=>app.w.document.body.textContent.includes('Settings → On-device model'));
    assert.equal(app.w.document.querySelector('textarea').value,'Help me plan');assert.equal(app.network.length,0);
    assert.equal(app.w.localStorage.getItem(LOCAL_STORAGE_KEY),null);
  }finally{app.dom.window.close();}
});

test('invalid native proposals cannot reach an action confirmation or device execution',async()=>{
  const app=await mount({interpretation:{action:'make_call',arguments:{contact:'Invented person'},executionStatus:'not_executed'}});
  try {
    await app.draft('Call Alex');app.send();await waitFor(()=>app.w.document.body.textContent.includes('proposed target did not match'));
    assert.equal(app.button('Confirm action'),undefined);assert.equal(app.native.filter(r=>r.action==='make_call').length,0);assert.equal(app.network.length,0);
  }finally{app.dom.window.close();}
});
