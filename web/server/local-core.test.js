import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {createLocalApi,isLocalMode,api,nativeModelRequest,buildLocalPrompt,isExplicitDeviceRequest,commandProposal} from '../src/local-core.js';
import {createLocalStore,LOCAL_STORAGE_KEY} from '../src/local-storage.js';

function fixture({modelRequest,storage:provided}={}) {
  const values=new Map(),storage=provided||{getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,value)};
  const calls=[],host={location:new URL('https://appassets.androidplatform.net/assets/web/index.html'),localStorage:storage,crypto:{randomUUID},ICARUS_NATIVE_CHANNEL:{postMessage:()=>{}},fetch:()=>assert.fail('Local mode must not fetch')};
  const model=modelRequest||(async(action,args)=>{calls.push({action,args});return {content:'Local answer'};});
  const adapter=createLocalApi({host,storage,modelRequest:model});
  const request=(path,body,method='POST')=>adapter(path,{method,body:JSON.stringify(body)});
  return {host,storage,values,calls,adapter,request,model};
}
const turn=(message,extra={})=>({message,native:true,clientTurnId:randomUUID(),...extra});

test('only the HTTPS bundled app origin and path use local mode',()=>{
  for(const url of ['https://appassets.androidplatform.net/assets/web/index.html','https://appassets.androidplatform.net/assets/web/'])assert.equal(isLocalMode({location:new URL(url)}),true);
  for(const url of ['https://icarus.test/?local=true','https://appassets.androidplatform.net/','http://appassets.androidplatform.net/assets/web/index.html','https://appassets.androidplatform.net.evil.test/assets/web/index.html','https://appassets.androidplatform.net/assets/other/index.html'])assert.equal(isLocalMode({location:new URL(url)}),false);
});

test('local user, Memory CRUD and conversation CRUD survive adapter recreation without network',async()=>{
  const f=fixture();
  assert.equal((await f.adapter('/me')).user.local,true);
  assert.equal((await f.adapter('/capabilities')).webSearch,false);
  const {memory}=await f.request('/memories',{content:'Keep this explicit memory'});
  assert.equal((await f.adapter('/memories')).memories[0].id,memory.id);
  await f.request('/memories/'+memory.id,{content:'Updated preference'},'PATCH');
  assert.equal((await f.adapter('/memories/'+memory.id)).memory.content,'Updated preference');
  const {conversation}=await f.request('/conversations',{title:'My plans'});
  await f.request('/conversations/'+conversation.id,{title:'Revised plans'},'PATCH');
  const reopened=createLocalApi({host:f.host,storage:f.storage,modelRequest:f.model});
  assert.equal((await reopened('/conversations')).conversations[0].title,'Revised plans');
  assert.equal((await reopened('/memories')).memories[0].content,'Updated preference');
  await f.request('/memories/'+memory.id,{},'DELETE');
  await f.request('/conversations/'+conversation.id,{},'DELETE');
  assert.deepEqual((await reopened('/memories')).memories,[]);
  assert.deepEqual((await reopened('/conversations')).conversations,[]);
  await assert.rejects(f.adapter('/integrations'),error=>error.local&&error.code==='local_feature_unavailable');
});

test('saved turns use only selected history and explicit Memory; temporary turns never read or write storage',async()=>{
  const f=fixture();
  await f.request('/memories',{content:'EXPLICIT_SAVED_MEMORY'});
  const first=await f.request('/chat',turn('SELECTED_HISTORY_MARKER'));
  await f.request('/chat',turn('OTHER_CONVERSATION_MARKER'));
  await f.request('/chat',turn('Continue this plan',{conversationId:first.conversationId}));
  const saved=f.calls.at(-1).args;
  assert.match(saved.prompt,/EXPLICIT_SAVED_MEMORY/);assert.match(saved.prompt,/SELECTED_HISTORY_MARKER/);assert.doesNotMatch(saved.prompt,/OTHER_CONVERSATION_MARKER/);
  assert.equal(saved.stateless,true);
  const before=f.values.get(LOCAL_STORAGE_KEY);
  await f.request('/chat/temporary',turn('PRIVATE_TEMPORARY_TEXT',{conversationId:first.conversationId,history:[{role:'user',content:'PRIVATE_TEMPORARY_HISTORY'}]}));
  const temporary=f.calls.at(-1).args;
  assert.match(temporary.prompt,/PRIVATE_TEMPORARY_HISTORY/);assert.doesNotMatch(temporary.prompt,/EXPLICIT_SAVED_MEMORY|SELECTED_HISTORY_MARKER|OTHER_CONVERSATION_MARKER/);
  assert.equal(temporary.temporary,true);assert.equal(temporary.stateless,true);assert.equal(f.values.get(LOCAL_STORAGE_KEY),before);
  const noStorage=fixture({storage:{getItem:()=>assert.fail('Temporary chat read saved storage'),setItem:()=>assert.fail('Temporary chat wrote storage')}});
  await noStorage.request('/chat/temporary',turn('Temporary without storage'));
});

test('concurrent and persisted retries deduplicate and reject changed payloads',async()=>{
  let finish,calls=0;
  const f=fixture({modelRequest:async()=>{calls++;return new Promise(resolve=>{finish=resolve;});}}),input=turn('Question');
  const a=f.request('/chat',input),b=f.request('/chat',input);
  await new Promise(resolve=>setImmediate(resolve));assert.equal(calls,1);finish({content:'One answer'});
  const [first,second]=await Promise.all([a,b]);assert.deepEqual(first,second);
  const reopened=createLocalApi({host:f.host,storage:f.storage,modelRequest:()=>assert.fail('Persisted retry called model')});
  assert.deepEqual(await reopened('/chat',{method:'POST',body:JSON.stringify(input)}),first);
  assert.equal((await reopened('/conversations')).conversations[0].messages.length,2);
  await assert.rejects(f.request('/chat',{...input,message:'Changed'}),error=>error.status===409);
  await f.request('/conversations/'+first.conversationId,{},'DELETE');
  await assert.rejects(f.request('/chat',input),error=>error.status===410);
});

test('failed generation and failed atomic storage writes do not leave orphaned turns',async()=>{
  let attempts=0;
  const f=fixture({modelRequest:async()=>{if(++attempts===1)throw Error('not ready');return {content:'Retried answer'};}}),input=turn('Retry this');
  await assert.rejects(f.request('/chat',input));assert.deepEqual((await f.adapter('/conversations')).conversations,[]);
  await f.request('/chat',input);assert.equal((await f.adapter('/conversations')).conversations[0].messages.length,2);
  const g=fixture({storage:{getItem:()=>null,setItem:()=>{throw Error('QuotaExceededError');}}});
  await assert.rejects(g.request('/chat',turn('No room')),error=>error.status===507&&error.local);
  assert.deepEqual((await g.adapter('/conversations')).conversations,[]);
});

test('an in-flight turn cannot restore a conversation deleted during inference',async()=>{
  let finish,slow=false;
  const f=fixture({modelRequest:async()=>slow?new Promise(resolve=>{finish=resolve;}):{content:'Initial'}});
  const first=await f.request('/chat',turn('Start'));slow=true;
  const pending=f.request('/chat',turn('Continue',{conversationId:first.conversationId}));
  await new Promise(resolve=>setImmediate(resolve));await f.request('/conversations/'+first.conversationId,{},'DELETE');finish({content:'Too late'});
  await assert.rejects(pending,error=>error.status===410);assert.deepEqual((await f.adapter('/conversations')).conversations,[]);
});

test('reviewed action proposals and first device results are idempotent across reloads',async()=>{
  const calls=[],f=fixture({modelRequest:async(action,args)=>{calls.push({action,args});return {action:'get_battery',arguments:{},executionStatus:'not_executed'};}});
  await f.request('/memories',{content:'MEMORY_MUST_NOT_AUTHORIZE_COMMANDS'});
  const input=turn('Check battery'),response=await f.request('/chat',input);
  assert.equal(response.proposal.action,'get_battery');assert.ok(response.proposal.id);
  assert.equal(calls[0].action,'interpret_command');assert.deepEqual(calls[0].args,{command:'Check battery'});
  assert.equal(f.host.ICARUS_NATIVE_CHANNEL.postMessage(),undefined);
  await f.request('/actions/'+response.proposal.id+'/result',{status:'cancelled',summary:'Cancelled by the user'});
  await f.request('/actions/'+response.proposal.id+'/result',{status:'reported',summary:'Cannot replace the result'});
  const repeated=await f.request('/chat',input);assert.equal(repeated.proposal,undefined);assert.equal(repeated.actionResult.status,'cancelled');
  const stored=(await f.adapter('/conversations')).conversations[0].messages;
  assert.equal(stored.length,3);assert.match(stored[2].content,/Cancelled by the user/);assert.equal(calls.length,1);
});

test('native interpretation must map into the existing reviewed action shape',()=>{
  const envelope=(action,args)=>({action,arguments:args,executionStatus:'not_executed'});
  assert.equal(commandProposal(envelope('make_call',{contact:'Alex'}),'Call Alex').value,'Alex');
  assert.equal(commandProposal(envelope('wake_word',{enabled:false}),'Stop listening').action,'stop_listening');
  assert.equal(commandProposal(envelope('set_timer',{seconds:60}),'Set a timer for one minute').value,'60');
  assert.equal(commandProposal(envelope('set_timer',{durationSeconds:60}),'Set a timer for one minute').value,'60');
  assert.throws(()=>commandProposal(envelope('set_timer',{seconds:60,durationSeconds:120}),'Set a timer for one minute'));
  assert.equal(commandProposal(envelope('unknown',{}),'Open something'),null);
  for(const [data,text] of [[envelope('make_call',{contact:'Invented'}),'Call Alex'],[envelope('send_sms',{contact:'Alex',message:'Hi'}),'Call Alex'],[envelope('set_volume',{level:120}),'Set volume to 120'],[envelope('toggle_flashlight',{enabled:'true'}),'Turn flashlight on'],[{action:'get_battery',arguments:{},executionStatus:'executed'},'Check battery']])assert.throws(()=>commandProposal(data,text));
  for(const text of ['Imagine you call Alex','If I say call Alex, what happens?','He said "call Alex"','Explain how to open Maps','Tell me about battery chemistry'])assert.equal(isExplicitDeviceRequest(text),false,text);
});

test('model prose never executes or proposes actions; cloud search fails without model/network calls',async()=>{
  const f=fixture({modelRequest:async(action)=>{assert.equal(action,'local_chat');return {content:'{"action":"make_call","arguments":{"contact":"Alex"}}'};}});
  const response=await f.request('/chat',turn('Explain actions'));
  assert.equal(response.proposal,undefined);
  await assert.rejects(f.request('/chat',turn('Search now',{search:true})),error=>error.code==='local_search_unavailable');
});

test('corrupt device data is preserved rather than silently reset',()=>{
  const values=new Map([[LOCAL_STORAGE_KEY,'{not json']]),storage={getItem:key=>values.get(key),setItem:()=>assert.fail('Corrupt data overwritten')};
  assert.throws(()=>createLocalStore(storage).read(),error=>error.code==='local_storage_invalid');assert.equal(values.get(LOCAL_STORAGE_KEY),'{not json');
});

test('prompt remains within native limit and always retains the current request',()=>{
  const history=Array.from({length:100},(_,i)=>({role:i%2?'assistant':'user',content:`HISTORY_${i} ${'x'.repeat(5000)}`}));
  history.push({role:'user',content:'CURRENT_REQUEST_MUST_SURVIVE'});
  const prompt=buildLocalPrompt(history,Array.from({length:50},(_,i)=>({content:`MEMORY_${i} ${'y'.repeat(4000)}`})));
  assert.ok(prompt.length<24000);assert.match(prompt,/CURRENT_REQUEST_MUST_SURVIVE/);assert.match(prompt,/history_omitted":true/);assert.match(prompt,/memory_omitted":true/);
});

test('native model callbacks correlate IDs and return actionable missing-model errors',async()=>{
  const f=fixture();let request;
  f.host.ICARUS_NATIVE_CHANNEL.postMessage=raw=>{request=JSON.parse(raw);};
  const pending=nativeModelRequest('local_chat',{prompt:'Hello'},{host:f.host,timeoutMs:1000});
  f.host.ICARUS_NATIVE_RESULT({ok:true,requestId:'not-this-request',data:{content:'Wrong response'}});
  f.host.ICARUS_NATIVE_RESULT({ok:false,requestId:request.requestId,error:'local_model_not_downloaded'});
  await assert.rejects(pending,error=>error.local&&/Settings → On-device model/.test(error.message));
  assert.equal(f.host.ICARUS_NATIVE_RESULT,undefined);
});

test('public adapter never fetches in local mode but preserves hosted cloud routing',async()=>{
  const previous=globalThis.window,f=fixture();globalThis.window=f.host;
  try{assert.equal((await api('/me')).user.local,true);await assert.rejects(api('/auth/login',{method:'POST',body:'{}'}),error=>error.local);}
  finally{globalThis.window=previous;}
  const requests=[],cloud={...f.host,location:new URL('https://icarus.test'),AbortSignal,fetch:async(url,options)=>{requests.push({url,options});return {ok:true,json:async()=>({user:{name:'Cloud user'}})};}};
  cloud.localStorage.setItem('icarus_token','cloud-token');globalThis.window=cloud;
  try{assert.equal((await api('/me')).user.name,'Cloud user');assert.equal(requests[0].url,'/api/me');assert.equal(requests[0].options.headers.authorization,'Bearer cloud-token');}
  finally{globalThis.window=previous;}
});

test('deleting a conversation removes its text from every stored record',async()=>{
  const f=fixture(),input=turn('PRIVATE_DELETE_MARKER'),response=await f.request('/chat',input);
  await f.request('/conversations/'+response.conversationId,{},'DELETE');
  assert.doesNotMatch(f.values.get(LOCAL_STORAGE_KEY),/PRIVATE_DELETE_MARKER|Local answer/);
  await assert.rejects(f.request('/chat',input),error=>error.status===410);
});
