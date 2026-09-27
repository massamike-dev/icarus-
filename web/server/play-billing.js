import {createHash,createHmac,createPrivateKey,createPublicKey,createSign,createVerify,createCipheriv,createDecipheriv,randomBytes} from 'node:crypto';
import {entitlementFor} from './entitlements.js';

export const PLAY_PACKAGE='com.icarusalmighty.app';
export const PREMIUM_PLANS=Object.freeze([
  {productId:'icarus_pro_monthly',name:'ICARUS Ascend',period:'P1M',periodLabel:'month',usdCents:1000},
  {productId:'icarus_pro_quarterly',name:'ICARUS Zenith',period:'P3M',periodLabel:'3 months',usdCents:1742},
  {productId:'icarus_pro_annual',name:'ICARUS Crest',period:'P1Y',periodLabel:'year',usdCents:6969},
]);
const PREFIX=`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PLAY_PACKAGE}`;
const TOKEN_URL='https://oauth2.googleapis.com/token';
const GOOGLE_CERTS='https://www.googleapis.com/oauth2/v3/certs';
const fail=(code,status=503)=>Object.assign(new Error(code),{status});
const hash=value=>createHash('sha256').update(value).digest('hex');
const b64=value=>Buffer.from(JSON.stringify(value)).toString('base64url');
const validToken=value=>typeof value==='string'&&value.length>=8&&value.length<=4096&&!/[\s\x00-\x1f\x7f]/.test(value);
const known=id=>PREMIUM_PLANS.find(plan=>plan.productId===id);
const approvedStates=new Set(['SUBSCRIPTION_STATE_ACTIVE','SUBSCRIPTION_STATE_IN_GRACE_PERIOD','SUBSCRIPTION_STATE_CANCELED']);
const allStates=new Set([...approvedStates,'SUBSCRIPTION_STATE_PENDING','SUBSCRIPTION_STATE_PAUSED','SUBSCRIPTION_STATE_ON_HOLD','SUBSCRIPTION_STATE_EXPIRED','SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED']);

export function inspectSubscription(purchase,expectedBinding,now=Date.now()) {
  if(purchase?.externalAccountIdentifiers?.obfuscatedExternalAccountId!==expectedBinding)throw fail('purchase_account_mismatch',403);
  if(!allStates.has(purchase.subscriptionState))throw fail('purchase_state_unknown',409);
  if(!Array.isArray(purchase.lineItems)||purchase.lineItems.length!==1)throw fail('unsupported_subscription_items',409);
  const item=purchase.lineItems[0],plan=known(item.productId);
  if(!plan||!item.autoRenewingPlan||!item.offerDetails?.basePlanId)throw fail('unsupported_subscription_product',400);
  const expiry=Date.parse(item.expiryTime);
  if(!Number.isFinite(expiry))throw fail('invalid_subscription_expiry',409);
  const ack=purchase.acknowledgementState;
  if(!['ACKNOWLEDGEMENT_STATE_PENDING','ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED'].includes(ack))throw fail('unknown_acknowledgement_state',409);
  return {productId:plan.productId,basePlanId:item.offerDetails.basePlanId,state:purchase.subscriptionState,expiresAt:expiry,verifiedAt:now,autoRenewing:item.autoRenewingPlan.autoRenewEnabled===true,testPurchase:purchase.testPurchase!==undefined,acknowledged:ack==='ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',eligible:approvedStates.has(purchase.subscriptionState)&&expiry>now};
}

export function inspectCatalog(product,plan) {
  if(product?.packageName!==PLAY_PACKAGE||product.productId!==plan.productId)throw fail('catalog_product_mismatch',409);
  const candidates=(product.basePlans||[]).filter(p=>p.state==='ACTIVE'&&p.autoRenewingBasePlanType?.billingPeriodDuration===plan.period);
  if(candidates.length!==1)throw fail('one_active_base_plan_required',409);
  const base=candidates[0],us=(base.regionalConfigs||[]).find(r=>r.regionCode==='US');
  const money=us?.price,units=Number(money?.units||0),nanos=Number(money?.nanos||0);
  if(!us?.newSubscriberAvailability||money?.currencyCode!=='USD'||!Number.isInteger(units)||!Number.isInteger(nanos)||nanos<0||nanos>=1e9||units*1e9+nanos!==plan.usdCents*1e7)throw fail('catalog_us_price_or_availability_mismatch',409);
  return {...plan,basePlanId:base.basePlanId,active:true};
}

/** Fixed Google origins, bounded requests, no client-provided package or price. */
export function createPlayBilling({store,env=process.env,fetchImpl=fetch,now=()=>Date.now()}) {
  const mode=['test','live'].includes(env.ICARUS_BILLING_MODE)?env.ICARUS_BILLING_MODE:'disabled';
  const isolated=env.ICARUS_PRIVATE_TEST==='true'||env.ICARUS_PRIVATE_TEST==='1';
  let credentials=null,key=null;
  try {
    const c=JSON.parse(env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON||'null');
    if(c?.type==='service_account'&&/^[^@\s]+@[^@\s]+\.iam\.gserviceaccount\.com$/.test(c.client_email)&&c.private_key){
      const parsed=createPrivateKey(c.private_key);
      if(parsed.asymmetricKeyType==='rsa'&&parsed.asymmetricKeyDetails?.modulusLength>=2048){credentials=c;key=parsed;}
    }
  }catch{/* Missing or malformed credentials disable billing, not the assistant. */}
  const secret=String(env.ICARUS_BILLING_SECRET||'');
  const configured=Boolean(credentials&&secret.length>=64&&!isolated);
  const testers=new Set(String(env.ICARUS_BILLING_TEST_USER_IDS||'').split(',').map(v=>v.trim()).filter(Boolean));
  const binding=id=>createHmac('sha256',secret).update(`icarus-play-account:v1:${id}`).digest('hex');
  const cipherKey=createHash('sha256').update(`icarus-play-token:v1:${secret}`).digest();
  let oauth=null,oauthPending=null,catalogCache=null,catalogPending=null,certCache=null;
  const locks=new Map(),verificationTimes=new Map();
  function seal(token){const iv=randomBytes(12),c=createCipheriv('aes-256-gcm',cipherKey,iv);return [iv.toString('base64url'),Buffer.concat([c.update(token,'utf8'),c.final()]).toString('base64url'),c.getAuthTag().toString('base64url')].join('.');}
  function unseal(value){try{const [iv,bytes,tag]=value.split('.');const c=createDecipheriv('aes-256-gcm',cipherKey,Buffer.from(iv,'base64url'));c.setAuthTag(Buffer.from(tag,'base64url'));return Buffer.concat([c.update(Buffer.from(bytes,'base64url')),c.final()]).toString();}catch{throw fail('billing_storage_key_mismatch');}}
  async function responseJson(response){
    if(response.status===204)return {};
    let text='';const reader=response.body?.getReader();
    if(!reader)return {};
    try{while(true){const {done,value}=await reader.read();if(done)break;text+=Buffer.from(value).toString();if(text.length>1000000)throw fail('google_response_too_large');}}finally{reader.releaseLock();}
    try{return JSON.parse(text||'{}');}catch{throw fail('google_response_invalid');}
  }
  async function request(url,options={}) {
    try{return await fetchImpl(url,{...options,redirect:'error',signal:AbortSignal.timeout(15000)});}catch{throw fail('google_billing_unavailable');}
  }
  async function accessToken() {
    if(!configured)throw fail('billing_not_configured');
    if(oauth?.until>now())return oauth.value;
    if(oauthPending)return oauthPending;
    oauthPending=(async()=>{
      const seconds=Math.floor(now()/1000),header=b64({alg:'RS256',typ:'JWT'}),payload=b64({iss:credentials.client_email,scope:'https://www.googleapis.com/auth/androidpublisher',aud:TOKEN_URL,iat:seconds,exp:seconds+3600});
      const signer=createSign('RSA-SHA256');signer.update(`${header}.${payload}`);signer.end();
      const assertion=`${header}.${payload}.${signer.sign(key).toString('base64url')}`;
      const result=await request(TOKEN_URL,{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion})});
      if(!result.ok)throw fail('google_billing_credentials_rejected');
      const value=await responseJson(result);if(typeof value.access_token!=='string'||!value.access_token||!Number.isFinite(value.expires_in))throw fail('google_token_invalid');
      oauth={value:value.access_token,until:now()+Math.max(0,Math.min(value.expires_in,3600)-60)*1000};return oauth.value;
    })().finally(()=>{oauthPending=null;});return oauthPending;
  }
  async function api(path,method='GET') {
    const token=await accessToken();
    const result=await request(PREFIX+path,{method,headers:{authorization:`Bearer ${token}`,...(method==='POST'?{'content-type':'application/json'}:{})},...(method==='POST'?{body:'{}'}:{})});
    if(!result.ok){if(result.status===401)oauth=null;throw Object.assign(fail(result.status===404||result.status===410?'google_resource_not_found':'google_billing_request_failed'),{googleStatus:result.status});}
    return responseJson(result);
  }
  async function catalog() {
    if(catalogCache?.until>now())return catalogCache.value;
    if(catalogPending)return catalogPending;
    catalogPending=Promise.all(PREMIUM_PLANS.map(async plan=>inspectCatalog(await api(`/subscriptions/${plan.productId}`),plan))).then(plans=>{catalogCache={value:plans,until:now()+300000};return plans;}).finally(()=>{catalogPending=null;});
    return catalogPending;
  }
  function checkoutAllowed(user){const liveReady=env.ICARUS_BILLING_LIVE_APPROVED==='true'&&env.ICARUS_RTDN_AUDIENCE&&env.ICARUS_RTDN_SERVICE_ACCOUNT;return configured&&(mode==='live'&&liveReady||mode==='test'&&testers.has(user.id));}
  async function configuration(user) {
    const result={mode,configured,checkoutEnabled:false,billingProtocolVersion:2,plans:PREMIUM_PLANS.map(p=>({...p,active:false})),reason:!configured?'billing_not_configured':'checkout_disabled'};
    if(!configured)return result;
    try{result.plans=await catalog();result.catalogVerified=true;}catch(error){result.reason=error.status?error.message:'catalog_unavailable';return result;}
    if(checkoutAllowed(user)){result.checkoutEnabled=true;result.accountBinding=binding(user.id);result.reason=null;}
    return result;
  }
  async function locked(token,fn){const id=hash(token),previous=locks.get(id)||Promise.resolve();const work=previous.catch(()=>{}).then(fn);locks.set(id,work);try{return await work;}finally{if(locks.get(id)===work)locks.delete(id);}}
  async function verify(user,token,{refresh=false}={}) {
    if(!configured)throw fail('billing_not_configured');
    if(!validToken(token))throw fail('invalid_purchase_token',400);
    return locked(token,async()=>{
      const id=hash(token),data=await store.read(),existing=(data.playPurchases||[]).find(p=>p.id===id);
      if(existing&&(existing.userId!==user.id||existing.deleted))throw fail('purchase_already_bound',409);
      if(existing?.replacedBy)return entitlement(user,{refresh:false});
      let purchase;
      try{purchase=await api(`/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`);}catch(error){
        if(existing&&[404,410].includes(error.googleStatus))await store.update(d=>{const p=d.playPurchases?.find(v=>v.id===id);if(p){p.state='SUBSCRIPTION_STATE_EXPIRED';p.expiresAt=0;p.verifiedAt=now();}});
        throw error;
      }
      const record=inspectSubscription(purchase,binding(user.id),now());
      if(mode==='test'&&!record.testPurchase)throw fail('real_purchase_rejected_in_test_mode',409);
      if(!existing&&!checkoutAllowed(user)&&!refresh)throw fail('checkout_disabled',409);
      const linked=purchase.linkedPurchaseToken;
      if(linked&&!validToken(linked))throw fail('invalid_linked_purchase',409);
      const linkedId=linked?hash(linked):null;
      if(linkedId===id)throw fail('invalid_linked_purchase',409);
      await store.update(d=>{
        if(!d.users.some(u=>u.id===user.id))throw fail('account_no_longer_available',401);
        d.playPurchases||=[];
        const prior=d.playPurchases.find(p=>p.id===id),old=d.playPurchases.find(p=>p.id===linkedId);
        if(prior&&(prior.userId!==user.id||prior.deleted||prior.replacedBy))throw fail('purchase_already_bound',409);
        if(old&&(old.userId!==user.id||old.deleted))throw fail('linked_purchase_account_mismatch',409);
        if(linkedId){if(old)old.replacedBy=id;else d.playPurchases.push({id:linkedId,userId:user.id,replacedBy:id,expiresAt:0});}
        const saved={...record,id,userId:user.id,token:seal(token)};
        delete saved.eligible;
        if(prior)Object.assign(prior,saved);else d.playPurchases.push(saved);
      });
      if(record.eligible&&!record.acknowledged){
        await api(`/purchases/subscriptions/${record.productId}/tokens/${encodeURIComponent(token)}:acknowledge`,'POST');
        await store.update(d=>{const p=d.playPurchases?.find(v=>v.id===id&&v.userId===user.id&&!v.deleted);if(p)p.acknowledged=true;});
      }
      return entitlement(user,{refresh:false});
    });
  }
  async function entitlement(user,{refresh=true}={}) {
    let data=await store.read();
    if(refresh&&configured){
      const rows=(data.playPurchases||[]).filter(p=>p.userId===user.id&&!p.deleted&&!p.replacedBy&&p.token&&p.verifiedAt<now()-300000&&p.expiresAt>now()-60*86400000);
      for(const row of rows.slice(0,3)){try{await verify(user,unseal(row.token),{refresh:true});}catch{/* A stale/expired record never becomes Premium because refresh failed. */}}
      data=await store.read();
    }
    return entitlementFor(user,env,data.playPurchases||[],now());
  }
  async function authenticatePush(auth) {
    const jwt=String(auth||'').replace(/^Bearer /,'');if(jwt.length>10000)throw fail('invalid_push_identity',401);
    try{
      const [h,p,s,...extra]=jwt.split('.');if(extra.length||!h||!p||!s)throw Error();
      const header=JSON.parse(Buffer.from(h,'base64url')),claims=JSON.parse(Buffer.from(p,'base64url'));
      if(header.alg!=='RS256'||typeof header.kid!=='string'||!env.ICARUS_RTDN_AUDIENCE||!env.ICARUS_RTDN_SERVICE_ACCOUNT||!['accounts.google.com','https://accounts.google.com'].includes(claims.iss)||claims.aud!==env.ICARUS_RTDN_AUDIENCE||claims.email!==env.ICARUS_RTDN_SERVICE_ACCOUNT||![true,'true'].includes(claims.email_verified)||!Number.isFinite(claims.exp)||claims.exp<=now()/1000||!Number.isFinite(claims.iat)||claims.iat>now()/1000+60||claims.iat<now()/1000-3700)throw Error();
      if(!certCache||certCache.until<now()){const r=await request(GOOGLE_CERTS);if(!r.ok)throw Error();certCache={keys:(await responseJson(r)).keys,until:now()+300000};}
      const jwk=certCache.keys?.find(k=>k.kid===header.kid&&k.kty==='RSA'&&k.alg==='RS256');if(!jwk)throw Error();
      const verify=createVerify('RSA-SHA256');verify.update(`${h}.${p}`);verify.end();if(!verify.verify(createPublicKey({key:jwk,format:'jwk'}),Buffer.from(s,'base64url')))throw Error();
    }catch{throw fail('invalid_push_identity',401);}
  }
  async function notification(auth,input) {
    if(!configured)throw fail('billing_not_configured');
    await authenticatePush(auth);
    let message;try{const raw=input?.message?.data;if(typeof raw!=='string'||raw.length>60000)throw Error();message=JSON.parse(Buffer.from(raw,'base64').toString());}catch{throw fail('invalid_notification',400);}
    if(message.packageName!==PLAY_PACKAGE)throw fail('notification_package_mismatch',400);
    if(message.testNotification)return;
    const token=message.subscriptionNotification?.purchaseToken||message.voidedPurchaseNotification?.purchaseToken;
    if(!validToken(token))throw fail('invalid_notification_token',400);
    const data=await store.read(),row=(data.playPurchases||[]).find(p=>p.id===hash(token));if(row?.deleted||row?.replacedBy)return;
    let user=data.users.find(u=>u.id===row?.userId);
    if(!user){const purchase=await api(`/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`);user=data.users.find(u=>binding(u.id)===purchase.externalAccountIdentifiers?.obfuscatedExternalAccountId);}
    if(!user)return;
    await verify(user,token,{refresh:true});
  }
  function rateLimit(user){const cutoff=now()-60000;const hits=(verificationTimes.get(user.id)||[]).filter(t=>t>cutoff);if(hits.length>=12)throw fail('billing_rate_limited',429);hits.push(now());verificationTimes.set(user.id,hits);if(verificationTimes.size>1000)for(const [id,v] of verificationTimes)if(v.at(-1)<cutoff)verificationTimes.delete(id);}
  return {configuration,entitlement,verify,notification,rateLimit,accountBinding:binding};
}

export async function billingBody(req){const chunks=[];let size=0;for await(const chunk of req){size+=chunk.length;if(size>65536)throw fail('billing_request_too_large',413);chunks.push(chunk);}try{return JSON.parse(Buffer.concat(chunks).toString()||'{}');}catch{throw fail('invalid_json',400);}}
