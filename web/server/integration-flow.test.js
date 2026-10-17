import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {createHandler} from './app.js';
import {issueToken} from './auth.js';
import {emptyStore} from './store.js';

test('authenticated HTTP flow connects, reads, isolates users, and disconnects without exposing credentials',async()=>{
  const store={data:{...emptyStore(),users:[{id:'alice'},{id:'bob'}]},async read(){return structuredClone(this.data);},async update(fn){return fn(this.data);}};
  const env={NODE_ENV:'test',ICARUS_SESSION_SECRET:'test-session-secret-at-least-32-characters',ICARUS_INTEGRATION_KEY:Buffer.alloc(32,8).toString('base64'),ICARUS_GOOGLE_CLIENT_ID:'test-client',ICARUS_GOOGLE_CLIENT_SECRET:'test-client-secret',ICARUS_OAUTH_REDIRECT_URI:'https://icarus.example/'};
  const outbound=[];const fetchImpl=async(url,options)=>{outbound.push({url,options});let data;if(url==='https://oauth2.googleapis.com/token')data={access_token:'provider-access-secret',refresh_token:'provider-refresh-secret',expires_in:3600,token_type:'Bearer',scope:'https://www.googleapis.com/auth/gmail.readonly'};else if(new URL(url).pathname.endsWith('/messages'))data={messages:[{id:'m1'}]};else data={id:'m1',snippet:'A read-only preview',payload:{headers:[{name:'Subject',value:'Test result'}]}};return new Response(JSON.stringify(data));};
  const server=createServer(createHandler({store,env,fetchImpl}));await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const call=async(path,method='GET',data,user='alice')=>{const r=await fetch(`http://127.0.0.1:${server.address().port}/api/integrations${path}`,{method,headers:{authorization:`Bearer ${issueToken(user,env.ICARUS_SESSION_SECRET)}`,'content-type':'application/json'},...(data?{body:JSON.stringify(data)}:{})});const body=await r.json();assert(!JSON.stringify(body).includes('provider-access-secret'));assert(!JSON.stringify(body).includes('provider-refresh-secret'));assert(!JSON.stringify(body).includes('test-client-secret'));return {status:r.status,body};};
  try{
    const pending=await call('/gmail/connect','POST',{});assert.equal(pending.status,200);const state=new URL(pending.body.url).searchParams.get('state');
    assert.equal((await call('/gmail/callback','POST',{code:'synthetic-code',state},'bob')).status,400);assert.equal(outbound.length,0);
    const connected=await call('/gmail/callback','POST',{code:'synthetic-code',state});assert.equal(connected.status,200);assert.equal(connected.body.connected,true);
    assert.equal((await call('/gmail/callback','POST',{code:'synthetic-code',state})).status,400);assert.equal(outbound.length,1);
    const prior=(await call('')).body.integrations.find(x=>x.id==='gmail');assert.equal(prior.connected,true);assert.equal(prior.liveVerified,false);
    assert.equal((await call('/gmail/query','POST',{query:'test'},'bob')).status,409);
    const read=await call('/gmail/query','POST',{query:'test'});assert.equal(read.status,200);assert.equal(read.body.results[0].title,'Test result');assert.equal(read.body.liveVerified,true);
    assert((await call('')).body.integrations.find(x=>x.id==='gmail').liveVerified);
    assert(!JSON.stringify(store.data).includes('provider-access-secret'));assert(!JSON.stringify(store.data).includes('provider-refresh-secret'));
    assert.deepEqual((await call('/gmail','DELETE')).body,{disconnected:true});assert.equal((await call('/gmail/query','POST',{query:'test'})).status,409);
    const after=(await call('')).body.integrations.find(x=>x.id==='gmail');assert.equal(after.connected,false);assert.equal(after.liveVerified,false);
    assert.equal(outbound.length,3);assert(outbound.slice(1).every(x=>x.options.method==='GET'));
  }finally{await new Promise(r=>server.close(r));}
});
