import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {createBillingHandler} from './billing-app.js';
import {emptyStore} from './store.js';
class MemoryStore {constructor(){this.data=emptyStore();}async read(){return structuredClone(this.data);}async update(fn){return fn(this.data);}}
async function withServer(fn){const store=new MemoryStore(),env={NODE_ENV:'test',ICARUS_PREMIUM_GRANT_EMAILS:'owner@example.com'};const server=createServer(createBillingHandler({store,env}));await new Promise(r=>server.listen(0,'127.0.0.1',r));try{await fn(`http://127.0.0.1:${server.address().port}`,store,env);}finally{await new Promise(r=>server.close(r));}}
const post=(url,value,token)=>fetch(url,{method:'POST',headers:{'content-type':'application/json',...(token?{authorization:`Bearer ${token}`}:{})},body:JSON.stringify(value)});
test('billing endpoints require authenticated account and fail closed when not configured',()=>withServer(async base=>{
 assert.equal((await fetch(`${base}/api/billing/config`)).status,401);
 const account=await(await post(`${base}/api/auth/register`,{email:'owner@example.com',password:'long-enough-password',premium:true})).json();
 const headers={authorization:`Bearer ${account.token}`};
 assert.equal((await(await fetch(`${base}/api/me`,{headers})).json()).user.entitlement.premium,false);
 const c=await(await fetch(`${base}/api/billing/config`,{headers})).json();assert.equal(c.checkoutEnabled,false);assert.equal(c.plans.length,3);
 assert.equal((await post(`${base}/api/billing/verify`,{purchaseToken:'purchase-token-1',premium:true},account.token)).status,503);
 assert.equal((await post(`${base}/api/entitlement`,{premium:true},account.token)).status,405);
}));
test('account deletion removes billing identity and encrypted tokens',()=>withServer(async(base,store)=>{
 const account=await(await post(`${base}/api/auth/register`,{email:'delete@example.com',password:'long-enough-password'})).json();
 store.data.playPurchases=[{id:'hash-only',userId:account.user.id,token:'sealed-value',productId:'icarus_pro_monthly'}];
 const response=await fetch(`${base}/api/account`,{method:'DELETE',headers:{authorization:`Bearer ${account.token}`}});assert.equal(response.status,200);
 assert.deepEqual(store.data.playPurchases,[{id:'hash-only',deleted:true,expiresAt:0}]);
 assert.equal((await fetch(`${base}/api/entitlement`,{headers:{authorization:`Bearer ${account.token}`}})).status,401);
}));
test('configured Premium features enforce server checks and developer grants bypass payment',()=>withServer(async(base,store,env)=>{
 const a=await(await post(`${base}/api/auth/register`,{email:'normal@example.com',password:'long-enough-password'})).json();
 env.ICARUS_PREMIUM_FEATURES='cloud-chat';
 assert.equal((await post(`${base}/api/chat`,{message:'test',premium:true},a.token)).status,403);
 env.ICARUS_PREMIUM_GRANT_USER_IDS=a.user.id;
 assert.equal((await post(`${base}/api/chat`,{message:'test'},a.token)).status,200);
}));
