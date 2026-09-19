import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {createHandler} from './app.js';
import {emptyStore} from './store.js';

class MemoryStore{
  constructor(){this.data=emptyStore();this.queue=Promise.resolve();}
  async read(){return structuredClone(this.data);}
  update(fn){const result=this.queue.then(()=>fn(this.data));this.queue=result.catch(()=>{});return result;}
}
async function fixture(fn){
  const store=new MemoryStore(),calls=[];
  let fail=false;
  const provider=async(url,opts)=>{
    const p=JSON.parse(opts.body);calls.push(p);
    await new Promise(r=>setTimeout(r,10));
    if(fail)return {ok:false};
    const text=p.messages?.at(-1)?.content;
    if(p.response_format)return {ok:true,json:async()=>({choices:[{message:{content:JSON.stringify(text==='turn flashlight on'?{action:'toggle_flashlight',value:'on'}:{action:'unknown'})}}]})};
    return {ok:true,json:async()=>({choices:[{message:text==='turn flashlight on'?{tool_calls:[{function:{name:'propose_device_action',arguments:'{"action":"toggle_flashlight","value":"on"}'}}]}:{content:`Answer: ${text}`}}]})};
  };
  const server=createServer(createHandler({store,env:{NODE_ENV:'test',ICARUS_AI_API_KEY:'fixture-key'},fetchImpl:provider}));
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const base=`http://127.0.0.1:${server.address().port}`;
  const request=async(path,{token,method='GET',data}={})=>{
    const r=await fetch(base+'/api'+path,{method,headers:{'content-type':'application/json',...(token?{authorization:`Bearer ${token}`}:{})},...(data?{body:JSON.stringify(data)}:{})});
    return {status:r.status,body:await r.json()};
  };
  const register=async email=>(await request('/auth/register',{method:'POST',data:{email,password:'test-password-long'}})).body.token;
  try{await fn({store,calls,request,register,setFailure:value=>{fail=value}});}finally{await new Promise(r=>server.close(r));}
}

test('voice follows selected Chat context and its reply persists with source channel',()=>fixture(async({request,register,calls})=>{
  const token=await register('a@example.test');
  const first=await request('/chat',{token,method:'POST',data:{message:'We are building a garden wall',clientTurnId:'chat-1'}});
  const id=first.body.conversationId;
  const voice=await request('/commands/interpret',{token,method:'POST',data:{command:'What did I say we are building?',conversationId:id,clientTurnId:'voice-1'}});
  assert.equal(voice.status,200);assert.equal(voice.body.conversationId,id);
  assert.ok(calls.at(-1).messages.some(m=>m.content==='We are building a garden wall'));
  const history=(await request('/conversations',{token})).body.conversations;
  assert.equal(history.length,1);assert.equal(history[0].messages.length,4);
  assert.equal(history[0].messages.at(-1).channel,'voice');
}));

test('temporary voice ignores saved conversation and Memory and creates no history/proposals',()=>fixture(async({request,register,store,calls})=>{
  const token=await register('a@example.test');
  await request('/memories',{token,method:'POST',data:{content:'PRIVATE_MEMORY_MARKER'}});
  const saved=await request('/chat',{token,method:'POST',data:{message:'PRIVATE_HISTORY_MARKER',clientTurnId:'chat-1'}});
  const before=await store.read();
  await request('/commands/interpret',{token,method:'POST',data:{command:'Hello',temporary:true,conversationId:saved.body.conversationId}});
  assert.doesNotMatch(JSON.stringify(calls.at(-1)),/PRIVATE_MEMORY_MARKER|PRIVATE_HISTORY_MARKER/);
  const action=await request('/commands/interpret',{token,method:'POST',data:{command:'turn flashlight on',temporary:true}});
  assert.equal(action.body.proposalId,undefined);assert.equal(action.body.proposal.id,undefined);
  assert.deepEqual(await store.read(),before);
}));

test('retries of the same turn share a response; reusing its ID for different input is rejected',()=>fixture(async({request,register,calls,store})=>{
  const token=await register('a@example.test'),options={token,method:'POST',data:{message:'Hello',clientTurnId:'same-turn'}};
  const [a,b]=await Promise.all([request('/chat',options),request('/chat',options)]);
  assert.deepEqual(a,b);assert.equal(calls.length,1);assert.equal((await store.read()).messages.length,2);
  assert.deepEqual(await request('/chat',options),a);
  assert.equal(calls.length,1);
  const conflict=await request('/chat',{...options,data:{message:'Other',clientTurnId:'same-turn'}});
  assert.equal(conflict.status,409);
}));

test('provider failure saves no conversation or orphan message and can be retried',()=>fixture(async({request,register,store,setFailure})=>{
  const token=await register('a@example.test');setFailure(true);
  const options={token,method:'POST',data:{message:'Hello',clientTurnId:'retry-me'}};
  assert.equal((await request('/chat',options)).status,500);
  assert.equal((await store.read()).messages.length,0);assert.equal((await store.read()).conversations.length,0);
  setFailure(false);assert.equal((await request('/chat',options)).status,200);assert.equal((await store.read()).messages.length,2);
}));

test('action result belongs to its user, persists once, and never rewrites first receipt',()=>fixture(async({request,register,store})=>{
  const token=await register('a@example.test'),other=await register('b@example.test');
  const turn=await request('/chat',{token,method:'POST',data:{message:'turn flashlight on',native:true,clientTurnId:'action-turn'}});
  const id=turn.body.proposal.id;assert.ok(id);assert.equal(turn.body.proposal.executionStatus,'not_executed');
  const path=`/actions/${id}/result`,report={method:'POST',data:{status:'reported',summary:'Android reported flashlight on.'}};
  assert.equal((await request(path,{...report,token:other})).status,404);
  const result=await request(path,{...report,token});assert.equal(result.status,200);
  const duplicate=await request(path,{...report,token,data:{status:'reported',summary:'Changed!'}});assert.deepEqual(duplicate,result);
  const replay=await request('/chat',{token,method:'POST',data:{message:'turn flashlight on',native:true,clientTurnId:'action-turn'}});
  assert.equal(replay.body.proposal,undefined);assert.equal(replay.body.proposalId,undefined);assert.match(replay.body.reply,/Previously recorded/);
  const d=await store.read();assert.equal(d.messages.length,3);assert.match(d.messages.at(-1).content,/not independently verified/);
  assert.doesNotMatch(d.messages.at(-1).content,/Changed!/);
  await request('/conversations/'+turn.body.conversationId,{token,method:'DELETE'});
  assert.equal((await request(path,{...report,token})).status,404);
  const deleted=await store.read();assert.equal(deleted.turns[0].deleted,true);assert.equal(deleted.turns[0].response,undefined);assert.equal(deleted.actionRequests.length,0);
  assert.equal((await request('/chat',{token,method:'POST',data:{message:'turn flashlight on',native:true,clientTurnId:'action-turn'}})).status,410);
  assert.equal((await store.read()).messages.length,0);
}));

test('a cancelled voice action cannot become executable again on turn retry',()=>fixture(async({request,register})=>{
  const token=await register('voice@example.test');
  const options={token,method:'POST',data:{command:'turn flashlight on',clientTurnId:'voice-action'}};
  const first=await request('/commands/interpret',options);
  await request(`/actions/${first.body.proposalId}/result`,{token,method:'POST',data:{status:'cancelled',summary:'User cancelled.'}});
  const replay=await request('/commands/interpret',options);
  assert.equal(replay.body.action,'unknown');assert.equal(replay.body.proposal,undefined);assert.equal(replay.body.executionStatus,'cancelled');
}));

test('foreign conversation IDs cannot seed Chat or voice context; account deletion clears receipts',()=>fixture(async({request,register,store})=>{
  const token=await register('a@example.test'),other=await register('b@example.test');
  const turn=await request('/chat',{token,method:'POST',data:{message:'turn flashlight on',native:true,clientTurnId:'owned-turn'}});
  for(const path of ['/chat','/commands/interpret']){
    assert.equal((await request(path,{token:other,method:'POST',data:{message:'Hello',command:'Hello',conversationId:turn.body.conversationId}})).status,404);
  }
  await request('/account',{token,method:'DELETE'});
  const d=await store.read();assert.equal(d.turns.length,0);assert.equal(d.actionRequests.length,0);assert.equal(d.messages.length,0);
}));
