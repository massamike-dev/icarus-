import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {createHandler} from './app.js';
import {emptyStore} from './store.js';

async function fixture(run) {
  const store={data:emptyStore(),async read(){return structuredClone(this.data);},async update(fn){return fn(this.data);}};
  const server=createServer(createHandler({store,env:{NODE_ENV:'test'},fetchImpl:async()=>{throw Error('Unexpected external request');}}));
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const request=async(path,{method='GET',token,data}={})=>{
    const response=await fetch(`http://127.0.0.1:${server.address().port}/api${path}`,{method,headers:{'content-type':'application/json',...(token?{authorization:`Bearer ${token}`}:{})},...(data?{body:JSON.stringify(data)}:{})});
    return {status:response.status,body:await response.json()};
  };
  try{await run({store,request});}finally{await new Promise(resolve=>server.close(resolve));}
}
const register=async(request,email)=>(await request('/auth/register',{method:'POST',data:{email,password:'long-enough-password'}})).body;

test('integration routes require authentication and expose honest unconfigured status without secrets',()=>fixture(async({request})=>{
  for(const [path,method] of [['/integrations','GET'],['/integrations/gmail/connect','POST'],['/integrations/gmail/callback','POST'],['/integrations/gmail/query','POST'],['/integrations/gmail','DELETE']]){
    assert.equal((await request(path,{method,data:method==='POST'?{}:undefined})).status,401);
  }
  const {token}=await register(request,'integrations@example.test');
  const result=await request('/integrations',{token});
  assert.equal(result.status,200);
  assert.equal(result.body.integrations.length,10);
  for(const integration of result.body.integrations){assert.equal(integration.connected,false);assert.equal(integration.liveVerified,false);assert.equal(integration.readOnly,true);}
  assert.doesNotMatch(JSON.stringify(result.body),/access_token|refresh_token|client_secret|encryptedToken/i);
  assert.equal((await request('/integrations/arbitrary/query',{token,method:'POST',data:{query:'hello'}})).status,404);
  assert.equal((await request('/integrations/gmail/query',{token,method:'PUT',data:{query:'hello'}})).status,405);
}));

test('account deletion clears only the owning integration credentials, states and verification records',()=>fixture(async({request,store})=>{
  const a=await register(request,'owner-a@example.test'),b=await register(request,'owner-b@example.test');
  for(const field of ['integrationCredentials','integrationOAuthStates','integrationVerification'])store.data[field]=[{userId:a.user.id,integration:'gmail',marker:'a'},{userId:b.user.id,integration:'gmail',marker:'b'}];
  assert.equal((await request('/account',{token:a.token,method:'DELETE'})).status,200);
  for(const field of ['integrationCredentials','integrationOAuthStates','integrationVerification'])assert.deepEqual(store.data[field],[{userId:b.user.id,integration:'gmail',marker:'b'}]);
  assert.equal((await request('/integrations',{token:a.token})).status,401);
}));

test('capabilities report configured model and API mode without inventing a knowledge cutoff',async()=>{
  const {capabilities}=await import('./assistant.js');
  const caps=capabilities({ICARUS_AI_API_KEY:'secret-never-return',ICARUS_AI_MODEL:'chosen-model',ICARUS_AI_API_MODE:'responses'});
  assert.equal(caps.ai.model,'chosen-model');assert.equal(caps.ai.apiMode,'responses');assert.equal(caps.ai.knowledgeCutoff,null);
  assert.equal(caps.ai.configured,true);assert.doesNotMatch(JSON.stringify(caps),/secret-never-return/);
  assert.deepEqual(caps.deviceActions,[]);
});
