import test from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const bundle=await build({root,logLevel:'silent',build:{write:false,minify:false}});
const code=bundle.output.find(x=>x.type==='chunk'&&x.isEntry).code;
const waitFor=async condition=>{for(let i=0;i<200;i++){if(condition())return;await new Promise(r=>setTimeout(r,10));}assert.fail('Chat did not reach the expected state');};
const delay=ms=>new Promise(r=>setTimeout(r,ms));

async function mount({voice={conversationId:null,temporary:false},conversations=[],fetchChat,statusDelay=0,onDevice}={}) {
  const dom=new JSDOM('<div id="root"></div>',{url:'https://icarus.test',runScripts:'outside-only'});
  const w=dom.window,requests=[],deviceRequests=[],errors=[];
  w.scrollTo=()=>{};w.AbortSignal.timeout=()=>new w.AbortController().signal;
  w.addEventListener('error',e=>{errors.push(e.error);e.preventDefault()});
  let voiceSession={...voice};
  w.fetch=async(url,options={})=>{
    const body=options.body?JSON.parse(options.body):undefined;requests.push({url,body});
    const custom=await fetchChat?.(url,body,requests);
    if(custom!==undefined)return custom;
    const data=url==='/api/me'?{user:{name:'Tester',email:'test@example.invalid'}}:url==='/api/capabilities'?{webSearch:true}:url==='/api/conversations'?{conversations}:url==='/api/memories'?{memories:[]}:url.startsWith('/api/actions/')?{ok:true}:{conversationId:'created',reply:'Review this action.',proposal:{id:'action-1',action:'get_battery',value:''}};
    return {ok:true,json:async()=>data};
  };
  w.IcarusNative={postMessage:raw=>{
    const p=JSON.parse(raw);deviceRequests.push(p);
    onDevice?.(p,w);
    if(p.action==='configure_voice_session') {
      if(Object.hasOwn(p.arguments,'temporary'))voiceSession.temporary=p.arguments.temporary;
      if(Object.hasOwn(p.arguments,'conversationId'))voiceSession.conversationId=p.arguments.conversationId||null;
    }
    if(p.bridgeRequest) {
      const snapshot={...voiceSession};
      setTimeout(()=>w.ICARUS_NATIVE_STATUS?.({requestId:p.requestId,actionProtocolVersion:1,voiceSession:snapshot}),statusDelay);
    }
    if(p.action)setTimeout(()=>w.ICARUS_NATIVE_RESULT?.({ok:true,requestId:p.requestId,data:p.action==='get_battery'?{level:55,charging:false}:{}}),0);
  }};
  w.eval(code);await waitFor(()=>w.document.querySelector('nav'));
  const button=label=>[...w.document.querySelectorAll('button')].find(b=>b.textContent===label);
  const click=async label=>{assert.ok(button(label),`Missing ${label}`);button(label).click();await delay(15)};
  const draft=async text=>{
    const input=w.document.querySelector('textarea');
    Object.getOwnPropertyDescriptor(w.HTMLTextAreaElement.prototype,'value').set.call(input,text);
    input.dispatchEvent(new w.Event('input',{bubbles:true}));input.dispatchEvent(new w.Event('change',{bubbles:true}));await delay(15);
  };
  const send=()=>w.document.querySelector('.composer').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
  await click('Chat');
  return {dom,w,requests,deviceRequests,errors,button,click,draft,send,getVoice:()=>voiceSession};
}

test('Chat resumes the native voice conversation and saves its Android report',async()=>{
  const app=await mount({voice:{conversationId:'voice-chat',temporary:false},conversations:[{id:'voice-chat',title:'Voice conversation',messages:[{role:'assistant',content:'Earlier voice reply.'}]}]});
  try {
    await waitFor(()=>app.w.document.body.textContent.includes('Earlier voice reply.'));
    await app.draft('Check battery');app.send();await waitFor(()=>app.button('Confirm action'));
    const turn=app.requests.find(r=>r.url==='/api/chat');
    assert.equal(turn.body.conversationId,'voice-chat');assert.match(turn.body.clientTurnId,/^[0-9a-f-]{36}$/i);
    await app.click('Confirm action');await waitFor(()=>app.requests.some(r=>r.url==='/api/actions/action-1/result'));
    assert.equal(app.requests.find(r=>r.url==='/api/actions/action-1/result').body.status,'reported');
    assert.match(app.requests.find(r=>r.url==='/api/actions/action-1/result').body.summary,/Battery: 55%/);
    assert.equal(app.getVoice().conversationId,'created');assert.deepEqual(app.errors,[]);
  }finally{app.dom.window.close()}
});

test('temporary Chat clears native saved context and never saves action results',async()=>{
  const app=await mount({voice:{conversationId:'saved-chat',temporary:false}});
  try {
    await waitFor(()=>app.w.document.body.textContent.includes('Phone actions are connected'));
    await app.click('Temporary off');
    assert.equal(app.getVoice().temporary,true);assert.equal(app.getVoice().conversationId,null);
    await app.draft('Check battery');app.send();await waitFor(()=>app.button('Confirm action'));
    await app.click('Confirm action');await waitFor(()=>app.w.document.body.textContent.includes('Battery: 55%'));
    assert.ok(app.requests.some(r=>r.url==='/api/chat/temporary'));
    assert.equal(app.requests.filter(r=>r.url.startsWith('/api/actions/')).length,0);
    assert.match(app.w.document.body.textContent,/device result will not be saved/);
    assert.equal(app.getVoice().temporary,true);assert.equal(app.getVoice().conversationId,null);
  }finally{app.dom.window.close()}
});

test('retry saving a report never re-executes its Android action',async()=>{
  let saveAttempts=0;
  const app=await mount({fetchChat:async url=>{if(url.startsWith('/api/actions/')){saveAttempts++;return {ok:saveAttempts>1,json:async()=>({ok:true})}}}});
  try {
    await waitFor(()=>app.w.document.body.textContent.includes('Phone actions are connected'));
    await app.draft('Check battery');app.send();await waitFor(()=>app.button('Confirm action'));await app.click('Confirm action');
    await waitFor(()=>app.button('Retry saving result'));
    assert.equal(app.deviceRequests.filter(p=>p.action==='get_battery').length,1);
    await app.click('+ New chat');assert.match(app.w.document.body.textContent,/Unsaved device report for a previous conversation/);
    await app.click('Retry saving result');await waitFor(()=>app.w.document.body.textContent.includes('Device report saved'));
    assert.equal(saveAttempts,2);assert.equal(app.deviceRequests.filter(p=>p.action==='get_battery').length,1);
    assert.equal(app.button('Retry saving result'),undefined);
  }finally{app.dom.window.close()}
});

test('cancelling a proposal records cancellation without any Android dispatch',async()=>{
  const app=await mount();
  try {
    await waitFor(()=>app.w.document.body.textContent.includes('Phone actions are connected'));
    await app.draft('Check battery');app.send();await waitFor(()=>app.button('Cancel action'));await app.click('Cancel action');
    await waitFor(()=>app.requests.some(r=>r.url==='/api/actions/action-1/result'));
    assert.equal(app.requests.find(r=>r.url==='/api/actions/action-1/result').body.status,'cancelled');
    assert.equal(app.deviceRequests.filter(p=>p.action==='get_battery').length,0);
  }finally{app.dom.window.close()}
});

test('a late native snapshot cannot undo a fresh temporary selection',async()=>{
  const app=await mount({voice:{conversationId:'old-context',temporary:false},statusDelay:120});
  try {
    await app.click('Temporary off');await delay(180);
    assert.ok(app.button('Temporary on'));assert.equal(app.getVoice().conversationId,null);
    await app.draft('Private question');app.send();await waitFor(()=>app.requests.some(r=>r.url==='/api/chat/temporary'));
    assert.equal(app.requests.filter(r=>r.url==='/api/chat').length,0);
  }finally{app.dom.window.close()}
});

test('an unchanged failed send retains its full request and edited text receives a new turn ID',async()=>{
  let attempts=0;
  const app=await mount({fetchChat:async url=>{if(url==='/api/chat'){attempts++;return {ok:false,json:async()=>({})}}}});
  try {
    await waitFor(()=>app.w.document.body.textContent.includes('Phone actions are connected'));
    await app.draft('Question');app.send();await waitFor(()=>app.w.document.body.textContent.includes('draft is retained'));
    app.send();await waitFor(()=>attempts===2);await waitFor(()=>!app.w.document.querySelector('textarea').disabled);
    const firstTwo=app.requests.filter(r=>r.url==='/api/chat');assert.deepEqual(firstTwo[0].body,firstTwo[1].body);
    await app.draft('Edited question');app.send();await waitFor(()=>attempts===3);
    const turns=app.requests.filter(r=>r.url==='/api/chat');assert.notEqual(turns[2].body.clientTurnId,turns[0].body.clientTurnId);
  }finally{app.dom.window.close()}
});

test('a changed sign-in cannot submit an earlier account device report',async()=>{
  const app=await mount({onDevice:(p,w)=>{if(p.action==='get_battery')w.localStorage.setItem('icarus_token','different-session')}});
  try {
    await waitFor(()=>app.w.document.body.textContent.includes('Phone actions are connected'));
    await app.draft('Check battery');app.send();await waitFor(()=>app.button('Confirm action'));await app.click('Confirm action');
    await waitFor(()=>app.w.document.body.textContent.includes('Your sign-in changed'));
    assert.equal(app.requests.filter(r=>r.url.startsWith('/api/actions/')).length,0);
    assert.equal(app.deviceRequests.filter(p=>p.action==='get_battery').length,1);
  }finally{app.dom.window.close()}
});

test('a deleted conversation failure offers a fresh chat instead of repeating the missing request',async()=>{
  const app=await mount({fetchChat:async url=>url==='/api/chat'?{ok:false,status:404,json:async()=>({})}:undefined});
  try {
    await waitFor(()=>app.w.document.body.textContent.includes('Phone actions are connected'));
    await app.draft('Question');app.send();await waitFor(()=>app.w.document.body.textContent.includes('Choose + New chat'));
    assert.equal(app.w.document.querySelector('textarea').value,'Question');assert.ok(app.button('+ New chat'));
    assert.equal(app.deviceRequests.filter(p=>p.action==='get_battery').length,0);
  }finally{app.dom.window.close()}
});

test('history refresh after a lost response does not duplicate the retried turn in Chat',async()=>{
  const conversations=[{id:'existing',title:'Existing',messages:[]}];let attempts=0;
  const app=await mount({voice:{conversationId:'existing',temporary:false},conversations,fetchChat:async url=>{
    if(url!=='/api/chat')return;
    attempts++;
    conversations[0].messages=[{role:'user',content:'Question'},{role:'assistant',content:'Saved answer'}];
    return attempts===1?{ok:false}:{ok:true,json:async()=>({conversationId:'existing',reply:'Saved answer'})};
  }});
  try {
    await waitFor(()=>app.w.document.body.textContent.includes('Phone actions are connected'));
    await app.draft('Question');app.send();await waitFor(()=>app.w.document.body.textContent.includes('draft is retained'));
    app.w.dispatchEvent(new app.w.Event('focus'));await waitFor(()=>app.w.document.querySelector('.messages').textContent.includes('Saved answer'));
    app.send();await waitFor(()=>attempts===2&&app.w.document.querySelector('textarea').value==='');
    assert.equal(app.w.document.querySelectorAll('.messages article.user').length,1);
    assert.equal(app.w.document.querySelectorAll('.messages article.assistant').length,1);
  }finally{app.dom.window.close()}
});
