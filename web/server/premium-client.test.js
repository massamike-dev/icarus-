import test from 'node:test';
import assert from 'node:assert/strict';
import {matchPlayOffers,billingNative} from '../src/premium-client.js';
const config={checkoutEnabled:true,plans:[{productId:'icarus_pro_monthly',basePlanId:'monthly',period:'P1M'}]};
const offer={productId:'icarus_pro_monthly',basePlanId:'monthly',billingPeriod:'P1M',formattedPrice:'$10.00',priceAmountMicros:10000000,currencyCode:'USD',offerToken:'live-offer'};
test('only protocol v2 and exact active Google offer can enable a button',()=>{
 assert.equal(matchPlayOffers(config,{billingProtocolVersion:2,products:[offer]}).length,1);
 for(const native of [{billingProtocolVersion:1,products:[offer]},{billingProtocolVersion:2,products:[{...offer,billingPeriod:'P1Y'}]},{billingProtocolVersion:2,products:[{...offer,basePlanId:'different'}]},{billingProtocolVersion:2,products:[offer,offer]}])assert.equal(matchPlayOffers(config,native).length,0);
 assert.equal(matchPlayOffers({...config,checkoutEnabled:false},{billingProtocolVersion:2,products:[offer]}).length,0);
});
test('native billing rejects missing bridges and timeouts, never assumes purchase',async()=>{
 await assert.rejects(billingNative('check_subscription',{},{}),/installed ICARUS/);
 await assert.rejects(billingNative('check_subscription',{}, {ICARUS_NATIVE_CHANNEL:{postMessage(){}}},{timeout:5}),/did not confirm/);
});
test('native billing ignores unrelated results and rejects error payloads',async()=>{
 const host={ICARUS_NATIVE_CHANNEL:{postMessage(raw){const request=JSON.parse(raw);queueMicrotask(()=>{host.ICARUS_NATIVE_RESULT({requestId:'other',ok:true,data:{}});host.ICARUS_NATIVE_RESULT({requestId:request.requestId,ok:false,error:'purchase_canceled'});});}}};
 await assert.rejects(billingNative('subscribe',{},host),/purchase canceled/);
});
test('native billing abort prevents late confirmation',async()=>{
 const controller=new AbortController(),host={ICARUS_NATIVE_CHANNEL:{postMessage(){controller.abort();}}};
 await assert.rejects(billingNative('check_subscription',{},host,{signal:controller.signal}),/cancelled/);
});
