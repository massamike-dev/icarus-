import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {createHandler} from './app.js';
import {emptyStore} from './store.js';
import {assistantTurn} from './assistant.js';
const env={ICARUS_AI_API_KEY:'test-key'};
const messages=[{role:'user',content:'Turn on the flashlight'}];
const call=(action='toggle_flashlight',value='on')=>({type:'function_call',name:'propose_device_action',arguments:JSON.stringify({action,value})});
const outputText=text=>({type:'message',role:'assistant',content:[{type:'output_text',text}]});
function mock(data,inspect=()=>{}) {return async(url,options)=>{inspect(url,JSON.parse(options.body),options);return {ok:true,json:async()=>data};};}

test('Responses assistant proposals retain native-only, single-action review safeguards',async()=>{
  const responseEnv={...env,ICARUS_AI_API_MODE:'responses'};
  const valid=await assistantTurn(messages,[],responseEnv,mock({output:[call()]}),{native:true});
  assert.equal(valid.proposal.action,'toggle_flashlight');assert.equal(valid.proposal.executionStatus,'not_executed');
  assert.match(valid.reply,/Nothing has been executed/);
  const blocked=[
    [false,[call()]],
    [true,[call(),call('make_call','Alex')]],
    [true,[{type:'function_call'}]],
    [true,[{...call(),arguments:'not json'}]],
    [true,[{...call(),name:'execute_shell'}]],
    [true,[call('set_volume','101')]],
    [true,[call('make_call','')]],
    [true,[call('unknown','')]],
  ];
  for(const [native,output] of blocked){
    const result=await assistantTurn(messages,[],responseEnv,mock({output:[outputText('Done!'),...output]}),{native});
    assert.equal(result.proposal,undefined);assert.doesNotMatch(result.reply,/Done!/);assert.match(result.reply,/Nothing was executed/);
  }
});

test('Responses normal answers and voice fallback do not gain device tools',async()=>{
  for(const options of [{voice:true},{}]) {
  const result=await assistantTurn([{role:'user',content:'Explain supported commands'}],[],{...env,ICARUS_AI_API_MODE:'responses'},mock({output:[outputText('Here are the supported commands.')]},(url,body)=>{
    assert.ok(url.endsWith('/responses'));assert.equal(body.tools,undefined);assert.equal(body.store,false);
  }),options);
  assert.equal(result.reply,'Here are the supported commands.');assert.equal(result.proposal,undefined);
  }
});

test('Responses web search remains read-only and strips private context',async()=>{
  const result=await assistantTurn([{role:'user',content:'PRIVATE_HISTORY'}, {role:'user',content:'Search the web for Jupiter'}],[{content:'PRIVATE_MEMORY'}],{...env,ICARUS_AI_API_MODE:'responses'},mock({output:[{type:'web_search_call',status:'completed'},outputText('Jupiter is a planet'),call()]},(url,body)=>{
    assert.ok(url.endsWith('/responses'));assert.deepEqual(body.tools,[{type:'web_search'}]);
    assert.equal(body.store,false);assert.doesNotMatch(JSON.stringify(body),/PRIVATE_HISTORY|PRIVATE_MEMORY/);
  }),{native:true});
  assert.equal(result.searched,true);assert.equal(result.proposal,undefined);
});

test('Responses HTTP chat and voice retain saved context, proposals, retries, and privacy',async()=>{
  const data=emptyStore();const store={read:async()=>structuredClone(data),update:async fn=>fn(data)};
  const calls=[];
  const provider=async(url,options)=>{
    assert.ok(url.endsWith('/responses'));
    const body=JSON.parse(options.body);calls.push(body);assert.equal(body.store,false);
    const input=body.input.at(-1).content;
    const output=body.text?.format?
      [outputText(JSON.stringify(input==='turn flashlight on'?{action:'toggle_flashlight',value:'on'}:{action:'unknown',value:''}))]:
      input==='turn flashlight on'?[call()]:[outputText('Answer: '+input)];
    return {ok:true,json:async()=>({status:'completed',output})};
  };
  const server=createServer(createHandler({store,env:{...env,NODE_ENV:'test',ICARUS_AI_API_MODE:'responses'},fetchImpl:provider}));
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${server.address().port}/api`;
  let token;
  const post=async(path,body)=>{
    const response=await fetch(base+path,{method:'POST',headers:{'content-type':'application/json',...(token?{authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body)});
    return {status:response.status,body:await response.json()};
  };
  try{
    token=(await post('/auth/register',{email:'responses@example.test',password:'long-test-password'})).body.token;
    const chat=await post('/chat',{message:'PRIVATE_HISTORY garden project',clientTurnId:'chat-1'});
    assert.equal(chat.status,200);assert.ok(chat.body.conversationId);assert.match(chat.body.reply,/garden project/);
    const voice=await post('/commands/interpret',{command:'What were we discussing?',conversationId:chat.body.conversationId,clientTurnId:'voice-1'});
    assert.equal(voice.status,200);assert.equal(voice.body.conversationId,chat.body.conversationId);
    assert.match(JSON.stringify(calls.at(-1).input),/garden project/);assert.equal(calls.at(-1).tools,undefined);
    const actionBody={message:'turn flashlight on',native:true,conversationId:chat.body.conversationId,clientTurnId:'chat-action'};
    const action=await post('/chat',actionBody);
    assert.equal(action.body.proposal.executionStatus,'not_executed');assert.ok(action.body.proposal.id);
    const beforeRetry=calls.length;assert.deepEqual(await post('/chat',actionBody),action);assert.equal(calls.length,beforeRetry);
    const voiceAction=await post('/commands/interpret',{command:'turn flashlight on',conversationId:chat.body.conversationId,clientTurnId:'voice-action'});
    assert.equal(voiceAction.status,200);assert.equal(voiceAction.body.executionStatus,'not_executed');assert.ok(voiceAction.body.proposalId);
    assert.equal(calls.at(-1).text.format.strict,true);
    const beforeTemporary=await store.read();
    const temporary=await post('/commands/interpret',{command:'Hello',temporary:true,conversationId:chat.body.conversationId});
    assert.equal(temporary.status,200);assert.doesNotMatch(JSON.stringify(calls.at(-1)),/PRIVATE_HISTORY/);
    assert.deepEqual(await store.read(),beforeTemporary);
    assert.equal(data.messages.filter(message=>message.channel==='voice').length,4);
  }finally{await new Promise(resolve=>server.close(resolve));}
});
