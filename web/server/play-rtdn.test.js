import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,createSign} from 'node:crypto';
import {createPlayBilling,PLAY_PACKAGE} from './play-billing.js';
const pair=generateKeyPairSync('rsa',{modulusLength:2048});
const clock=1801036800000;
const audience='https://icarusassistant.com/api/billing/rtdn';
const pushEmail='push@icarus-test.iam.gserviceaccount.com';
function jwt(overrides={},wrongSignature=false){
 const encode=value=>Buffer.from(JSON.stringify(value)).toString('base64url');
 const h=encode({alg:'RS256',kid:'test-key',typ:'JWT'}),p=encode({iss:'https://accounts.google.com',aud:audience,email:pushEmail,email_verified:true,iat:clock/1000,exp:clock/1000+3600,...overrides});
 const signer=createSign('RSA-SHA256');signer.update(`${h}.${p}`);signer.end();
 const signature=signer.sign(pair.privateKey);
 if(wrongSignature)signature[0]^=1;
 return `Bearer ${h}.${p}.${signature.toString('base64url')}`;
}
function fixture(){
 const user={id:'owner'},data={users:[user],playPurchases:[]};
 const store={read:async()=>structuredClone(data),update:async fn=>fn(data)};
 const env={GOOGLE_PLAY_SERVICE_ACCOUNT_JSON:JSON.stringify({type:'service_account',client_email:'billing@icarus-test.iam.gserviceaccount.com',private_key:pair.privateKey.export({format:'pem',type:'pkcs8'})}),ICARUS_BILLING_SECRET:'f'.repeat(64),ICARUS_BILLING_MODE:'test',ICARUS_BILLING_TEST_USER_IDS:user.id,ICARUS_RTDN_AUDIENCE:audience,ICARUS_RTDN_SERVICE_ACCOUNT:pushEmail};
 let state='SUBSCRIPTION_STATE_ACTIVE',calls=0;
 const api=createPlayBilling({store,env,now:()=>clock,fetchImpl:async(url,options)=>{
  if(url==='https://www.googleapis.com/oauth2/v3/certs')return Response.json({keys:[{...pair.publicKey.export({format:'jwk'}),kid:'test-key',alg:'RS256'}]});
  if(url==='https://oauth2.googleapis.com/token')return Response.json({access_token:'google-test-token',expires_in:3600});
  if(url.endsWith(':acknowledge'))return new Response(null,{status:204});
  if(url.includes('/purchases/subscriptionsv2/tokens/')){calls++;return Response.json({externalAccountIdentifiers:{obfuscatedExternalAccountId:api.accountBinding(user.id)},subscriptionState:state,acknowledgementState:'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',testPurchase:{},lineItems:[{productId:'icarus_pro_monthly',expiryTime:new Date(clock+86400000).toISOString(),autoRenewingPlan:{autoRenewEnabled:true},offerDetails:{basePlanId:'monthly'}}]});}
  throw Error('Unexpected outbound URL');
 }});
 const input=payload=>({message:{data:Buffer.from(JSON.stringify({packageName:PLAY_PACKAGE,...payload})).toString('base64')}});
 return {api,data,user,input,setState:value=>state=value,calls:()=>calls};
}
test('RTDN accepts signed Google identity for the exact audience and email',async()=>{const f=fixture();await f.api.notification(jwt(),f.input({testNotification:{version:'1.0'}}));assert.equal(f.data.playPurchases.length,0);});
test('RTDN resolves a verified purchase to its bound account and is idempotent',async()=>{const f=fixture();const n=f.input({subscriptionNotification:{purchaseToken:'test-purchase-token',notificationType:4}});await f.api.notification(jwt(),n);await f.api.notification(jwt(),n);assert.equal(f.data.playPurchases.length,1);assert.equal((await f.api.entitlement(f.user)).premium,true);assert.equal(f.calls(),3);});
test('RTDN re-queries Google instead of trusting event type for entitlement',async()=>{const f=fixture();const n=f.input({subscriptionNotification:{purchaseToken:'test-purchase-token',notificationType:4}});await f.api.notification(jwt(),n);f.setState('SUBSCRIPTION_STATE_ON_HOLD');await f.api.notification(jwt(),n);assert.equal((await f.api.entitlement(f.user)).premium,false);});
for(const [label,claims] of [['wrong audience',{aud:'https://attacker.invalid'}],['wrong email',{email:'attacker@example.com'}],['expired',{exp:clock/1000-1}],['future-issued',{iat:clock/1000+3600}],['unverified email',{email_verified:false}],['wrong issuer',{iss:'attacker.invalid'}]])test(`RTDN rejects ${label}`,async()=>{const f=fixture();await assert.rejects(f.api.notification(jwt(claims),f.input({testNotification:{}})),/invalid_push_identity/);});
test('RTDN rejects a forged signature even with valid-looking claims',async()=>{const f=fixture();await assert.rejects(f.api.notification(jwt({},true),f.input({testNotification:{}})),/invalid_push_identity/);});
test('RTDN rejects notifications for another package',async()=>{const f=fixture();await assert.rejects(f.api.notification(jwt(),f.input({packageName:'other.app',testNotification:{}})),/notification_package_mismatch/);});
