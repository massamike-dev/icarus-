import test from 'node:test';
import assert from 'node:assert/strict';
import {assistantTurn,capabilities} from './assistant.js';
import {actionRequest,executeProposal} from '../src/device-actions.js';
const env={ICARUS_AI_API_KEY:'test-key'};
const history=[{role:'user',content:'Turn on my flashlight'}];
const response=data=>async()=>({ok:true,json:async()=>data});
const tool=(action,value)=>({choices:[{message:{content:'Done!',tool_calls:[{function:{name:'propose_device_action',arguments:JSON.stringify({action,value,confirmed:true})}}]}}]});
test('phone tools are offered only to connected Android and never during search',async()=>{
  let body;await assistantTurn(history,[],env,async(url,opts)=>{body=JSON.parse(opts.body);return {ok:true,json:async()=>({choices:[{message:{content:'hello'}}]})}});
  assert.equal(body.tools,undefined);assert.match(body.messages[0].content,/Never invent a knowledge-cutoff/);
  const r=await assistantTurn(history,[],env,response(tool('toggle_flashlight','on')),{native:true});
  assert.equal(r.proposal.executionStatus,'not_executed');assert.equal(r.proposal.confirmed,undefined);assert.doesNotMatch(r.reply,/Done!/);
  assert.equal((await assistantTurn(history,[],env,response(tool('make_purchase','car')),{native:true})).proposal,undefined);
  assert.equal((await assistantTurn(history,[],env,response(tool('set_volume','101')),{native:true})).proposal,undefined);
});
test('web search uses read-only tools and returns only safe citation URLs',async()=>{
  const r=await assistantTurn(history,[],env,async(url,opts)=>{
    assert.ok(url.endsWith('/responses'));const p=JSON.parse(opts.body);assert.equal(p.store,false);assert.deepEqual(p.tools,[{type:'web_search'}]);
    return {ok:true,json:async()=>({output:[{type:'web_search_call',status:'completed'},{type:'message',content:[{type:'output_text',text:'Current answer',annotations:[{type:'url_citation',url:'https://example.com',title:'Source'},{type:'url_citation',url:'javascript:alert(1)'}]}]}]})};
  },{native:true,search:true});
  assert.equal(r.searched,true);assert.equal(r.sources.length,1);assert.equal(r.proposal,undefined);
  assert.equal(capabilities({...env,ICARUS_AI_BASE_URL:'https://other.test/v1'}).webSearch,false);
});
test('failed search is never represented as current information',async()=>{
  const r=await assistantTurn(history,[],env,response({output:[]}),{search:true});assert.match(r.reply,/did not complete/);assert.equal(r.searched,undefined);
});
function hostFor(result,{supported=true}={}){const sent=[];const host={IcarusNative:{postMessage:raw=>{const p=JSON.parse(raw);sent.push(p);queueMicrotask(()=>{if(p.bridgeRequest)host.ICARUS_NATIVE_STATUS?.({requestId:p.requestId,actionProtocolVersion:supported?1:0});else if(result)host.ICARUS_NATIVE_RESULT?.({...result,requestId:p.requestId})});}}};return {host,sent};}
test('device validates mapping and confirms results only from matching request',async()=>{
  assert.deepEqual(actionRequest({action:'make_call',value:'Trisha'}).arguments,{contact:'Trisha'});
  assert.throws(()=>actionRequest({action:'set_timer',value:'0'}));
  const {host,sent}=hostFor({ok:true,data:{level:70,charging:true}});
  const reply=await executeProposal({action:'get_battery'},host);assert.match(reply,/70%/);assert.equal(sent.filter(x=>x.action).length,1);
});
test('old hosts cannot execute and Android errors do not become success',async()=>{
  const old=hostFor(null,{supported:false});assert.match(await executeProposal({action:'get_battery'},old.host),/Update ICARUS/);assert.equal(old.sent.filter(x=>x.action).length,0);
  const denied=hostFor({ok:false,error:'permission_required'});assert.match(await executeProposal({action:'make_call',value:'Trisha'},denied.host),/permission required/);
  const timeout=hostFor(null);assert.match(await executeProposal({action:'get_battery'},timeout.host,5),/may have started/);
});
