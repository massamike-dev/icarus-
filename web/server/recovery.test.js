import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {JsonStore} from './store.js';
import {createRecovery} from './recovery.js';
import {hashPassword,verifyPassword} from './auth.js';
import {RECOVERY_WORDS,generateRecoveryCode,normalizeRecoveryCode} from './recovery-words.js';
const env={NODE_ENV:'test',ICARUS_SESSION_SECRET:'test-session-secret-at-least-32-characters',ICARUS_RECOVERY_ORIGIN:'https://icarus.example',ICARUS_RECOVERY_FROM:'recovery@accounts.icarus.example',RESEND_API_KEY:'test-not-real'};
const email='person@example.com';
async function fixture(fn,overrides={}){
 const dir=await mkdtemp(join(tmpdir(),'icarus-code-test-')),store=new JsonStore(join(dir,'store.json')),mail=[];
 let time=Date.now(),auth=null;
 await store.update(d=>{d.users.push({id:'u',email,passwordHash:hashPassword('old-password-123'),recoveryEmailVerifiedFor:email,recoveryEmailVerifiedAt:new Date(time).toISOString()});d.memories.push({userId:'u',content:'preserve'});});
 const call=async(route,input={},options={})=>{
  // New handler every request proves durable budgets and no process-memory reliance.
  const recovery=createRecovery({store,env:{...env,...overrides},privateTest:options.privateTest??null,now:()=>time,authenticated:async()=>auth,fetchImpl:async(_url,opts)=>{mail.push(JSON.parse(opts.body));return {ok:true};}});
  let result;try{await recovery.handle({method:options.method||'POST',headers:options.origin?{origin:options.origin}:{},socket:{remoteAddress:options.ip||'127.0.0.1'}},{},`/api/auth/${route}`,{json:(_res,status,data)=>result={status,data},body:async()=>input});}catch(e){result={status:e.status||500,data:{error:e.message}};}
  await new Promise(r=>setImmediate(r));return result;
 };
 const code=()=>mail.filter(m=>m.subject.includes('code')||m.subject.includes('Verify')).at(-1)?.text.split('\n')[2];
 const issue=async()=>{const r=await call('forgot-password',{email});return {challengeId:r.data.challengeId,code:code()};};
 try{await fn({store,mail,call,code,issue,advance:ms=>time+=ms,auth:async()=>auth=structuredClone((await store.read()).users[0])});}finally{await rm(dir,{recursive:true,force:true});}
}

test('vocabulary has 22-bit pair space and strict canonical normalization',()=>{
 assert.equal(RECOVERY_WORDS.length,2048);assert.equal(new Set(RECOVERY_WORDS).size,2048);assert.equal(2048**2,4194304);
 assert.equal(new Set(RECOVERY_WORDS.map(w=>w.slice(0,4))).size,2048);
 for(const word of RECOVERY_WORDS){assert.match(word,/^[a-z]{5,8}$/);assert.ok(!['ntavatz','ches','styt','tsats','pidix','kayl','mpini','gyft','gkiaoy','lagne','viast','ptom'].some(fragment=>word.includes(fragment)));}
 for(const word of RECOVERY_WORDS)assert.equal(normalizeRecoveryCode(` ${word.toUpperCase()} - ${word} `),`${word}-${word}`);
 for(const value of [null,{},'alpha', 'αlpha-beta','alpha-beta-gamma','<script>','x'.repeat(65)])assert.equal(normalizeRecoveryCode(value),null);
 for(let i=0;i<1000;i++){const c=generateRecoveryCode();assert.equal(normalizeRecoveryCode(c),c);}
});
test('two-word code exchanged for reset-only grant then consumed, sessions revoked and data preserved',()=>fixture(async({store,call,issue})=>{
 const c=await issue(),d=await store.read();assert.ok(!JSON.stringify(d).includes(c.code));assert.notEqual(d.users[0].recoveryChallenge.hash,createHash('sha256').update(c.code).digest('hex'));
 const verified=await call('verify-recovery-code',{email,...c});assert.equal(verified.status,200);const resetToken=verified.data.resetToken;assert.match(resetToken,/^[a-f0-9]{64}$/);assert.ok(!JSON.stringify(await store.read()).includes(resetToken));
 assert.equal((await call('reset-password',{resetToken,password:'new-password-123'})).status,200);
 const after=await store.read();assert.equal(after.users[0].sessionVersion,1);assert.ok(verifyPassword('new-password-123',after.users[0].passwordHash));assert.equal(after.memories[0].content,'preserve');assert.equal(after.users[0].recoveryGrant,undefined);
 assert.equal((await call('reset-password',{resetToken,password:'another-password'})).status,400);
 assert.equal((await call('verify-recovery-code',{email,...c})).status,400);
}));
test('uniform response shape for known, missing, invalid, disabled legacy and throttled destination',()=>fixture(async({store,call,mail})=>{
 const replies=[];for(const target of [email,'missing@example.com','invalid',email,email,email])replies.push(await call('forgot-password',{email:target}));
 for(const r of replies){assert.equal(r.status,200);assert.equal(r.data.ok,true);assert.match(r.data.challengeId,/^[a-f0-9]{32}$/);assert.equal(r.data.message,replies[0].data.message);}
 assert.equal(new Set(replies.map(r=>r.data.challengeId)).size,replies.length);assert.equal(mail.length,1);
 await store.update(d=>{delete d.users[0].recoveryEmailVerifiedAt;});assert.equal((await call('forgot-password',{email})).data.message,replies[0].data.message);
}));
test('resend cooldown and durable 3-request limit; newer challenge invalidates previous',()=>fixture(async({call,issue,mail,advance})=>{
 const old=await issue();await call('forgot-password',{email});assert.equal(mail.length,1);advance(60001);const fresh=await issue();assert.equal(mail.length,2);
 assert.equal((await call('verify-recovery-code',{email,...old})).status,400);
 assert.equal((await call('verify-recovery-code',{email,...fresh})).status,200);
 advance(60001);await call('forgot-password',{email});assert.equal(mail.length,2);
}));
test('five failed code checks exhaust durable account budget across new handlers and resend',()=>fixture(async({call,issue,advance,mail})=>{
 const c=await issue();for(let i=0;i<5;i++)assert.equal((await call('verify-recovery-code',{email,challengeId:c.challengeId,code:'wrong-words'},{ip:`10.0.0.${i}`})).status,400);
 advance(60001);await call('forgot-password',{email});assert.equal(mail.length,1);assert.equal((await call('verify-recovery-code',{email,...c})).status,400);
 advance(15*60000);const fresh=await issue();assert.equal((await call('verify-recovery-code',{email,...fresh})).status,200);
}));
test('code expiration, binding to account/id/session version, and grant expiration',()=>fixture(async({call,issue,store,advance})=>{
 let c=await issue();assert.equal((await call('verify-recovery-code',{email:'other@example.com',...c})).status,400);assert.equal((await call('verify-recovery-code',{email,...c,challengeId:'0'.repeat(32)})).status,400);
 advance(5*60000+1);assert.equal((await call('verify-recovery-code',{email,...c})).status,400);
 c=await issue();await store.update(d=>d.users[0].sessionVersion=1);assert.equal((await call('verify-recovery-code',{email,...c})).status,400);
 advance(15*60000);c=await issue();const r=await call('verify-recovery-code',{email,...c});assert.equal(r.status,200);advance(5*60000+1);assert.equal((await call('reset-password',{resetToken:r.data.resetToken,password:'new-password-123'})).status,400);
}));
test('parallel code checks and password writes each permit exactly one success',()=>fixture(async({call,issue})=>{
 const c=await issue();const results=await Promise.all([call('verify-recovery-code',{email,...c}),call('verify-recovery-code',{email,...c})]);assert.deepEqual(results.map(r=>r.status).sort(),[200,400]);
 const resetToken=results.find(r=>r.status===200).data.resetToken;const resets=await Promise.all([call('reset-password',{resetToken,password:'new-password-123'}),call('reset-password',{resetToken,password:'other-password-123'})]);assert.deepEqual(resets.map(r=>r.status).sort(),[200,400]);
}));
test('default legacy ineligible; current-password and email possession enroll existing destination',()=>fixture(async({store,call,auth,code,mail,advance})=>{
 await store.update(d=>{delete d.users[0].recoveryEmailVerifiedAt;delete d.users[0].recoveryEmailVerifiedFor;});
 await call('forgot-password',{email});assert.equal(mail.length,0);assert.equal((await call('recovery-email',{currentPassword:'old-password-123'})).status,401);await auth();
 assert.equal((await call('recovery-email',{}, {method:'GET'})).data.verified,false);
 assert.equal((await call('recovery-email',{currentPassword:'wrong'})).status,401);
 const r=await call('recovery-email',{currentPassword:'old-password-123',email:'attacker@example.com'});assert.equal(r.status,200);assert.equal(mail[0].to[0],email);
 assert.equal((await call('verify-recovery-code',{email,challengeId:r.data.challengeId,code:code()})).status,400);
 assert.equal((await call('verify-recovery-email',{challengeId:r.data.challengeId,code:code()})).status,200);
 const u=(await store.read()).users[0];assert.equal(u.recoveryEmailVerifiedFor,email);assert.ok(u.recoveryEmailVerifiedAt);assert.equal(u.sessionVersion,undefined);
 advance(15*60000);await call('forgot-password',{email});assert.equal(mail.length,2);
}));
test('explicit legacy policy allows possession proof but never pre-verifies all historical emails',()=>fixture(async({store,call,issue})=>{
 await store.update(d=>{delete d.users[0].recoveryEmailVerifiedAt;delete d.users[0].recoveryEmailVerifiedFor;});const c=await issue();assert.equal((await store.read()).users[0].recoveryEmailVerifiedAt,undefined);
 const r=await call('verify-recovery-code',{email,...c});assert.equal(r.status,200);assert.equal((await store.read()).users[0].recoveryEmailVerifiedAt,undefined);
 assert.equal((await call('reset-password',{resetToken:r.data.resetToken,password:'new-password-123'})).status,200);assert.equal((await store.read()).users[0].recoveryEmailVerifiedFor,email);
},{ICARUS_RECOVERY_ALLOW_LEGACY_EMAIL:'true'}));
test('new request revokes outstanding grant; email/password changes invalidate all recovery state',()=>fixture(async({call,issue,advance,auth,store})=>{
 let c=await issue();let r=await call('verify-recovery-code',{email,...c});advance(60001);c=await issue();assert.equal((await call('reset-password',{resetToken:r.data.resetToken,password:'new-password-123'})).status,400);
 await auth();assert.equal((await call('change-password',{currentPassword:'old-password-123',password:'new-password-123'})).status,200);assert.equal((await call('verify-recovery-code',{email,...c})).status,400);
 const user=(await store.read()).users[0];assert.equal(user.recoveryChallenge,undefined);assert.equal(user.recoveryGrant,undefined);
}));
test('old link token cannot bypass code verification and invalid password does not consume grant',()=>fixture(async({call,issue})=>{
 assert.equal((await call('reset-password',{token:'a'.repeat(64),password:'new-password-123'})).status,400);
 const c=await issue(),r=await call('verify-recovery-code',{email,...c}),resetToken=r.data.resetToken;
 for(const password of ['short',null,'x'.repeat(1025)])assert.equal((await call('reset-password',{resetToken,password})).status,400);
 assert.equal((await call('reset-password',{resetToken,password:'new-password-123'})).status,200);
}));
test('global actual mail attempts budget survives fresh handlers',()=>fixture(async({store,call,mail})=>{
 await store.update(d=>d.recoveryMailAttempts=Array(80).fill(Date.now()));await call('forgot-password',{email});assert.equal(mail.length,0);
}));
test('unconfigured/foreign origin/private mode fail closed',()=>fixture(async({call})=>{
 assert.equal((await call('forgot-password',{email},{origin:'https://evil.example'})).status,403);
 for(const route of ['forgot-password','verify-recovery-code','reset-password','change-password','recovery-email','verify-recovery-email'])assert.equal((await call(route,{},{privateTest:{enabled:true}})).status,503);
}));
for(const overrides of [{ICARUS_RECOVERY_ORIGIN:''},{RESEND_API_KEY:''},{NODE_ENV:'production',ICARUS_SESSION_SECRET:''}])test('missing required configuration disables emailed recovery',()=>fixture(async({call})=>assert.equal((await call('forgot-password',{email})).status,503),overrides));

test('case and whitespace normalization preserves full code entropy; fourth failure still allows correct code',()=>fixture(async({call,issue})=>{
 const c=await issue();for(let i=0;i<4;i++)assert.equal((await call('verify-recovery-code',{email,...c,code:'wrong-words'})).status,400);
 assert.equal((await call('verify-recovery-code',{email,...c,code:c.code.toUpperCase().replace('-', '   ')})).status,200);
}));
test('stale email verification marker cannot enable a different stored address',()=>fixture(async({call,store,mail})=>{
 await store.update(d=>d.users[0].email='other@example.com');await call('forgot-password',{email:'other@example.com'});assert.equal(mail.length,0);
}));
