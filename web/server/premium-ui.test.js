import test from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const bundle=await build({root,logLevel:'silent',build:{write:false,minify:false}});
const code=bundle.output.find(x=>x.type==='chunk'&&x.isEntry).code;
const wait=async fn=>{for(let i=0;i<150;i++){if(fn())return;await new Promise(r=>setTimeout(r,10));}assert.fail('UI did not settle');};
async function mount(enabled=false){
 const dom=new JSDOM('<div id="root"></div>',{url:'https://icarus.test',runScripts:'outside-only'}),w=dom.window,requests=[],errors=[];
 w.localStorage.setItem('icarus_token','signed-session');w.scrollTo=()=>{};w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;};
 w.addEventListener('error',e=>{errors.push(e.error);e.preventDefault();});
 const plan={productId:'icarus_pro_monthly',name:'ICARUS Ascend',basePlanId:'monthly',period:'P1M',periodLabel:'month',usdCents:1000};
 w.fetch=async(path,options={})=>{requests.push({path,options});return {ok:true,json:async()=>path==='/api/me'?{user:{id:'test-user',name:'Tester',email:'tester@example.com'}}:path==='/api/billing/config'?{configured:enabled,checkoutEnabled:enabled,mode:'live',plans:[plan],accountBinding:'a'.repeat(64),reason:enabled?null:'billing_not_configured'}:path==='/api/entitlement'?{entitlement:{premium:false,source:'none'}}:path==='/api/billing/verify'?{entitlement:{premium:true,source:'google_play',expiresAt:'2027-01-01T00:00:00Z'}}:{}};};
 w.ICARUS_NATIVE_CHANNEL={postMessage(raw){const p=JSON.parse(raw);requests.push(p);if(p.bridgeRequest){queueMicrotask(()=>w.ICARUS_NATIVE_STATUS?.({requestId:p.requestId,actionProtocolVersion:1,connected:true}));return;}if(p.action==='check_subscription')queueMicrotask(()=>w.ICARUS_NATIVE_RESULT?.({requestId:p.requestId,ok:true,data:{billingProtocolVersion:2,purchases:[],products:[{...plan,billingPeriod:'P1M',formattedPrice:'$10.00',priceAmountMicros:10000000,currencyCode:'USD',offerToken:'current-offer'}]}}));if(p.action==='subscribe')queueMicrotask(()=>w.ICARUS_NATIVE_RESULT?.({requestId:p.requestId,ok:true,data:{billingProtocolVersion:2,purchase:{state:'purchased',purchaseToken:'unverified-token'}}}));}};
 w.eval(code);await wait(()=>w.document.querySelector('nav'));
 [...w.document.querySelectorAll('nav button')].find(b=>b.textContent==='Settings').click();await wait(()=>w.document.querySelector('#premium-title'));await wait(()=>w.document.body.textContent.includes(enabled?'No active verified Google Play subscription found.':'Checkout is disabled'));
 return {dom,w,requests,errors};
}
test('membership screen is visible but cannot charge when setup is missing',async()=>{const a=await mount();try{const b=[...a.w.document.querySelectorAll('button')].find(b=>b.textContent==='Review ICARUS Ascend');assert.equal(b.disabled,true);assert.equal(a.requests.some(p=>p.action==='subscribe'),false);assert.deepEqual(a.errors,[]);}finally{a.dom.window.close();}});
test('purchase requires review and final Google action; Premium appears only after server verification',async()=>{const a=await mount(true);try{const button=text=>[...a.w.document.querySelectorAll('button')].find(b=>b.textContent===text);button('Review ICARUS Ascend').click();await wait(()=>a.w.document.querySelector('dialog[open]'));assert.equal(a.requests.some(p=>p.action==='subscribe'),false);button('Continue to Google Play').click();await wait(()=>a.w.document.body.textContent.includes('Premium is verified and active.'));assert.equal(a.requests.filter(p=>p.action==='subscribe').length,1);assert.ok(a.requests.some(p=>p.path==='/api/billing/verify'));assert.deepEqual(a.errors,[]);}finally{a.dom.window.close();}});
