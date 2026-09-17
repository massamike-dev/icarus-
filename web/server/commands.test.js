import test from 'node:test';
import assert from 'node:assert/strict';
import {validateCommand,interpretCommand} from './commands.js';
import {createServer} from 'node:http';
import {createHandler} from './app.js';
import {emptyStore} from './store.js';
test('model cannot invent an executable capability',()=>{
  for(const action of ['send_sms','shell','delete_files','subscribe']) assert.throws(()=>validateCommand({action}));
});
test('commands reject dangerous ranges and missing targets',()=>{
  for(const c of [{action:'set_volume',value:'101'},{action:'set_timer',value:'-1'},{action:'set_timer',value:'86401'},{action:'make_call',value:''},{action:'toggle_flashlight',value:'maybe'}]) assert.throws(()=>validateCommand(c));
});
test('interpretation is never reported as execution or trusted confirmation',()=>{
  assert.deepEqual(validateCommand({action:'make_call',value:'Trisha',confirmed:true,executionStatus:'success'}),{action:'make_call',value:'Trisha',reply:undefined,executionStatus:'not_executed'});
});
test('no provider returns an honest unsupported result',async()=>assert.equal((await interpretCommand('email my boss',{},()=>{throw Error('must not fetch')})).action,'unknown'));
test('command endpoint requires a valid account and rejects empty commands',async()=>{
  let data=emptyStore();const store={read:async()=>structuredClone(data),update:async fn=>fn(data)};
  const server=createServer(createHandler({store,env:{NODE_ENV:'test'}}));
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const base=`http://127.0.0.1:${server.address().port}`;
  const post=(path,body,token)=>fetch(base+path,{method:'POST',headers:{'content-type':'application/json',...(token?{authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body)});
  try {
    assert.equal((await post('/api/commands/interpret',{command:'call Trisha'})).status,401);
    const {token}=await(await post('/api/auth/register',{email:'voice@example.com',password:'voice-test-password'})).json();
    assert.equal((await post('/api/commands/interpret',{command:''},token)).status,400);
    const result=await(await post('/api/commands/interpret',{command:'call Trisha'},token)).json();
    assert.equal(result.executionStatus,'not_executed');assert.equal(result.action,'unknown');
  }finally{await new Promise(r=>server.close(r))}
});
