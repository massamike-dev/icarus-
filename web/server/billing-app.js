import {createHandler} from './app.js';
import {verifyToken} from './auth.js';
import {isPrivateTester,privateSessionSecret,privateTestConfig} from './private-test.js';
import {createPlayBilling,billingBody} from './play-billing.js';
import {premiumFeatures,requiredPremiumFeature,PREMIUM_FEATURE_LABELS} from './premium-features.js';
const json=(res,status,value)=>{res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store'});res.end(JSON.stringify(value));};

// The existing assistant routes remain intact. This wrapper owns billing-only
// routes and augments account status with verified, server-owned entitlements.
export function createBillingHandler({store,env=process.env,fetchImpl=fetch,...options}) {
  const original=createHandler({store,env,fetchImpl,...options});
  const privateTest=privateTestConfig(env);
  const secret=privateSessionSecret(env.ICARUS_SESSION_SECRET||(!privateTest&&env.NODE_ENV==='test'?'test-secret-at-least-32-characters':''),privateTest);
  const billing=createPlayBilling({store,env:privateTest?{...env,ICARUS_PRIVATE_TEST:'true'}:env,fetchImpl});
  return async(req,res)=>{
    const path=new URL(req.url,'http://localhost').pathname;
    const feature=privateTest?null:requiredPremiumFeature(path,req.method,env);
    if(!feature&&!path.startsWith('/api/billing/')&&path!=='/api/entitlement'&&path!=='/api/me'&&!(path==='/api/account'&&req.method==='DELETE'))return original(req,res);
    try {
      if(path==='/api/billing/rtdn') {
        if(req.method!=='POST')return json(res,405,{error:'method_not_allowed'});
        await billing.notification(req.headers.authorization,await billingBody(req));
        res.writeHead(204);return res.end();
      }
      const token=String(req.headers.authorization||'').replace(/^Bearer\s+/i,'');
      const id=verifyToken(token,secret),user=id&&(await store.read()).users.find(u=>u.id===id);
      if(!user||privateTest&&!isPrivateTester(user,privateTest))return json(res,401,{error:'authentication_required'});
      if(feature){if(!(await billing.entitlement(user)).premium)return json(res,403,{error:'premium_required',feature});return original(req,res);}
      if(path==='/api/account'&&req.method==='DELETE') {
        // Minimal unlinked token-hash tombstones prevent a deleted purchase being
        // reassigned. Remove the encrypted token and all account associations.
        await store.update(d=>{d.playPurchases=(d.playPurchases||[]).map(p=>p.userId===user.id?{id:p.id,deleted:true,expiresAt:0}:p);});
        return original(req,res);
      }
      if(path==='/api/me'&&req.method==='GET')return json(res,200,{user:{id:user.id,email:user.email,name:user.name,entitlement:await billing.entitlement(user)}});
      if(path==='/api/entitlement'&&req.method==='GET')return json(res,200,{entitlement:await billing.entitlement(user)});
      if(path==='/api/billing/config'&&req.method==='GET'){
        const config=await billing.configuration(user);config.benefits=[...premiumFeatures(env)].map(id=>PREMIUM_FEATURE_LABELS[id]);
        if(config.mode==='live'&&!config.benefits.length){config.checkoutEnabled=false;delete config.accountBinding;config.reason='premium_benefits_not_configured';}
        return json(res,200,config);
      }
      if(path==='/api/billing/verify'&&req.method==='POST') {
        billing.rateLimit(user);const input=await billingBody(req);
        return json(res,200,{entitlement:await billing.verify(user,input.purchaseToken)});
      }
      return json(res,405,{error:'method_not_allowed'});
    }catch(error){return json(res,error.status||500,{error:error.status?error.message:'billing_unavailable'});}
  };
}
