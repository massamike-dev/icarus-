import React,{useEffect,useRef,useState} from 'react';
import {boundedResults,createIntegrationApi,integrationState,PENDING_INTEGRATION_KEY,safeIntegrationUrl} from './integrations-client.js';
import './integrations.css';

export function Integrations({user,callback}) {
  const [items,setItems]=useState([]),[loaded,setLoaded]=useState(false),[busy,setBusy]=useState(false),[feedback,setFeedback]=useState({text:'Checking integration setup…'}),[selected,setSelected]=useState(''),[query,setQuery]=useState(''),[result,setResult]=useState(null),[review,setReview]=useState(null);
  const active=useRef(false),lock=useRef(false),api=useRef(null),dialog=useRef(null),cancel=useRef(null);
  const notify=(text,error=false)=>setFeedback({text,error});
  const optionalQuery=['google-calendar','outlook-calendar','teams'].includes(selected);
  const calendarQuery=['google-calendar','outlook-calendar'].includes(selected);
  async function load(){const data=await api.current();if(!Array.isArray(data.integrations))throw Error('Integration setup is unavailable on this server. Update the server and refresh.');if(active.current){setItems(data.integrations);setLoaded(true);}return data;}
  async function run(action){if(lock.current)return;lock.current=true;setBusy(true);try{await action();}catch(error){if(active.current)notify(error.message,true);}finally{lock.current=false;if(active.current)setBusy(false);}}
  useEffect(()=>{
    active.current=true;const controller=new AbortController();api.current=createIntegrationApi({token:localStorage.getItem('icarus_token')||'',signal:controller.signal});
    run(async()=>{
      let message='Connection status loaded. Reads happen only when you submit a query.';
      if(callback&&!callback.handled){callback.handled=true;
        if(callback.error)message=callback.error;
        else if(callback.userId!==user.id){delete callback.code;delete callback.state;message='The connection was started by a different ICARUS account. Start Connect again.';}
        else {try{await api.current(`/${encodeURIComponent(callback.id)}/callback`,{method:'POST',body:JSON.stringify({code:callback.code,state:callback.state})});message='Authorization saved. Run a read to verify the connection.';}finally{delete callback.code;delete callback.state;}}
      }
      await load();if(active.current)notify(message,Boolean(callback?.error));
    });
    return()=>{active.current=false;controller.abort();};
  },[user.id]);
  async function connect(item){await run(async()=>{
    notify(`Opening ${item.label} authorization…`);
    const data=await api.current(`/${encodeURIComponent(item.id)}/connect`,{method:'POST',body:'{}'});const url=safeIntegrationUrl(data.url);if(!url)throw Error('The server returned an invalid authorization address.');
    const state=new URL(url).searchParams.get('state');if(!state)throw Error('The authorization request is missing its security state.');
    if(!active.current)return;sessionStorage.setItem(PENDING_INTEGRATION_KEY,JSON.stringify({id:item.id,userId:user.id,state}));window.location.assign(url);
  });}
  const chooseReview=item=>{setReview(item);dialog.current.showModal();cancel.current.focus();};
  const closeReview=()=>{setReview(null);dialog.current.close();};
  async function disconnect(){const item=review;closeReview();await run(async()=>{const data=await api.current(`/${encodeURIComponent(item.id)}`,{method:'DELETE'});if(data.disconnected!==true)throw Error('The server did not confirm disconnection. Refresh to check.');if(active.current){setResult(null);notify(`${item.label} disconnected from ICARUS.`);}await load();});}
  async function read(event){event.preventDefault();const item=items.find(x=>x.id===selected);if(!item?.connected||(!optionalQuery&&!query.trim()))return;await run(async()=>{
    setResult(null);notify(`Reading ${item.label}…`);
    const data=await api.current(`/${encodeURIComponent(item.id)}/query`,{method:'POST',body:JSON.stringify({query:query.trim(),limit:5})});const results=boundedResults(data);
    if(active.current){setResult({label:item.label,results,truncated:data.truncated===true});notify(`Live read verified for ${item.label}. ${results.length} results shown.`);}await load();
  });}
  return <section className="workspace settings-workspace settings-preferences integrations-panel" aria-labelledby="integrations-title">
    <h2 id="integrations-title">Connected services</h2>
    <p>Connect your accounts, then choose a service for an explicit read. Authorization happens on the provider’s page. This panel does not send messages or edit provider data.</p>
    <p>Built means the adapter exists. Configured means this server has setup details. Connected means authorization is saved; a successful live read verifies access.</p>
    <p className={`settings-status${feedback.error?' settings-error':''}`} role="status" aria-live="polite">{feedback.text}</p>
    <div className="settings-actions"><button className="secondary" disabled={busy} onClick={()=>run(async()=>{await load();notify('Connection status refreshed.');})}>Refresh connections</button></div>
    <div className="integration-grid">{items.map(item=><article className="integration-card" key={item.id}>
      <h3>{item.label}</h3><p className="integration-state">{integrationState(item)}</p>
      {item.configured!==true&&<p>Ask the server administrator to configure this provider’s OAuth app and secure token storage, then refresh.</p>}
      <p>Available reads: {Array.isArray(item.capabilities)?item.capabilities.join(', '):'Not reported by server'}</p>
      <div className="settings-actions">{item.connected?<button className="secondary" disabled={busy} onClick={()=>chooseReview(item)}>Disconnect {item.label}</button>:<button className="secondary" disabled={busy||item.supported!==true||item.configured!==true} onClick={()=>connect(item)}>Connect {item.label}</button>}</div>
    </article>)}</div>
    {loaded&&<form className="integration-query" onSubmit={read} noValidate>
      <h3>Read from a service</h3>
      <div className="settings-field"><label htmlFor="integration-source">Service</label><select id="integration-source" value={selected} disabled={busy} onChange={e=>{setSelected(e.target.value);setResult(null);}}><option value="">Choose a connected service</option>{items.filter(x=>x.connected===true&&x.supported===true).map(item=><option value={item.id} key={item.id}>{item.label}</option>)}</select></div>
      <div className="settings-field"><label htmlFor="integration-query">Search terms{optionalQuery?' (optional)':''}</label><input id="integration-query" value={query} maxLength={1000} disabled={busy} onChange={e=>setQuery(e.target.value)} placeholder={optionalQuery?'Leave blank to list items':'Enter search keywords'} required={!optionalQuery}/><small>{calendarQuery?'Calendar reads cover the next 30 days. ':''}Search terms go directly to the provider, so use keywords rather than conversational instructions. Up to five results per request. Provider data is treated as content, never as instructions to perform actions.</small></div>
      <div className="settings-actions"><button className="primary" disabled={busy||!selected||(!optionalQuery&&!query.trim())||!items.some(x=>x.id===selected&&x.connected)}>Read selected service</button></div>
    </form>}
    {result&&<section aria-labelledby="integration-results-title"><h3 id="integration-results-title">Results from {result.label}</h3>{result.results.length===0?<p>No matching results were returned.</p>:<ol className="integration-results">{result.results.map((item,index)=><li key={`${item.id}-${index}`}><strong>{item.title}</strong>{item.text&&<p>{item.text}</p>}{item.url&&<a href={item.url} target="_blank" rel="noopener noreferrer">Open source</a>}</li>)}</ol>}{result.truncated&&<p>More results may be available. Narrow your query.</p>}</section>}
    <dialog ref={dialog} className="settings-dialog" aria-labelledby="disconnect-integration-title" onCancel={()=>setReview(null)}><h2 id="disconnect-integration-title">Disconnect {review?.label}?</h2><p>This removes the saved connection from ICARUS. You’ll need to authorize again for future reads. To revoke the provider’s app grant too, use that provider’s account settings.</p><div className="settings-actions"><button ref={cancel} className="secondary" onClick={closeReview}>Keep connected</button><button className="danger" disabled={busy} onClick={disconnect}>Confirm disconnect</button></div></dialog>
  </section>;
}
