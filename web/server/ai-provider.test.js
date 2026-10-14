import test from 'node:test';
import assert from 'node:assert/strict';
import {requestAI,normalizeAIResponse} from './ai-provider.js';
import {interpretCommand,actions,validateCommand} from './commands.js';

const env={ICARUS_AI_API_KEY:'test-key'};
const messages=[{role:'user',content:'Turn on the flashlight'}];
const parameters={type:'object',properties:{action:{type:'string'},value:{type:'string'}},required:['action','value'],additionalProperties:false};
const tool={type:'function',function:{name:'propose_device_action',description:'Propose for review',parameters,strict:true}};
const call=(action='toggle_flashlight',value='on')=>({type:'function_call',name:'propose_device_action',arguments:JSON.stringify({action,value})});
const outputText=text=>({type:'message',role:'assistant',content:[{type:'output_text',text}]});
function mock(data,inspect=()=>{}) {return async(url,options)=>{inspect(url,JSON.parse(options.body),options);return {ok:true,json:async()=>data};};}

test('default adapter preserves Chat Completions, model, tools, and timeout signal',async()=>{
  const result=await requestAI({env,system:'system',messages,tools:[tool],fetchImpl:mock({choices:[{message:{content:'hello'}}]},(url,body,opts)=>{
    assert.equal(url,'https://api.openai.com/v1/chat/completions');
    assert.equal(body.model,'gpt-5-mini');assert.equal(body.store,false);
    assert.deepEqual(body.messages,[{role:'system',content:'system'},...messages]);
    assert.deepEqual(body.tools,[tool]);assert.equal(body.parallel_tool_calls,false);
    assert.equal(body.input,undefined);assert.ok(opts.signal instanceof AbortSignal);
    assert.equal(opts.headers.authorization,'Bearer test-key');
  })});
  assert.deepEqual(result,{content:'hello',tool_calls:[]});
});

test('Responses opt-in honors custom provider/model and flattens strict functions',async()=>{
  const result=await requestAI({env:{...env,ICARUS_AI_API_MODE:'responses',ICARUS_AI_BASE_URL:'https://provider.test/v1/',ICARUS_AI_MODEL:'gpt-6.1-sol'},system:'system',messages,tools:[tool],fetchImpl:mock({status:'completed',output:[{type:'reasoning',summary:[]},call()]},(url,body)=>{
    assert.equal(url,'https://provider.test/v1/responses');assert.equal(body.model,'gpt-6.1-sol');
    assert.equal(body.store,false);assert.equal(body.instructions,'system');assert.deepEqual(body.input,messages);
    assert.deepEqual(body.tools,[{type:'function',...tool.function}]);assert.equal(body.parallel_tool_calls,false);
    for(const key of ['previous_response_id','conversation','messages']) assert.equal(body[key],undefined);
  })});
  assert.equal(result.tool_calls.length,1);assert.equal(result.tool_calls[0].function.name,'propose_device_action');
  assert.equal(validateCommand(JSON.parse(result.tool_calls[0].function.arguments)).executionStatus,'not_executed');
});

test('only explicit responses mode changes endpoint; tool-free requests stay tool-free',async()=>{
  for(const mode of [undefined,'chat','chat/completions','typo']){
    await requestAI({env:{...env,ICARUS_AI_API_MODE:mode},system:'s',messages,fetchImpl:mock({choices:[]},(url,body)=>{
      assert.ok(url.endsWith('/chat/completions'));assert.equal(body.tools,undefined);assert.equal(body.parallel_tool_calls,undefined);
    })});
  }
});

test('Responses reads only public output text and refuses partial envelopes',()=>{
  assert.deepEqual(normalizeAIResponse({output:[{type:'reasoning',summary:[{text:'private'}]},outputText('hello'),outputText('world')]},'responses'),{content:'hello\nworld',tool_calls:[]});
  for(const data of [{},{output:[],status:'incomplete'},{output:[],status:'failed'},{output:[],error:{message:'bad'}},{output:[{...outputText('partial'),status:'incomplete'}]}]) assert.throws(()=>normalizeAIResponse(data,'responses'),/invalid_provider_response/);
});

test('multiple, unknown and malformed function calls remain visible for caller rejection',()=>{
  const multiple=normalizeAIResponse({output:[call(),call('make_call','Alex')]},'responses');
  assert.equal(multiple.tool_calls.length,2);
  for(const item of [{type:'function_call'}, {...call(),arguments:{}},{...call(),status:'in_progress'},{type:'web_search_call',status:'completed'}]){
    const result=normalizeAIResponse({output:[outputText('Done!'),item]},'responses');
    assert.equal(result.tool_calls.length,1);assert.notEqual(result.tool_calls[0].function.name,'propose_device_action');
  }
  const unknown=normalizeAIResponse({output:[{...call(),name:'execute_shell'}]},'responses');
  assert.equal(unknown.tool_calls[0].function.name,'execute_shell');
  const malformedJSON=normalizeAIResponse({output:[{...call(),arguments:'{invalid'}]},'responses');
  assert.throws(()=>JSON.parse(malformedJSON.tool_calls[0].function.arguments));
  for(const tool_calls of [[null],[{}],{}]) assert.equal(normalizeAIResponse({choices:[{message:{tool_calls}}]}).tool_calls.length,1);
});

test('Responses commands use strict JSON schema and return a validated unexecuted command',async()=>{
  const result=await interpretCommand('set timer for 1 minute',{...env,ICARUS_AI_API_MODE:'responses'},mock({output:[outputText('{"action":"set_timer","value":"60"}')]},(url,body)=>{
    assert.ok(url.endsWith('/responses'));assert.equal(body.store,false);assert.equal(body.tools,undefined);
    assert.equal(body.text.format.type,'json_schema');assert.equal(body.text.format.strict,true);
    assert.equal(body.text.format.name,'device_command');assert.deepEqual(body.text.format.schema.properties.action.enum,actions);
    assert.deepEqual(body.text.format.schema.required,['action','value']);assert.equal(body.text.format.schema.additionalProperties,false);
    assert.match(body.instructions,/skip confirmations/);assert.equal(body.response_format,undefined);
  }));
  assert.equal(result.action,'set_timer');assert.equal(result.value,'60');assert.equal(result.executionStatus,'not_executed');
});

test('default command interpreter preserves legacy JSON mode and validates provider output',async()=>{
  const result=await interpretCommand('battery',env,mock({choices:[{message:{content:'{"action":"get_battery","value":"","confirmed":true}'}}]},(url,body)=>{
    assert.ok(url.endsWith('/chat/completions'));assert.deepEqual(body.response_format,{type:'json_object'});
    assert.equal(body.model,'gpt-5-mini');assert.equal(body.store,false);
  }));
  assert.equal(result.confirmed,undefined);assert.equal(result.executionStatus,'not_executed');
});

test('structured commands reject unexpected calls, invalid JSON, capabilities and ranges',async()=>{
  const responseEnv={...env,ICARUS_AI_API_MODE:'responses'};
  for(const output of [[call(),outputText('{"action":"get_battery","value":""}')],[outputText('{invalid')],[outputText('{"action":"shell","value":"ls"}')],[outputText('{"action":"set_volume","value":"101"}')],[outputText('{"action":"make_call","value":""}')]]) {
    await assert.rejects(interpretCommand('command',responseEnv,mock({output})));
  }
});

test('HTTP errors keep caller-specific error codes and do not retry',async()=>{
  let attempts=0;const fetchImpl=async()=>{attempts++;return {ok:false};};
  await assert.rejects(requestAI({env,fetchImpl,system:'s',messages}),/assistant_provider_unavailable/);
  await assert.rejects(interpretCommand('battery',env,fetchImpl),/command_provider_unavailable/);
  assert.equal(attempts,2);
});
