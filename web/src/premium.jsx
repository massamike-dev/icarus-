import React,{useEffect,useRef,useState} from 'react';
import {billingNative,matchPlayOffers} from './premium-client.js';
import {getNativeTransport} from './native-transport.js';
import './premium.css';
const management='https://play.google.com/store/account/subscriptions?package=com.icarusalmighty.app';
const planned=[{productId:'icarus_pro_monthly',name:'ICARUS Ascend',periodLabel:'month',usdCents:1000},{productId:'icarus_pro_quarterly',name:'ICARUS Zenith',periodLabel:'3 months',usdCents:1742},{productId:'icarus_pro_annual',name:'ICARUS Crest',periodLabel:'year',usdCents:6969}];

export function PremiumMembership({user}) {
  const [config,setConfig]=useState(null),[play,setPlay]=useState(null),[entitlement,setEntitlement]=useState(null),[message,setMessage]=useState(''),[busy,setBusy]=useState(false),[review,setReview]=useState(null),[testConfirmed,setTestConfirmed]=useState(false);
  const active=useRef(false),lock=useRef(false),abort=useRef(null),session=useRef(''),dialog=useRef(null),cancel=useRef(null);
  const sameAccount=()=>active.current&&session.current===(localStorage.getItem('icarus_token')||'');
  async function api(path,options={}) {
    if(!sameAccount())throw Error('Your sign-in changed. Reopen Settings.');
    const response=await fetch(`/api${path}`,{...options,signal:abort.current?.signal,headers:{'content-type':'application/json',authorization:`Bearer ${session.current}`},cache:'no-store'});
    const data=await response.json();if(!sameAccount())throw Error('Your sign-in changed. Reopen Settings.');
    if(!response.ok)throw Error(String(data.error||'Billing is unavailable.').replaceAll('_',' '));return data;
  }
  async function restoreFrom(native) {
    let pending=false,restored=false;
    for(const purchase of (native?.purchases||[])) {
      if(purchase.state==='pending'){pending=true;continue;}
      if(purchase.state!=='purchased')continue;
      const result=await api('/billing/verify',{method:'POST',body:JSON.stringify({purchaseToken:purchase.purchaseToken})});
      setEntitlement(result.entitlement);restored=restored||result.entitlement?.premium===true;
    }
    return pending?'Payment is pending in Google Play. Premium is not granted for pending payments.':restored?'Premium verified with Google Play.':'No active verified Google Play subscription found.';
  }
  async function refresh(restore=false) {
    if(lock.current||!sameAccount())return;
    lock.current=true;setBusy(true);setMessage('Checking membership…');
    try {
      const [c,e]=await Promise.all([api('/billing/config'),api('/entitlement')]);
      setConfig(c);setEntitlement(e.entitlement);
      if(!getNativeTransport()){setPlay(null);setMessage('Open the installed Android app to use Google Play subscriptions.');return;}
      const native=await billingNative('check_subscription',{},window,{signal:abort.current.signal});
      if(!sameAccount())return;
      setPlay(native);
      if(native.billingProtocolVersion!==2){setMessage('Install ICARUS 1.6.14 or newer to use the verified billing screen.');return;}
      if(restore&&c.configured)setMessage(await restoreFrom(native));
      else setMessage(c.checkoutEnabled?'Google Play prices loaded. No payment is made until you confirm in Google Play.':c.reason==='billing_not_configured'?'Subscription setup is pending. Checkout is disabled.':`Checkout is disabled: ${String(c.reason||'setup pending').replaceAll('_',' ')}.`);
    }catch(error){if(sameAccount())setMessage(error.message);}
    finally{lock.current=false;if(active.current)setBusy(false);}
  }
  useEffect(()=>{
    active.current=true;session.current=localStorage.getItem('icarus_token')||'';abort.current=new AbortController();refresh(true);
    const focus=()=>{if(document.visibilityState!=='hidden')refresh(true);};
    window.addEventListener('focus',focus);document.addEventListener('visibilitychange',focus);
    const timer=setInterval(()=>{if(document.visibilityState!=='hidden')refresh(false);},300000);
    return()=>{active.current=false;abort.current?.abort();clearInterval(timer);window.removeEventListener('focus',focus);document.removeEventListener('visibilitychange',focus);};
  },[user.id]);
  async function purchase() {
    if(lock.current||!review||!sameAccount()||config?.mode==='test'&&!testConfirmed)return;
    const selected=review;setReview(null);dialog.current.close();lock.current=true;setBusy(true);setMessage('Opening Google Play. Review the full charge before confirming.');
    try {
      const c=await api('/billing/config');
      const e=await api('/entitlement');
      if(e.entitlement?.premium)throw Error('This account already has Premium. Manage the existing membership instead.');
      if(!c.checkoutEnabled||!matchPlayOffers(c,play).some(p=>p.productId===selected.productId&&p.basePlanId===selected.basePlanId&&p.offerToken===selected.offerToken))throw Error('The selected plan changed or checkout is unavailable. Refresh prices.');
      const productSpec=[selected.productId,c.accountBinding,selected.basePlanId,selected.offerToken].join('|');
      const result=await billingNative('subscribe',{sku:productSpec},window,{signal:abort.current.signal,timeout:180000});
      if(!sameAccount())return;
      if(result.purchase?.state==='pending'){setMessage('Payment is pending in Google Play. Premium will be available after payment is completed and verified.');return;}
      if(result.purchase?.state!=='purchased')throw Error('Google Play did not report a completed purchase.');
      const confirmed=await api('/billing/verify',{method:'POST',body:JSON.stringify({purchaseToken:result.purchase.purchaseToken})});
      setEntitlement(confirmed.entitlement);
      setMessage(confirmed.entitlement?.premium?'Premium is verified and active.':'Payment was checked, but no active Premium entitlement was returned. Contact support before buying again.');
    }catch(error){if(sameAccount())setMessage(`${error.message} A charge or cancellation is not assumed. Use Restore purchases before retrying.`);}
    finally{lock.current=false;if(active.current)setBusy(false);}
  }
  const offers=matchPlayOffers(config,play),hasPurchase=(play?.purchases||[]).some(p=>['purchased','pending'].includes(p.state));
  const enabled=config?.checkoutEnabled===true&&!entitlement?.premium&&!hasPurchase&&!busy;
  return <section className="workspace premium-workspace" aria-labelledby="premium-title">
    <p className="eyebrow">ICARUS MEMBERSHIP</p><h1 id="premium-title">Choose your billing period</h1>
    <p>Ascend, Zenith and Crest are billing options for the same Premium membership, not three different feature levels.</p>
    {config?.benefits?.length>0&&<p>Premium includes: {config.benefits.join(', ')}.</p>}
    {entitlement?.premium&&<p role="status">Premium active{entitlement.source==='developer_grant'?' · Granted by the developer; no Google Play subscription required.':` · ${entitlement.testPurchase?'Google Play test purchase':'Google Play'}${entitlement.expiresAt?` · Current access ends ${new Date(entitlement.expiresAt).toLocaleDateString()}`:''}`}</p>}
    {config?.mode==='test'&&<p className="premium-warning">Billing test mode. Only designated license testers should continue. Cancel if Google Play shows a real charge instead of a test payment method.</p>}
    <div className="premium-plans">{(config?.plans||planned).map(plan=>{const quote=offers.find(p=>p.productId===plan.productId);return <article className="premium-plan" key={plan.productId}>
      <h2>{plan.name}</h2><p>{quote?`${quote.formattedPrice} / ${plan.periodLabel}`:`Planned U.S. price: $${(plan.usdCents/100).toFixed(2)} / ${plan.periodLabel}`}</p>
      <p>Auto-renews every {plan.periodLabel==='month'?'month':plan.periodLabel==='year'?'year':'3 months'} until canceled. Manage cancellation in Google Play.</p>
      <button className="primary" disabled={!enabled||!quote} onClick={()=>{setReview(quote);setTestConfirmed(false);dialog.current.showModal();cancel.current?.focus();}}>Review {plan.name}</button>
    </article>;})}</div>
    <p>Prices available to your Google account come from Google Play. The Google Play confirmation screen shows the final charge and applicable tax. No separate ICARUS processing surcharge is added.</p>
    <div className="settings-actions"><button className="secondary" disabled={busy} onClick={()=>refresh(false)}>Refresh prices</button><button className="secondary" disabled={busy||!getNativeTransport()||!config?.configured} onClick={()=>refresh(true)}>Restore purchases</button><a href={management} target="_blank" rel="noopener noreferrer">Manage in Google Play</a></div>
    <p className="settings-status" role="status">{message}</p>
    <p className="settings-muted">Account ID for developer grants: <code>{user.id}</code></p>
    <dialog ref={dialog} className="settings-dialog" aria-labelledby="review-premium-title" onCancel={()=>setReview(null)}><h2 id="review-premium-title">Review your subscription</h2>
      {review&&<><p>{review.name}: {review.formattedPrice} every {review.periodLabel}. Renews automatically until canceled through Google Play.</p><p>Confirm only after checking the full charge and tax on Google's payment screen.</p></>}
      {config?.mode==='test'&&<label className="settings-check"><input type="checkbox" checked={testConfirmed} onChange={e=>setTestConfirmed(e.target.checked)}/>I am a Google Play license tester and will cancel any real-charge checkout.</label>}
      <div className="settings-actions"><button ref={cancel} className="secondary" onClick={()=>{setReview(null);dialog.current.close();}}>Cancel</button><button className="primary" disabled={busy||config?.mode==='test'&&!testConfirmed} onClick={purchase}>Continue to Google Play</button></div>
    </dialog>
  </section>;
}
