import React,{useEffect,useRef,useState} from 'react';
import {actionRequest,executeProposal,checkDevice} from './device-actions';

async function api(path,options={}) {
  const r=await fetch('/api'+path,{...options,headers:{'content-type':'application/json',authorization:`Bearer ${localStorage.getItem('icarus_token')||''}`},signal:AbortSignal.timeout(70000)});
  if(!r.ok)throw Error('Request failed. Check your connection and sign-in.');
  return r.json();
}
export function Chat(){
  const [messages,setMessages]=useState([]),[conversationId,setConversationId]=useState(),[conversations,setConversations]=useState([]);
  const [busy,setBusy]=useState(false),[temporary,setTemporary]=useState(false),[notice,setNotice]=useState(''),[draft,setDraft]=useState('');
  const [search,setSearch]=useState(false),[canSearch,setCanSearch]=useState(false),[native,setNative]=useState(false);
  const [pending,setPending]=useState(null),[deleteId,setDeleteId]=useState(null);const lock=useRef(false),actionLock=useRef(false),mounted=useRef(true);
  const loadHistory=()=>api('/conversations').then(r=>{if(mounted.current)setConversations(r.conversations)}).catch(()=>{if(mounted.current)setNotice('History is temporarily unavailable.')});
  useEffect(()=>{mounted.current=true;loadHistory();api('/capabilities').then(c=>{if(mounted.current)setCanSearch(c.webSearch===true)}).catch(()=>{});let checking=false;const refresh=async()=>{if(checking)return;checking=true;const connected=await checkDevice();checking=false;if(mounted.current)setNative(connected);};refresh();const t=setInterval(refresh,5000);return()=>{mounted.current=false;clearInterval(t)}},[]);
  const reset=()=>{setConversationId();setMessages([]);setPending(null);setNotice('')};
  const send=async e=>{
    e.preventDefault();const text=draft.trim();if(!text||lock.current||pending)return;lock.current=true;setBusy(true);setNotice('');
    const prior=messages;setMessages([...prior,{role:'user',content:text}]);
    try{
      const r=await api(temporary?'/chat/temporary':'/chat',{method:'POST',body:JSON.stringify({message:text,conversationId,history:temporary?prior:undefined,native,search})});
      if(!mounted.current)return;
      if(!temporary)setConversationId(r.conversationId);
      setMessages([...prior,{role:'user',content:text},{role:'assistant',content:r.reply,sources:r.sources||[]}]);setDraft('');
      if(r.proposal){try{actionRequest(r.proposal);setPending(r.proposal);}catch{setNotice('The proposed action was invalid. Nothing was sent to Android.');}}
      if(!temporary)loadHistory();
    }catch{if(mounted.current){setMessages(prior);setNotice('ICARUS could not finish this request. Your draft is retained. Check history before resending because the server may have received it.');}}
    finally{lock.current=false;if(mounted.current)setBusy(false);}
  };
  const confirmAction=async()=>{
    if(actionLock.current||!pending)return;actionLock.current=true;setBusy(true);const proposal=pending;setPending(null);
    const result=await executeProposal(proposal);
    if(mounted.current){setMessages(m=>[...m,{role:'assistant',content:result}]);setBusy(false);setNotice('Device results stay in this view and are not saved to conversation history.');}
    actionLock.current=false;
  };
  const deleteChat=async()=>{if(lock.current||!deleteId)return;lock.current=true;setBusy(true);try{await api(`/conversations/${deleteId}`,{method:'DELETE'});if(conversationId===deleteId)reset();setDeleteId(null);await loadHistory();}catch{setNotice('Conversation could not be deleted. Try again.');}finally{lock.current=false;setBusy(false);}};
  const copy=async text=>{try{await navigator.clipboard.writeText(text);setNotice('Response copied.')}catch{setNotice('Copy is unavailable. Select the response text to copy it.')}};
  return <section className="workspace chat-workspace">
    <div className="chat-heading"><div><p className="eyebrow">ICARUS CHAT</p><h1>Ask. Build. Move.</h1></div><div className="chat-actions">
      <button className="secondary" disabled={busy} onClick={reset}>+ New chat</button>
      <button className="secondary" disabled={busy} aria-pressed={temporary} onClick={()=>{reset();setTemporary(!temporary)}}>Temporary {temporary?'on':'off'}</button>
    </div></div>
    <p>{native?'Phone actions are connected. Review each action before sending it to Android.':window.IcarusNative?.postMessage?'Checking Android support. If this persists, update ICARUS to use reviewed Chat actions.':'Open the Android app for phone actions. You can chat here.'}</p>
    <div className="chat-layout"><aside className="history-panel" aria-label="Conversation history"><b>RECENT</b>{conversations.length===0&&<p>No saved chats yet.</p>}{conversations.map(c=><div key={c.id}><button disabled={busy} className={conversationId===c.id?'current':''} onClick={()=>{setTemporary(false);setConversationId(c.id);setMessages(c.messages||[]);setPending(null);setNotice('')}}>{c.title}</button><button disabled={busy} aria-label={`Delete ${c.title}`} onClick={()=>setDeleteId(c.id)}>Delete</button></div>)}</aside>
    <div className="chat-main"><div className="messages" aria-live="polite">
      {!messages.length&&<div className="empty"><b>{temporary?'TEMPORARY CHAT':'START A CONVERSATION'}</b><p>{temporary?'This conversation will not be saved or use Memory.':'Ask a question or request a phone action. ICARUS uses only memories you explicitly save.'}</p></div>}
      {messages.map((m,i)=><article className={m.role} key={i}><b>{m.role==='user'?'YOU':'ICARUS'}</b><p>{m.content}</p>{m.role==='assistant'&&<div className="message-tools"><button onClick={()=>copy(m.content)}>Copy</button><button disabled={busy||Boolean(pending)} onClick={()=>{const last=messages.slice(0,i).reverse().find(x=>x.role==='user');if(last){setDraft(last.content);setNotice('Review your draft and send it again if needed. No phone action has been repeated.')}}}>Retry</button></div>}{m.sources?.length>0&&<ul aria-label="Sources">{m.sources.filter(s=>/^https?:\/\//i.test(s.url)).map(s=><li key={s.url}><a href={s.url} target="_blank" rel="noopener noreferrer">{s.title}</a></li>)}</ul>}</article>)}
      {busy&&<p role="status">{actionLock.current?'Waiting for Android…':search?'Searching and preparing an answer…':'ICARUS is thinking…'}</p>}
    </div>
    {pending&&<section className="settings-section" aria-labelledby="action-review"><h2 id="action-review">{actionRequest(pending).label}?</h2><p>Nothing has been executed. Android may require permission or an unlocked phone.</p><div className="settings-actions"><button className="secondary" disabled={busy} onClick={()=>{setPending(null);setNotice('Cancelled. Nothing was sent to Android.')}}>Cancel action</button><button className="primary" disabled={busy||!native} onClick={confirmAction}>Confirm action</button></div></section>}
    {notice&&<p className="chat-notice" role="status">{notice}</p>}
    {deleteId&&<section className="settings-section"><h2>Delete this conversation?</h2><p>This removes its saved messages.</p><div className="settings-actions"><button className="secondary" disabled={busy} onClick={()=>setDeleteId(null)}>Keep conversation</button><button className="danger" disabled={busy} onClick={deleteChat}>Delete conversation</button></div></section>}
    <form className="composer" onSubmit={send} noValidate><textarea style={{resize:"none"}} name="message" aria-label="Message" value={draft} onChange={e=>setDraft(e.target.value)} placeholder="Tell ICARUS what you need…" disabled={busy||Boolean(pending)} onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.nativeEvent.isComposing){e.preventDefault();e.currentTarget.form.requestSubmit()}}}/><button className="primary" disabled={busy||Boolean(pending)||!draft.trim()}>{busy?'Wait…':'Send'}<span>→</span></button></form>
    <label><input type="checkbox" checked={search} disabled={!canSearch||busy||Boolean(pending)} onChange={e=>setSearch(e.target.checked)}/> Search web {canSearch?'(read-only turn)':'(not configured)'}</label>
    <small className="assistant-disclosure">ICARUS can make mistakes. Verify important information. An Android acknowledgement does not prove a call connected or a task finished.</small>
    </div></div>
  </section>;
}
