import test from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
import {fileURLToPath} from 'node:url';
import {boundedResults,captureIntegrationCallback,createIntegrationApi,integrationState,PENDING_INTEGRATION_KEY,safeIntegrationUrl} from '../src/integrations-client.js';
const root=fileURLToPath(new URL('../',import.meta.url));
const bundle=await build({root,logLevel:'silent',build:{write:false,minify:false}});
const code=bundle.output.find(x=>x.type==='chunk'&&x.isEntry).code;
const wait=async fn=>{for(let i=0;i<150;i++){if(fn())return;await new Promise(r=>setTimeout(r,10));}assert.fail('UI did not settle');};
const button=(w,text)=>[...w.document.querySelectorAll('button')].find(b=>b.textContent===text);
async function mount({connected=false,configured=false,delayed=false}={}){
 const dom=new JSDOM('<div id="root"></div>',{url:'https://icarus.test',runScripts:'outside-only'}),w=dom.window,requests=[],errors=[];
 w.localStorage.setItem('icarus_token','signed-session');w.scrollTo=()=>{};w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;};
 w.addEventListener('error',e=>{errors.push(e.error);e.preventDefault();});let settle;
 w.fetch=async(path,options={})=>{requests.push({path,options});let value={};
 if(path==='/api/me')value={user:{id:'test-user',name:'Tester',email:'tester@example.com'}};
 if(path==='/api/integrations')value={integrations:[{id:'gmail',label:'Gmail',supported:true,configured,connected,readOnly:true,liveVerified:false,capabilities:['Search mail']}]};
 if(path==='/api/integrations/gmail'&&options.method==='DELETE'){connected=false;value={disconnected:true};}
 if(path==='/api/integrations/gmail/query'){if(delayed)await new Promise(r=>settle=r);value={results:[{id:'1',title:'<img src=x onerror=alert(1)>',text:'<script>bad()</script>',url:'javascript:alert(1)'}],readOnly:true,liveVerified:true};}
 if(path==='/api/billing/config')value={configured:false,checkoutEnabled:false,plans:[],reason:'billing_not_configured'};
 if(path==='/api/entitlement')value={entitlement:{premium:false}};
 return {ok:true,json:async()=>value};};
 w.eval(code);await wait(()=>w.document.querySelector('nav'));button(w,'Settings').click();await wait(()=>w.document.body.textContent.includes('Connection status loaded.'));
 return {dom,w,requests,errors,settle:()=>settle?.()};
}
test('states and result validation are truthful and bounded',()=>{
 assert.match(integrationState({supported:true}),/server setup required/);assert.match(integrationState({supported:true,configured:true}),/not connected/);assert.match(integrationState({supported:true,connected:true}),/not yet verified/);
 assert.equal(safeIntegrationUrl('javascript:alert(1)'),null);assert.equal(safeIntegrationUrl('https://user:pass@example.com'),null);assert.throws(()=>boundedResults({results:[]}),/did not confirm/);assert.equal(boundedResults({readOnly:true,liveVerified:true,results:Array(50).fill({text:'x'.repeat(9000)})}).length,10);
});
test('OAuth callback scrubs URL and matches pending state',()=>{
 const dom=new JSDOM('',{url:'https://icarus.test/?code=secret&state=nonce&keep=1'});try{dom.window.sessionStorage.setItem(PENDING_INTEGRATION_KEY,JSON.stringify({id:'gmail',state:'nonce',userId:'u'}));assert.deepEqual(captureIntegrationCallback(dom.window),{id:'gmail',userId:'u',code:'secret',state:'nonce'});assert.equal(dom.window.location.search,'?keep=1');assert.equal(dom.window.sessionStorage.getItem(PENDING_INTEGRATION_KEY),null);}finally{dom.window.close();}
});
test('API rejects account changes before dispatch and after response',async()=>{
 let token='one',called=0;const api=createIntegrationApi({token,currentToken:()=>token,fetcher:async()=>{called++;token='two';return {ok:true,json:async()=>({})};}});await assert.rejects(api(),/sign-in changed/);await assert.rejects(api(),/sign-in changed/);assert.equal(called,1);
});
test('unconfigured integrations never auto-connect',async()=>{const a=await mount();try{assert.equal(button(a.w,'Connect Gmail').disabled,true);assert.match(a.w.document.body.textContent,/Built · server setup required/);assert.equal(a.requests.some(x=>x.path.endsWith('/connect')),false);assert.deepEqual(a.errors,[]);}finally{a.dom.window.close();}});
test('disconnect cancellation sends nothing; confirmation waits for acknowledgement',async()=>{const a=await mount({connected:true,configured:true});try{button(a.w,'Disconnect Gmail').click();await wait(()=>a.w.document.querySelector('dialog[open]'));assert.equal(a.w.document.activeElement.textContent,'Keep connected');button(a.w,'Keep connected').click();assert.equal(a.requests.some(x=>x.options.method==='DELETE'),false);button(a.w,'Disconnect Gmail').click();button(a.w,'Confirm disconnect').click();await wait(()=>button(a.w,'Connect Gmail'));assert.equal(a.requests.filter(x=>x.options.method==='DELETE').length,1);assert.deepEqual(a.errors,[]);}finally{a.dom.window.close();}});
test('explicit reads escape content, block unsafe links and suppress repeated submit',async()=>{const a=await mount({connected:true,configured:true,delayed:true});try{
 const select=a.w.document.querySelector('#integration-source');select.value='gmail';select.dispatchEvent(new a.w.Event('change',{bubbles:true}));const input=a.w.document.querySelector('#integration-query');Object.getOwnPropertyDescriptor(a.w.HTMLInputElement.prototype,'value').set.call(input,'find mail');input.dispatchEvent(new a.w.Event('input',{bubbles:true}));await wait(()=>!button(a.w,'Read selected service').disabled);
 const form=a.w.document.querySelector('.integration-query');form.dispatchEvent(new a.w.Event('submit',{bubbles:true,cancelable:true}));form.dispatchEvent(new a.w.Event('submit',{bubbles:true,cancelable:true}));await wait(()=>a.requests.some(x=>x.path.endsWith('/query')));assert.equal(a.requests.filter(x=>x.path.endsWith('/query')).length,1);a.settle();await wait(()=>a.w.document.querySelector('.integration-results'));assert.match(a.w.document.querySelector('.integration-results').textContent,/<script>bad/);assert.equal(a.w.document.querySelector('.integration-results script, .integration-results img, .integration-results a'),null);assert.deepEqual(a.errors,[]);
 }finally{a.dom.window.close();}});
test('unmatched or denied OAuth returns cannot become connections',()=>{
 for(const suffix of ['code=secret&state=wrong','error=access_denied&state=nonce']){
 const dom=new JSDOM('',{url:`https://icarus.test/?${suffix}`});try{dom.window.sessionStorage.setItem(PENDING_INTEGRATION_KEY,JSON.stringify({id:'gmail',state:'nonce',userId:'u'}));const value=captureIntegrationCallback(dom.window);assert.ok(value.error);assert.equal(value.code,undefined);assert.equal(dom.window.location.search,'');}finally{dom.window.close();}}
});
test('a bounded timeout explains uncertain request completion',async()=>{
 const api=createIntegrationApi({token:'one',currentToken:()=> 'one',timeoutMs:5,fetcher:(_url,{signal})=>new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(Object.assign(new Error('aborted'),{name:'AbortError'}))))});await assert.rejects(api(),/timed out/);
});
test('leaving settings during a read prevents stale result UI',async()=>{
 const a=await mount({connected:true,configured:true,delayed:true});try{
 const select=a.w.document.querySelector('#integration-source');select.value='gmail';select.dispatchEvent(new a.w.Event('change',{bubbles:true}));const input=a.w.document.querySelector('#integration-query');Object.getOwnPropertyDescriptor(a.w.HTMLInputElement.prototype,'value').set.call(input,'find mail');input.dispatchEvent(new a.w.Event('input',{bubbles:true}));await wait(()=>!button(a.w,'Read selected service').disabled);button(a.w,'Read selected service').click();await wait(()=>a.requests.some(x=>x.path.endsWith('/query')));button(a.w,'Home').click();a.settle();await new Promise(r=>setTimeout(r,40));assert.equal(a.w.document.querySelector('.integration-results'),null);assert.deepEqual(a.errors,[]);
 }finally{a.dom.window.close();}
});
