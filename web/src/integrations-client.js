export const PENDING_INTEGRATION_KEY='icarus.integration-pending';
export function safeIntegrationUrl(value) {
  try { const url=new URL(value);return url.protocol==='https:'&&!url.username&&!url.password?url.href:null; } catch { return null; }
}
export function integrationState(item) {
  if(item.supported!==true)return 'Not available in this build';
  if(item.connected===true)return item.liveVerified===true?'Connected · last read verified':'Connected · live read not yet verified';
  if(item.configured!==true)return 'Built · server setup required';
  if(['expired','reauthorization_required'].includes(item.status))return 'Authorization expired or revoked · connect again';
  return 'Configured · not connected';
}
export function boundedResults(value) {
  if(value?.readOnly!==true||value.liveVerified!==true||!Array.isArray(value.results))throw Error('The service did not confirm a live read. No results are shown.');
  return value.results.slice(0,10).map((item,index)=>({id:String(item.id||index),title:String(item.title||'Result').slice(0,300),text:String(item.text||'').slice(0,4000),url:safeIntegrationUrl(item.url)}));
}
export function createIntegrationApi({token,fetcher=fetch,currentToken=()=>localStorage.getItem('icarus_token')||'',signal,timeoutMs=20000}) {
  return async(path='',options={})=>{
    if(!token||token!==currentToken())throw Error('Your sign-in changed. Reopen Settings.');
    const controller=new AbortController();const abort=()=>controller.abort();signal?.addEventListener('abort',abort,{once:true});if(signal?.aborted)abort();
    const timer=setTimeout(abort,timeoutMs);
    try {
      const response=await fetcher(`/api/integrations${path}`,{...options,signal:controller.signal,cache:'no-store',headers:{'content-type':'application/json',authorization:`Bearer ${token}`}});
      const data=await response.json();
      if(token!==currentToken()||signal?.aborted)throw Error('Your sign-in changed. Reopen Settings.');
      if(!response.ok)throw Error(String(data.message||data.error||'Integration request failed.').replaceAll('_',' ').slice(0,400));
      return data;
    } catch(error) {if(error.name==='AbortError')throw Error('The request stopped or timed out. Refresh connection status before trying again.');throw error;}
    finally {clearTimeout(timer);signal?.removeEventListener('abort',abort);}
  };
}
// Capture and scrub authorization parameters before the app makes any network requests.
export function captureIntegrationCallback(host=window) {
  const url=new URL(host.location.href);if(!url.searchParams.has('code')&&!url.searchParams.has('error'))return null;
  const code=url.searchParams.get('code'),state=url.searchParams.get('state'),denied=url.searchParams.has('error');
  for(const name of ['code','state','error','error_description','error_uri','scope','authuser','prompt'])url.searchParams.delete(name);
  host.history.replaceState(host.history.state,'',`${url.pathname}${url.search}${url.hash}`);
  let pending;try{pending=JSON.parse(host.sessionStorage.getItem(PENDING_INTEGRATION_KEY));host.sessionStorage.removeItem(PENDING_INTEGRATION_KEY);}catch{}
  if(!pending||pending.state!==state)return {error:'This connection return could not be matched. Start Connect again from Settings.'};
  if(denied)return {error:'Connection was not authorized. No connected status has been assumed.'};
  return {id:pending.id,userId:pending.userId,code,state};
}
