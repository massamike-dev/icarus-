import React,{useEffect,useRef,useState} from 'react';

const request=async(path,payload,authenticated=false)=>{
  const token=authenticated?localStorage.getItem('icarus_token'):null;
  const response=await fetch(`/api/auth/${path}`,{method:payload?'POST':'GET',headers:{'content-type':'application/json',...(token?{authorization:`Bearer ${token}`}:{})},...(payload?{body:JSON.stringify(payload)}:{})});
  const value=await response.json();
  if(authenticated&&localStorage.getItem('icarus_token')!==token)throw new Error('authentication_required');
  if(!response.ok)throw new Error(value.error||'request_failed');
  return value;
};
function useConfig(){
  const[config,setConfig]=useState(null);
  useEffect(()=>{let active=true;request('config').then(v=>{if(active)setConfig(v)}).catch(()=>{if(active)setConfig({})});return()=>{active=false}},[]);
  return config;
}
const errorText=error=>({invalid_or_expired_code:'That code is invalid or expired. Check the two words or request a new code.',invalid_or_expired_reset_link:'Your reset session is invalid or expired. Start again.',invalid_or_expired_reset_token:'Your reset session is invalid or expired. Start again.',invalid_or_expired_reset_session:'Your reset session is invalid or expired. Start again.',invalid_current_password:'Your current password was not accepted.',password_must_be_10_to_1024_characters:'Use a password between 10 and 1024 characters.',invalid_reset_token:'Your reset session is invalid or expired. Start again.',invalid_credentials:'Your current password was not accepted.',try_again_later:'Too many attempts. Please wait before trying again.',rate_limited:'Too many attempts. Please wait before trying again.',authentication_required:'Please sign in again before continuing.'}[error]||'The request could not be completed. Please try again.');
const challengeValid=value=>typeof value==='string'&&/^[a-f0-9]{32}$/.test(value);
const resetValid=value=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
function PasswordFields(){return <><label>New password<input name="password" type="password" autoComplete="new-password" minLength={10} maxLength={1024} required/></label><label>Confirm new password<input name="confirmPassword" type="password" autoComplete="new-password" minLength={10} maxLength={1024} required/></label><small>Use at least 10 characters. All existing sessions will be signed out.</small></>}
function CodeField(){return <label>Two-word recovery code<input name="code" type="text" autoComplete="one-time-code" autoCapitalize="none" spellCheck={false} maxLength={64} required/><small>Enter both Greek words using Latin letters, separated by a space or hyphen. Uppercase or lowercase is fine.</small></label>}
function useRequestGuard(){
  const live=useRef(false),epoch=useRef(0),inFlight=useRef(false);
  useEffect(()=>{live.current=true;return()=>{live.current=false;epoch.current++;inFlight.current=false}},[]);
  const invalidate=()=>{epoch.current++;inFlight.current=false};
  const begin=()=>{if(inFlight.current||!live.current)return null;inFlight.current=true;const current=epoch.current;return()=>live.current&&epoch.current===current};
  return {begin,invalidate,inFlight};
}
function useCooldown(until){const[now,setNow]=useState(Date.now());useEffect(()=>{setNow(Date.now());if(!until)return;const timer=setInterval(()=>setNow(Date.now()),1000);return()=>clearInterval(timer)},[until]);return Math.max(0,Math.ceil((until-now)/1000))}

export function PasswordRecovery({reset=false}){
  const config=useConfig(),[step,setStep]=useState('email'),[email,setEmail]=useState(''),[challenge,setChallenge]=useState(''),[resetToken,setResetToken]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[until,setUntil]=useState(0),guard=useRequestGuard(),remaining=useCooldown(until);
  const clear=()=>{guard.invalidate();setStep('email');setEmail('');setChallenge('');setResetToken('');setBusy(false);setError('');setUntil(0)};
  useEffect(()=>{
    // Old reset-link secrets are never accepted and never retained in the URL.
    if(location.hash||location.search)history.replaceState(history.state,'',location.pathname);
    const resetFlow=()=>clear();window.addEventListener('popstate',resetFlow);window.addEventListener('pagehide',resetFlow);
    return()=>{window.removeEventListener('popstate',resetFlow);window.removeEventListener('pagehide',resetFlow)};
  },[]);
  const send=async(address)=>{
    const result=await request('forgot-password',{email:address});
    if(result?.ok!==true||!challengeValid(result.challengeId))throw new Error('request_failed');
    return result;
  };
  const submit=async event=>{
    event.preventDefault();const form=event.currentTarget,input=Object.fromEntries(new FormData(form));
    if(step==='password'&&input.password!==input.confirmPassword){setError('The passwords do not match.');return}
    const current=guard.begin();if(!current)return;setError('');setBusy(true);
    try{
      if(step==='email'){
        const address=String(input.email||'').trim().toLowerCase(),result=await send(address);if(!current())return;
        setEmail(address);setChallenge(result.challengeId);setUntil(Date.now()+60_000);setStep('code');
      }else if(step==='code'){
        const phrase=String(input.code||'');form.reset();
        const result=await request('verify-recovery-code',{email,challengeId:challenge,code:phrase});if(!current())return;
        if(result?.ok!==true||!resetValid(result.resetToken))throw new Error('request_failed');
        setResetToken(result.resetToken);setChallenge('');setStep('password');
      }else if(step==='password'){
        const result=await request('reset-password',{resetToken,password:input.password});if(!current())return;
        if(result?.ok!==true)throw new Error('request_failed');
        form.reset();setResetToken('');setChallenge('');setEmail('');setUntil(0);setStep('done');
      }
    }catch(err){if(current())setError(errorText(err.message))}finally{if(current()){guard.inFlight.current=false;setBusy(false)}}
  };
  const resend=async()=>{
    if(remaining>0)return;const current=guard.begin();if(!current)return;setBusy(true);setError('');
    try{const result=await send(email);if(!current())return;setChallenge(result.challengeId);setResetToken('');setUntil(Date.now()+60_000)}
    catch(err){if(current())setError(errorText(err.message))}finally{if(current()){guard.inFlight.current=false;setBusy(false)}}
  };
  return <main className="auth recovery"><img src="/brand/icarus-crest.svg" alt="ICARUS"/><h1>{step==='password'?'Choose a new password':step==='code'?'Enter your recovery code':reset?'Reset password':'Forgot password?'}</h1>
    {!config?<p role="status">Checking recovery availability…</p>:config.privateTest||config.passwordRecoveryEnabled!==true?<p role="status">Password recovery is not available here yet. Your account has not been changed.</p>:step==='done'?<p role="status">Your password has been changed. Sign in with your new password.</p>:<>
      {step==='email'&&<p>Request a code using the email registered and verified for recovery. Phone and other recovery methods are not available here yet.</p>}
      {step==='code'&&<p role="status">If an eligible account matches that email, a two-word code will be sent. Check your inbox and spam folder. Only the newest code works.</p>}
      <form key={`${step}:${challenge}`} onSubmit={submit}>{step==='email'?<label>Account email<input name="email" type="email" autoComplete="email" maxLength={254} required/></label>:step==='code'?<CodeField/>:<PasswordFields/>}
        {error&&<div className="error" role="alert">{error}</div>}<button className="primary" disabled={busy}>{busy?'Please wait…':step==='email'?'Send recovery code':step==='code'?'Verify code':'Reset password'}</button>
      </form>
      {step==='code'&&<button type="button" className="text-button" disabled={busy||remaining>0} onClick={resend}>{remaining>0?`Resend available in ${remaining}s`:'Resend code'}</button>}
      {step!=='email'&&<button type="button" className="text-button" onClick={clear}>{step==='code'?'Use a different email':'Start again'}</button>}
    </>}
    <a className="text-button" href="/">Back to ICARUS</a>
  </main>;
}

function RecoveryEmailEnrollment(){
  const[state,setState]=useState(null),[step,setStep]=useState('password'),[challenge,setChallenge]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),guard=useRequestGuard();
  useEffect(()=>{let active=true;request('recovery-email',undefined,true).then(value=>{if(active)setState(typeof value?.verified==='boolean'&&typeof value.email==='string'?value:{unavailable:true})}).catch(()=>{if(active)setState({unavailable:true})});return()=>{active=false}},[]);
  const cancel=()=>{guard.invalidate();setChallenge('');setStep('password');setBusy(false);setError('')};
  const submit=async event=>{
    event.preventDefault();const form=event.currentTarget,input=Object.fromEntries(new FormData(form)),current=guard.begin();if(!current)return;setBusy(true);setError('');form.reset();
    try{
      if(step==='password'){
        const value=await request('recovery-email',{currentPassword:input.currentPassword},true);if(!current())return;
        if(value?.ok!==true||!challengeValid(value.challengeId))throw new Error('request_failed');
        setChallenge(value.challengeId);setStep('code');
      }else{
        const value=await request('verify-recovery-email',{challengeId:challenge,code:input.code},true);if(!current())return;
        if(value?.ok!==true)throw new Error('request_failed');
        setChallenge('');setState({...state,verified:true});setStep('done');
      }
    }catch(err){if(current())setError(errorText(err.message))}finally{if(current()){guard.inFlight.current=false;setBusy(false)}}
  };
  return <section aria-label="Recovery email"><h3>Recovery email</h3>{!state?<p role="status">Checking recovery email…</p>:state.unavailable?<p role="status">Recovery email status is unavailable. Try again later.</p>:state.verified?<p role="status">Your recovery email is verified.</p>:<>
    <p>Verify the email already on your account before it can be used to recover your password. This does not add or change an email address.</p>
    {typeof state.email==='string'&&<p>{state.email}</p>}
    {step==='code'&&<p role="status">Enter the two-word code sent to your account email.</p>}
    <form key={step} onSubmit={submit}>{step==='password'?<label>Current password to verify email<input name="currentPassword" type="password" autoComplete="current-password" maxLength={1024} required/></label>:<CodeField/>}
      {error&&<div role="alert" className="error">{error}</div>}<button className="primary" disabled={busy}>{busy?'Please wait…':step==='password'?'Send email verification code':'Verify recovery email'}</button>
    </form>{step==='code'&&<button type="button" className="text-button" onClick={cancel}>Cancel email verification</button>}
  </>}</section>;
}
export function ChangePassword({onSignOut}){
  const config=useConfig(),[busy,setBusy]=useState(false),[error,setError]=useState(''),guard=useRequestGuard();
  if(!config||config.privateTest||config.passwordChangeEnabled!==true)return null;
  const submit=async event=>{
    event.preventDefault();const form=event.currentTarget,input=Object.fromEntries(new FormData(form));setError('');
    if(input.password!==input.confirmPassword){setError('The passwords do not match.');return}
    const current=guard.begin();if(!current)return;setBusy(true);
    try{const value=await request('change-password',{currentPassword:input.currentPassword,password:input.password},true);if(!current())return;if(value?.ok!==true)throw new Error('request_failed');form.reset();localStorage.removeItem('icarus_token');onSignOut();}
    catch(err){if(current())setError(errorText(err.message))}finally{if(current()){guard.inFlight.current=false;setBusy(false)}}
  };
  return <section className="workspace recovery"><h2>Change password</h2><p>Your saved chats and memories stay intact. Changing your password signs out all sessions, including this one.</p><form aria-label="Change password" onSubmit={submit}><label>Current password<input name="currentPassword" type="password" autoComplete="current-password" maxLength={1024} required/></label><PasswordFields/>{error&&<div role="alert" className="error">{error}</div>}<button className="primary" disabled={busy}>{busy?'Changing…':'Change password and sign out'}</button></form>{config.passwordRecoveryEnabled===true&&<RecoveryEmailEnrollment/>}</section>;
}
