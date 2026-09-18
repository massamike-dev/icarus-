import test from 'node:test';
import assert from 'node:assert/strict';
import {actionRequest,checkDevice,executeProposal,readDeviceStatus,readVoiceSession,subscribeNative} from '../src/device-actions.js';
import {getNativeTransport} from '../src/native-transport.js';

const tick=()=>new Promise(resolve=>setImmediate(resolve));
function device({automaticStatus=true,version=1}={}) {
  const requests=[];
  const host={ICARUS_NATIVE_CHANNEL:{postMessage(raw){
    const request=JSON.parse(raw);requests.push(request);
    if(automaticStatus&&request.bridgeRequest)host.ICARUS_NATIVE_STATUS?.({requestId:request.requestId,actionProtocolVersion:version});
  }}};
  const reply=(request,data,extra={})=>host.ICARUS_NATIVE_RESULT?.({requestId:request.requestId,ok:true,data,...extra});
  return {host,requests,reply};
}

test('native transport prefers the canonical Android channel over the legacy bridge',()=>{
  const canonical={postMessage(){}},legacy={postMessage(){}};
  assert.equal(getNativeTransport({ICARUS_NATIVE_CHANNEL:canonical,IcarusNative:legacy}),canonical);
});

test('native transport accepts a valid legacy bridge when the canonical channel is absent or invalid',()=>{
  const legacy={postMessage(){}};
  for(const canonical of [undefined,null,{}, {postMessage:true},{postMessage:'invalid'}]){
    assert.equal(getNativeTransport({ICARUS_NATIVE_CHANNEL:canonical,IcarusNative:legacy}),legacy);
  }
});

test('native transport rejects missing bridges and non-callable postMessage values',()=>{
  for(const host of [{},{ICARUS_NATIVE_CHANNEL:{}},{IcarusNative:{postMessage:true}},{ICARUS_NATIVE_CHANNEL:{postMessage:'invalid'},IcarusNative:{postMessage:null}}]){
    assert.equal(getNativeTransport(host),null);
  }
});

test('callback subscribers dispose out of order without retaining unmounted listeners',()=>{
  const received=[];
  const previous=()=>received.push('previous'),host={ICARUS_NATIVE_RESULT:previous};
  const first=subscribeNative(host,'result',()=>received.push('first'));
  const second=subscribeNative(host,'result',()=>received.push('second'));
  first();host.ICARUS_NATIVE_RESULT('{}');second();
  assert.deepEqual(received,['second','previous']);
  assert.equal(host.ICARUS_NATIVE_RESULT,previous);
  const third=subscribeNative(host,'result',()=>{throw Error('consumer failed');});
  const fourth=subscribeNative(host,'result',()=>received.push('fourth'));
  host.ICARUS_NATIVE_RESULT('{}');fourth();third();
  assert.deepEqual(received,['second','previous','fourth','previous']);
  assert.equal(host.ICARUS_NATIVE_RESULT,previous);
  assert.throws(()=>subscribeNative(host,'__proto__',()=>{}));
});

test('overlapping status checks share one request and only accept its correlated response',async()=>{
  const {host,requests}=device({automaticStatus:false});
  const first=readDeviceStatus(host,100),second=checkDevice(host);
  assert.equal(requests.length,1);
  host.ICARUS_NATIVE_STATUS('{invalid');
  host.ICARUS_NATIVE_STATUS([]);
  host.ICARUS_NATIVE_STATUS({requestId:'unrelated',actionProtocolVersion:1});
  host.ICARUS_NATIVE_STATUS({requestId:requests[0].requestId,actionProtocolVersion:1});
  assert.equal((await first).requestId,requests[0].requestId);assert.equal(await second,true);
  assert.equal(host.ICARUS_NATIVE_STATUS,undefined);
});

test('missing and unsupported protocol versions never dispatch a device action',async()=>{
  for(const version of [undefined,null,'1',0,2,true]){
    const {host,requests}=device({version});
    // The helper default supplies 1 when version is undefined; explicitly omit it.
    if(version===undefined)host.ICARUS_NATIVE_CHANNEL.postMessage=raw=>{const p=JSON.parse(raw);requests.push(p);host.ICARUS_NATIVE_STATUS?.({requestId:p.requestId});};
    const result=await executeProposal({action:'get_battery'},host);
    assert.match(result,/Nothing was sent/);assert.equal(requests.filter(p=>p.action).length,0);
  }
});

test('status timeout releases its callback and a late response cannot satisfy the next check',async()=>{
  const {host,requests}=device({automaticStatus:false});
  assert.equal(await readDeviceStatus(host,5),null);
  assert.equal(host.ICARUS_NATIVE_STATUS,undefined);
  const next=readDeviceStatus(host,100);
  host.ICARUS_NATIVE_STATUS({requestId:requests[0].requestId,actionProtocolVersion:1});
  host.ICARUS_NATIVE_STATUS({requestId:requests[1].requestId,actionProtocolVersion:0});
  assert.equal((await next).actionProtocolVersion,0);
});

test('voice session requires valid privacy state and a supported correlated status',async()=>{
  for(const [voiceSession,expected] of [
    [{conversationId:'conversation-1',temporary:false},{conversationId:'conversation-1',temporary:false}],
    [{conversationId:null,temporary:true},{conversationId:null,temporary:true}],
    [{conversationId:'saved',temporary:true},null],
    [{conversationId:3,temporary:false},null],
    [{conversationId:null,temporary:'false'},null],
    [{temporary:false},null]
  ]){
    const {host}=device({automaticStatus:false});
    host.ICARUS_NATIVE_CHANNEL.postMessage=raw=>{const p=JSON.parse(raw);host.ICARUS_NATIVE_STATUS({requestId:p.requestId,actionProtocolVersion:1,voiceSession});};
    assert.deepEqual(await readVoiceSession(host),expected);
  }
});

test('concurrent device actions route out-of-order results once without stale callback chains',async()=>{
  const {host,requests,reply}=device();
  let settingsCalls=0;
  const removeSettings=subscribeNative(host,'result',()=>settingsCalls++);
  const battery=executeProposal({action:'get_battery'},host);
  const torch=executeProposal({action:'toggle_flashlight',value:'on'},host);
  await tick();
  const actions=requests.filter(p=>p.action);assert.equal(actions.length,2);
  removeSettings();
  reply(actions[1],{enabled:true});reply(actions[0],{level:67,charging:false});
  assert.match(await battery,/Battery: 67%/);assert.match(await torch,/flashlight on/);
  assert.equal(settingsCalls,0);assert.equal(host.ICARUS_NATIVE_RESULT,undefined);
});

test('mismatched and late result acknowledgements cannot claim success or trigger retries',async()=>{
  const {host,requests,reply}=device();
  const action=executeProposal({action:'get_battery'},host,10);await tick();
  const first=requests.find(p=>p.action);
  host.ICARUS_NATIVE_RESULT({requestId:'someone-else',ok:true,data:{level:100,charging:true}});
  host.ICARUS_NATIVE_RESULT('not json');
  assert.match(await action,/may have started/);
  const next=executeProposal({action:'get_battery'},host,100);await tick();
  reply(first,{level:100,charging:true});
  reply(requests.filter(p=>p.action)[1],{level:40,charging:false});
  assert.match(await next,/Battery: 40%/);assert.equal(requests.filter(p=>p.action).length,2);
  assert.equal(host.ICARUS_NATIVE_RESULT,undefined);
});

test('malformed or inconsistent success payloads remain unknown',async()=>{
  const cases=[
    [{action:'get_battery'},{level:50,charging:'false'}],
    [{action:'get_battery'},{level:-1,charging:false}],
    [{action:'toggle_flashlight',value:'on'},{enabled:false}],
    [{action:'set_volume',value:'50'},{level:200}],
    [{action:'stop_listening'},{}],
    [{action:'set_timer',value:'60'},{seconds:30,executionStatus:'request_accepted'}],
    [{action:'navigate_to',value:'Arlington'},{destination:'Elsewhere',executionStatus:'request_accepted'}],
    [{action:'open_app',value:'Maps'},{app:'Maps'}],
    [{action:'make_call',value:'Mike'},{number:'1234',requestAccepted:true}],
    [{action:'get_battery'},[]]
  ];
  for(const [proposal,data] of cases){
    const {host,requests,reply}=device();const pending=executeProposal(proposal,host);await tick();reply(requests.find(p=>p.action),data);
    assert.match(await pending,/Completion is unknown/);
  }
});

test('native errors override successful-looking data and accepted external actions do not claim completion',async()=>{
  const failed=device();const pending=executeProposal({action:'get_battery'},failed.host);await tick();failed.reply(failed.requests.find(p=>p.action),{level:100,charging:true},{ok:false,error:'permission_required'});
  assert.match(await pending,/could not complete.*permission required/);
  const accepted=device();const launch=executeProposal({action:'navigate_to',value:'Arlington'},accepted.host);await tick();accepted.reply(accepted.requests.find(p=>p.action),{destination:'Arlington',executionStatus:'request_accepted'});
  assert.match(await launch,/not independently verified/);
});

test('aborting while support is pending prevents action dispatch; abort after dispatch preserves actual result',async()=>{
  const first=device({automaticStatus:false}),controller=new AbortController();
  const pending=executeProposal({action:'get_battery'},first.host,100,{signal:controller.signal});
  controller.abort();first.host.ICARUS_NATIVE_STATUS({requestId:first.requests[0].requestId,actionProtocolVersion:1});
  assert.match(await pending,/Cancelled before/);assert.equal(first.requests.filter(p=>p.action).length,0);
  const second=device(),controller2=new AbortController();
  const dispatched=executeProposal({action:'get_battery'},second.host,100,{signal:controller2.signal});await tick();controller2.abort();
  second.reply(second.requests.find(p=>p.action),{level:15,charging:true});
  assert.match(await dispatched,/Battery: 15%/);
});

test('a replaced bridge cannot inherit an in-flight support check',async()=>{
  const {host,requests}=device({automaticStatus:false});const pending=executeProposal({action:'get_battery'},host);
  let dispatched=false;host.ICARUS_NATIVE_CHANNEL={postMessage(){dispatched=true;}};
  host.ICARUS_NATIVE_STATUS({requestId:requests[0].requestId,actionProtocolVersion:1});
  assert.match(await pending,/Nothing was sent/);assert.equal(dispatched,false);
});

test('a newly injected canonical bridge gets its own status check and supersedes legacy support',async()=>{
  const {host,requests}=device({automaticStatus:false}),canonicalRequests=[];
  host.IcarusNative=host.ICARUS_NATIVE_CHANNEL;delete host.ICARUS_NATIVE_CHANNEL;
  const legacyAction=executeProposal({action:'get_battery'},host);
  assert.equal(requests.length,1);
  host.ICARUS_NATIVE_CHANNEL={postMessage:raw=>canonicalRequests.push(JSON.parse(raw))};
  const canonicalStatus=readDeviceStatus(host,100);
  assert.equal(canonicalRequests.length,1);
  assert.notEqual(canonicalRequests[0].requestId,requests[0].requestId);
  host.ICARUS_NATIVE_STATUS({requestId:requests[0].requestId,actionProtocolVersion:1});
  assert.match(await legacyAction,/Nothing was sent/);
  const sharedCanonicalStatus=readDeviceStatus(host,100);
  assert.equal(canonicalRequests.length,1);
  host.ICARUS_NATIVE_STATUS({requestId:canonicalRequests[0].requestId,actionProtocolVersion:1,connected:true});
  assert.equal((await canonicalStatus).connected,true);
  assert.deepEqual(await sharedCanonicalStatus,await canonicalStatus);
  assert.equal([...requests,...canonicalRequests].filter(p=>p.action).length,0);
  assert.equal(host.ICARUS_NATIVE_STATUS,undefined);
});

test('invalid proposals fail before bridge access and caller mutation cannot alter the reviewed target',async()=>{
  for(const proposal of [null,[],{action:'open_app',value:{}},{action:'set_volume',value:50},{action:'get_battery',value:'extra'},{action:'navigate_to',value:'x'.repeat(251)},{action:'make_call',value:'Mike\nAlso call Jane'}])assert.throws(()=>actionRequest(proposal));
  const {host,requests,reply}=device({automaticStatus:false});const proposal={action:'navigate_to',value:'Arlington'};
  const pending=executeProposal(proposal,host);proposal.value='Seattle';proposal.action='make_call';
  host.ICARUS_NATIVE_STATUS({requestId:requests[0].requestId,actionProtocolVersion:1});await tick();
  const sent=requests.find(p=>p.action);assert.equal(sent.arguments.destination,'Arlington');
  reply(sent,{destination:'Arlington',executionStatus:'request_accepted'});assert.match(await pending,/accepted/);
});
