import {createHash,createHmac,randomBytes,timingSafeEqual} from 'node:crypto';
import {hashPassword,verifyPassword} from './auth.js';
import {generateRecoveryCode,normalizeRecoveryCode} from './recovery-words.js';
const digest=value=>createHash('sha256').update(value).digest('hex');
const GENERIC_MESSAGE='If this account is eligible, a recovery code will be sent to its registered email.';
const error=(message,status=400)=>Object.assign(new Error(message),{status});
const validPassword=p=>typeof p==='string'&&p.length>=10&&p.length<=1024;
const TTL=5*60_000,WINDOW=15*60_000;
const emailOf=value=>typeof value==='string'?value.trim().toLowerCase().slice(0,320):'';
const validEmail=email=>/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
const validId=id=>typeof id==='string'&&/^[a-f0-9]{32}$/.test(id);
const equal=(a,b)=>{const x=Buffer.from(a||'','hex'),y=Buffer.from(b||'','hex');return x.length===32&&y.length===32&&timingSafeEqual(x,y)};

export function createRecovery({store,env,privateTest,fetchImpl,authenticated,secret,now=Date.now}) {
  let origin='';
  try { const url=new URL(env.ICARUS_RECOVERY_ORIGIN); if(url.origin===env.ICARUS_RECOVERY_ORIGIN&&url.protocol==='https:')origin=url.origin; } catch {}
  const sender=String(env.ICARUS_RECOVERY_FROM||'');
  const key=secret||env.ICARUS_SESSION_SECRET||(env.NODE_ENV==='test'?'test-secret-at-least-32-characters':'');
  const enabled=!privateTest&&!!origin&&key.length>=32&&!!env.RESEND_API_KEY&&/^[^\s<>@]+@[^\s<>@]+\.[^\s<>@]+$/.test(sender);
  const legacyAllowed=env.ICARUS_RECOVERY_ALLOW_LEGACY_EMAIL==='true';
  const codeHash=(user,challenge,code)=>createHmac('sha256',key).update(JSON.stringify(['icarus-recovery-v2',user.id,challenge.id,challenge.purpose,user.email,code])).digest('hex');
  const limits=new Map();
  function limited(key,max,window=WINDOW) {
    const time=now();for(const[k,v]of limits)if(v.until<=time)limits.delete(k);
    let value=limits.get(key);if(!value){if(limits.size>=10000)return true;value={count:0,until:time+window};limits.set(key,value);}
    return ++value.count>max;
  }
  function entries(data){return data.recoveryRateLimits=(data.recoveryRateLimits||[]).filter(v=>v.until>now());}
  function storedLimit(data,key,max,window=WINDOW){
    const values=entries(data);let entry=values.find(v=>v.key===key);
    if(!entry){if(values.length>=10000)return true;entry={key,count:0,until:now()+window};values.push(entry);}
    entry.count=Math.min(entry.count+1,max+1);return entry.count>max;
  }
  function exhausted(data,key,max){const values=entries(data),entry=values.find(v=>v.key===key);return entry?entry.count>=max:values.length>=10000;}
  function mailBudget(data){data.recoveryMailAttempts=(data.recoveryMailAttempts||[]).filter(t=>t>now()-864e5);if(data.recoveryMailAttempts.length>=80)return false;data.recoveryMailAttempts.push(now());return true;}
  function verified(user){return Boolean(user&&user.recoveryEmailVerifiedFor===user.email&&typeof user.recoveryEmailVerifiedAt==='string'&&Number.isFinite(Date.parse(user.recoveryEmailVerifiedAt)));}
  function eligible(user){return user&&!user.privateTester&&(verified(user)||legacyAllowed);}
  async function mail(to,subject,text){
    const response=await fetchImpl('https://api.resend.com/emails',{method:'POST',signal:AbortSignal.timeout(10_000),headers:{Authorization:`Bearer ${env.RESEND_API_KEY}`,'Content-Type':'application/json'},body:JSON.stringify({from:sender,to:[to],subject,text})});
    if(!response.ok)throw Error('delivery_failed');
  }
  function dispatch(delivery){
    if(!delivery)return;
    Promise.resolve().then(()=>mail(delivery.email,delivery.subject,delivery.text)).catch(()=>console.warn('ICARUS recovery delivery failed'));
  }
  const failureKey=email=>`code-fail:${digest(email)}`;
  function issue(data,user,purpose,id){
    const email=user.email;
    if(storedLimit(data,`email:${digest(email)}`,3)||exhausted(data,failureKey(email),5)||(user.recoveryLastSentAt!=null&&now()-user.recoveryLastSentAt<60_000)||!mailBudget(data))return null;
    user.recoveryLastSentAt=now();
    const code=generateRecoveryCode();const challenge={id,purpose,email,sessionVersion:user.sessionVersion??0,expiresAt:now()+TTL};
    challenge.hash=codeHash(user,challenge,code);user.recoveryChallenge=challenge;delete user.recoveryGrant;delete user.passwordReset;
    return {email,subject:purpose==='enroll'?'Verify your ICARUS recovery email':'Your ICARUS recovery code',text:`Your ICARUS ${purpose==='enroll'?'email verification':'password recovery'} code is:\n\n${code}\n\n${purpose==='enroll'?'Enter these two words in the ICARUS Settings email-verification form.':`Return to the ICARUS recovery page you already opened on ${origin} and enter these two words there. If you closed or refreshed that page, request a new code first.`}\nThe code expires in 5 minutes and can be used only once. Never share it with anyone, including support. If you did not request this, ignore this email. Your password has not changed.`};
  }
  function check(data,user,email,id,raw,purpose){
    // Budgets belong to the account's stable destination, not a resend/challenge.
    // Failures commit even if no matching challenge/account exists.
    const budget=failureKey(email),blocked=exhausted(data,budget,5),code=normalizeRecoveryCode(raw),c=user?.recoveryChallenge;
    const ok=!blocked&&code&&validId(id)&&c&&c.id===id&&c.purpose===purpose&&c.email===email&&c.expiresAt>now()&&c.sessionVersion===(user.sessionVersion??0)&&equal(c.hash,codeHash(user,c,code));
    if(!ok){storedLimit(data,budget,5);return false;}
    delete user.recoveryChallenge;return true;
  }
  async function handle(req,res,path,{json,body}){
    const paths=['/api/auth/forgot-password','/api/auth/verify-recovery-code','/api/auth/reset-password','/api/auth/change-password','/api/auth/recovery-email','/api/auth/verify-recovery-email'];
    if(!paths.includes(path))return false;
    const enrollment=path.endsWith('/recovery-email')||path.endsWith('/verify-recovery-email');
    const change=path.endsWith('/change-password');
    if(privateTest||(change&&!origin)||(!change&&!enabled)){json(res,503,{error:'recovery_unavailable'});return true;}
    if(req.method==='GET'&&path==='/api/auth/recovery-email'){
      const user=await authenticated(req);if(!user){json(res,401,{error:'authentication_required'});return true;}
      json(res,200,{verified:verified(user),email:user.email});return true;
    }
    if(req.method!=='POST'){json(res,405,{error:'method_not_allowed'});return true;}
    if(req.headers.origin&&req.headers.origin!==origin){json(res,403,{error:'origin_not_allowed'});return true;}
    if(limited(`ip:${digest(String(req.socket?.remoteAddress||'unknown'))}`,40)){json(res,429,{error:'try_again_later'});return true;}
    const input=await body(req);if(!input||typeof input!=='object'||Array.isArray(input))throw error('invalid_request');
    if(path.endsWith('/forgot-password')){
      const email=emailOf(input.email),id=randomBytes(16).toString('hex');let delivery;
      if(validEmail(email)&&!limited(`email:${digest(email)}`,3))await store.update(data=>{
        const user=data.users.find(u=>u.email===email);if(eligible(user))delivery=issue(data,user,'reset',id);
        else storedLimit(data,`email:${digest(email)}`,3);
      });
      dispatch(delivery);json(res,200,{ok:true,message:GENERIC_MESSAGE,challengeId:id});return true;
    }
    if(enrollment){
      const account=await authenticated(req);if(!account){json(res,401,{error:'authentication_required'});return true;}
      if(path.endsWith('/verify-recovery-email')){
        const accepted=await store.update(data=>{const user=data.users.find(u=>u.id===account.id);if(!user||(user.sessionVersion??0)!==(account.sessionVersion??0))return false;
          if(!check(data,user,user.email,input.challengeId,input.code,'enroll'))return false;
          user.recoveryEmailVerifiedAt=new Date(now()).toISOString();user.recoveryEmailVerifiedFor=user.email;return true;});
        if(!accepted)throw error('invalid_or_expired_code');json(res,200,{ok:true,message:'Recovery email verified.'});return true;
      }
      const id=randomBytes(16).toString('hex');let delivery;
      const refusal=await store.update(data=>{const user=data.users.find(u=>u.id===account.id);
        if(storedLimit(data,`enroll:${account.id}`,5))return 'try_again_later';
        if(!user||(user.sessionVersion??0)!==(account.sessionVersion??0)||typeof input.currentPassword!=='string'||input.currentPassword.length>1024||!verifyPassword(input.currentPassword,user.passwordHash))return 'invalid_current_password';
        delivery=issue(data,user,'enroll',id);return delivery?null:'try_again_later';});
      if(refusal)throw error(refusal,refusal==='try_again_later'?429:401);dispatch(delivery);json(res,200,{ok:true,challengeId:id,email:account.email});return true;
    }
    if(path.endsWith('/verify-recovery-code')){
      const email=emailOf(input.email);let resetToken;
      const accepted=await store.update(data=>{const user=data.users.find(u=>u.email===email);
        if(!eligible(user)||!check(data,user,email,input.challengeId,input.code,'reset')){if(!eligible(user))storedLimit(data,failureKey(email),5);return false;}
        resetToken=randomBytes(32).toString('hex');user.recoveryGrant={hash:digest(resetToken),expiresAt:now()+TTL,sessionVersion:user.sessionVersion??0,email:user.email};return true;});
      if(!accepted)throw error('invalid_or_expired_code');json(res,200,{ok:true,resetToken});return true;
    }
    if(!validPassword(input.password))throw error('password_must_be_10_to_1024_characters');
    if(change){
      const account=await authenticated(req);if(!account){json(res,401,{error:'authentication_required'});return true;}
      if(limited(`change:${account.id}`,5)){json(res,429,{error:'try_again_later'});return true;}
      if(typeof input.currentPassword!=='string'||input.currentPassword.length>1024)throw error('invalid_current_password',401);
      const refusal=await store.update(data=>{if(storedLimit(data,`change:${account.id}`,5))return 'try_again_later';
        const user=data.users.find(u=>u.id===account.id);if(!user||(user.sessionVersion??0)!==(account.sessionVersion??0)||!verifyPassword(input.currentPassword,user.passwordHash))return 'invalid_current_password';
        user.passwordHash=hashPassword(input.password);user.sessionVersion=(user.sessionVersion??0)+1;delete user.passwordReset;delete user.recoveryChallenge;delete user.recoveryGrant;});
      if(refusal)throw error(refusal,refusal==='try_again_later'?429:401);
    }else{
      if(typeof input.resetToken!=='string'||!/^[a-f0-9]{64}$/.test(input.resetToken))throw error('invalid_or_expired_reset_session');
      const hash=digest(input.resetToken);let notice;
      const accepted=await store.update(data=>{const user=data.users.find(u=>!u.privateTester&&u.recoveryGrant?.hash===hash&&u.recoveryGrant.expiresAt>now()&&u.recoveryGrant.sessionVersion===(u.sessionVersion??0)&&u.recoveryGrant.email===u.email);
        if(!eligible(user))return false;
        user.passwordHash=hashPassword(input.password);user.sessionVersion=(user.sessionVersion??0)+1;
        user.recoveryEmailVerifiedAt=new Date(now()).toISOString();user.recoveryEmailVerifiedFor=user.email;delete user.passwordReset;delete user.recoveryChallenge;delete user.recoveryGrant;
        if(mailBudget(data))notice={email:user.email,subject:'Your ICARUS password changed',text:'Your ICARUS password was changed and previous sessions were signed out. If you did not make this change, seek account help immediately. No password or recovery code is included in this message.'};return true;});
      if(!accepted)throw error('invalid_or_expired_reset_session');dispatch(notice);
    }
    json(res,200,{ok:true,message:'Password updated. Sign in again on each device.'});return true;
  }
  return {enabled,changeEnabled:!privateTest&&!!origin,handle};
}
