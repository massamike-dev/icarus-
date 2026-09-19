import React,{useEffect,useRef,useState} from 'react';
import {actionRequest,executeProposal,checkDevice} from './device-actions';
import {getChatContext,selectChatContext,restoreChatContext} from './native-session';
import {getNativeTransport} from './native-transport.js';

async function api(path,options={}) {
  const r=await fetch('/api'+path,{...options,headers:{'content-type':'application/json',authorization:`Bearer ${localStorage.getItem('icarus_token')||''}`},signal:AbortSignal.timeout(70000)});
  if(!r.ok){const error=Error('Request failed. Check your connection and sign-in.');error.status=r.status;throw error;}
  return r.json();
}
export function Chat(){
  const [messages,setMessages]=useState([]),[conversationId,setConversationId]=useState(()=>getChatContext().conversationId),[conversations,setConversations]=useState([]);
  const [busy,setBusy]=useState(false),[temporary,setTemporary]=useState(()=>getChatContext().temporary),[notice,setNotice]=useState(''),[draft,setDraft]=useState('');
  const [search,setSearch]=useState(false),[canSearch,setCanSearch]=useState(false),[native,setNative]=useState(false);
  const [pending,setPending]=useState(null),[deleteId,setDeleteId]=useState(null),[unsavedResults,setUnsavedResults]=useState([]);
  const lock=useRef(false),actionLock=useRef(false),mounted=useRef(true),pendingRef=useRef(null),retryTurn=useRef(null),historyRequest=useRef(0),actionAbort=useRef(null),unsavedRef=useRef([]);
  const setProposal=value=>{pendingRef.current=value;setPending(value)};
  const loadHistory=async(refreshMessages=false,context=getChatContext())=>{
    const requestId=++historyRequest.current;
    try {
      const r=await api('/conversations');
      if(!mounted.current||requestId!==historyRequest.current)return;
      setConversations(r.conversations||[]);
      if(refreshMessages&&context.revision===getChatContext().revision&&!lock.current&&!actionLock.current&&!pendingRef.current&&!unsavedRef.current.some(r=>r.conversationId===context.conversationId)&&!context.temporary) {
        const selected=r.conversations?.find(c=>c.id===context.conversationId);
        if(selected)setMessages(selected.messages||[]);
      }
    }catch{if(mounted.current)setNotice('History is temporarily unavailable.');}
  };
  useEffect(()=>{
    mounted.current=true;
    api('/capabilities').then(c=>{if(mounted.current)setCanSearch(c.webSearch===true)}).catch(()=>{});
    let checking=false;
    const refresh=async()=>{
      if(checking||document.visibilityState==='hidden'||lock.current||actionLock.current||pendingRef.current)return;
      checking=true;
      try {
        const before=getChatContext();
        const [connected,context]=await Promise.all([checkDevice(),restoreChatContext()]);
        if(!mounted.current)return;
        setNative(connected);
        if(context&&context.revision===getChatContext().revision&&!lock.current&&!actionLock.current&&!pendingRef.current) {
          setConversationId(context.conversationId);setTemporary(context.temporary);
          if(context.conversationId!==before.conversationId||context.temporary!==before.temporary){setMessages([]);retryTurn.current=null;}
          await loadHistory(true,context);
        }
      }finally{checking=false;}
    };
    const onVisible=()=>{if(document.visibilityState!=='hidden')refresh()};
    refresh();const timer=setInterval(refresh,5000);
    window.addEventListener('focus',refresh);document.addEventListener('visibilitychange',onVisible);
    return()=>{mounted.current=false;actionAbort.current?.abort();clearInterval(timer);window.removeEventListener('focus',refresh);document.removeEventListener('visibilitychange',onVisible);};
  },[]);
  const changeContext=(next,selectedMessages=[])=>{
    const context=selectChatContext(next);
    setConversationId(context.conversationId);setTemporary(context.temporary);setMessages(selectedMessages);setProposal(null);setNotice('');setDraft('');retryTurn.current=null;
  };
  const reset=()=>changeContext({temporary});
  const send=async e=>{
    e.preventDefault();const text=draft.trim();if(!text||lock.current||actionLock.current||pending)return;lock.current=true;setBusy(true);setNotice('');
    // Retain the complete failed request, not only its ID: a late bridge check
    // must not change the payload of an idempotent retry.
    const turn=retryTurn.current||{path:temporary?'/chat/temporary':'/chat',prior:messages,body:{message:text,conversationId,history:temporary?messages:undefined,native,search,clientTurnId:crypto.randomUUID()}};
    retryTurn.current=turn;
    const context=selectChatContext({conversationId,temporary});
    const prior=turn.prior;setMessages([...prior,{role:'user',content:text}]);
    try{
      const r=await api(turn.path,{method:'POST',body:JSON.stringify(turn.body)});
      if(context.revision!==getChatContext().revision)return;
      if(!temporary)selectChatContext({conversationId:r.conversationId,temporary:false});
      retryTurn.current=null;
      if(!mounted.current)return;
      if(!temporary)setConversationId(r.conversationId);
      setMessages([...prior,{role:'user',content:text},{role:'assistant',content:r.reply,sources:r.sources||[]}]);setDraft('');
      if(r.proposal){try{actionRequest(r.proposal);setProposal({...r.proposal,temporary});}catch{setNotice('The proposed action was invalid. Nothing was sent to Android.');}}
      if(!temporary)loadHistory();
    }catch(error){if(mounted.current){setMessages(prior);if(error.status===404||error.status===410){retryTurn.current=null;setNotice('This conversation is no longer available. Choose + New chat to continue. Nothing was sent to Android.');}else setNotice('ICARUS could not finish this request. Your draft is retained. Sending the unchanged draft again safely retries this request.');}}
    finally{lock.current=false;if(mounted.current)setBusy(false);}
  };
  const saveResult=async report=>{
    const removeSaved=()=>{unsavedRef.current=unsavedRef.current.filter(r=>r.id!==report.id);setUnsavedResults(unsavedRef.current)};
    if(report.sessionToken!==(localStorage.getItem('icarus_token')||'')) {
      if(mounted.current){removeSaved();setNotice('Your sign-in changed. The device report was not saved; the action will not be repeated.');}
      return;
    }
    try {
      await api(`/actions/${encodeURIComponent(report.id)}/result`,{method:'POST',body:JSON.stringify({status:report.status,summary:report.summary})});
      if(mounted.current){removeSaved();setNotice(report.status==='cancelled'?'Cancellation saved. Nothing was sent to Android.':'Device report saved. ICARUS has not independently verified the outcome.');loadHistory();}
    }catch {
      if(mounted.current){unsavedRef.current=[...unsavedRef.current.filter(r=>r.id!==report.id),report];setUnsavedResults(unsavedRef.current);setNotice('The device report could not be saved. Retry saving it before leaving Chat. The action will not be repeated.');}
    }
  };
  const confirmAction=async()=>{
    if(actionLock.current||!pending)return;actionLock.current=true;setBusy(true);const proposal=pending,sessionToken=localStorage.getItem('icarus_token')||'';setProposal(null);
    const controller=new AbortController();actionAbort.current=controller;
    let result,status='reported';
    try {result=await executeProposal(proposal,window,10000,{signal:controller.signal});}
    catch {status='unknown';result='The Android result is unknown. Check your phone before trying the action again.';}
    if(mounted.current)setMessages(m=>[...m,{role:'assistant',content:result}]);
    // Keep recording an already-dispatched action after navigation. Never send
    // temporary results or retry device execution because persistence failed.
    if(proposal.id&&!proposal.temporary)await saveResult({id:proposal.id,status,summary:result,conversationId,sessionToken});
    else if(mounted.current)setNotice(proposal.temporary?'Temporary chat: the device result will not be saved.':'The device result is shown here; this response has no saved action record.');
    actionAbort.current=null;actionLock.current=false;if(mounted.current)setBusy(false);
  };
  const cancelAction=async()=>{
    if(actionLock.current||!pending)return;
    const proposal=pending,summary='Cancelled. Nothing was sent to Android.',sessionToken=localStorage.getItem('icarus_token')||'';
    actionLock.current=true;setBusy(true);setProposal(null);setMessages(m=>[...m,{role:'assistant',content:summary}]);setNotice(summary);
    if(proposal.id&&!proposal.temporary)await saveResult({id:proposal.id,status:'cancelled',summary,conversationId,sessionToken});
    actionLock.current=false;if(mounted.current)setBusy(false);
  };
  const deleteChat=async()=>{if(lock.current||!deleteId)return;lock.current=true;setBusy(true);try{await api(`/conversations/${deleteId}`,{method:'DELETE'});if(conversationId===deleteId)reset();setDeleteId(null);await loadHistory();}catch{setNotice('Conversation could not be deleted. Try again.');}finally{lock.current=false;setBusy(false);}};
  const copy=async text=>{try{await navigator.clipboard.writeText(text);setNotice('Response copied.')}catch{setNotice('Copy is unavailable. Select the response text to copy it.')}};
  return <section className="workspace chat-workspace">
    <div className="chat-heading"><div><p className="eyebrow">ICARUS CHAT</p><h1>Ask. Build. Move.</h1></div><div className="chat-actions">
      <button className="secondary" disabled={busy||Boolean(pending)} onClick={reset}>+ New chat</button>
      <button className="secondary" disabled={busy||Boolean(pending)} aria-pressed={temporary} onClick={()=>changeContext({temporary:!temporary})}>Temporary {temporary?'on':'off'}</button>
    </div></div>
    <p>{native?'Phone actions are connected. Review each action before sending it to Android.':getNativeTransport()?'Checking Android support. If this persists, update ICARUS to use reviewed Chat actions.':'Open the Android app for phone actions. You can chat here.'}</p>
    <div className="chat-layout"><aside className="history-panel" aria-label="Conversation history"><b>RECENT</b>{conversations.length===0&&<p>No saved chats yet.</p>}{conversations.map(c=><div key={c.id}><button disabled={busy||Boolean(pending)} className={conversationId===c.id?'current':''} onClick={()=>changeContext({conversationId:c.id,temporary:false},c.messages||[])}>{c.title}</button><button disabled={busy||Boolean(pending)} aria-label={`Delete ${c.title}`} onClick={()=>setDeleteId(c.id)}>Delete</button></div>)}</aside>
    <div className="chat-main"><div className="messages" aria-live="polite">
      {!messages.length&&<div className="empty"><b>{temporary?'TEMPORARY CHAT':'START A CONVERSATION'}</b><p>{temporary?'This conversation will not be saved or use Memory.':'Ask a question or request a phone action. ICARUS uses only memories you explicitly save.'}</p></div>}
      {messages.map((m,i)=><article className={m.role} key={i}><b>{m.role==='user'?'YOU':'ICARUS'}</b><p>{m.content}</p>{m.role==='assistant'&&<div className="message-tools"><button onClick={()=>copy(m.content)}>Copy</button><button disabled={busy||Boolean(pending)} onClick={()=>{const last=messages.slice(0,i).reverse().find(x=>x.role==='user');if(last){retryTurn.current=null;setDraft(last.content);setNotice('Review your draft and send it again if needed. No phone action has been repeated.')}}}>Retry</button></div>}{m.sources?.length>0&&<ul aria-label="Sources">{m.sources.filter(s=>/^https?:\/\//i.test(s.url)).map(s=><li key={s.url}><a href={s.url} target="_blank" rel="noopener noreferrer">{s.title}</a></li>)}</ul>}</article>)}
      {busy&&<p role="status">{actionLock.current?'Waiting for Android…':search?'Searching and preparing an answer…':'ICARUS is thinking…'}</p>}
    </div>
    {pending&&<section className="settings-section" aria-labelledby="action-review"><h2 id="action-review">{actionRequest(pending).label}?</h2><p>Nothing has been executed. Android may require permission or an unlocked phone.</p><div className="settings-actions"><button className="secondary" disabled={busy} onClick={cancelAction}>Cancel action</button><button className="primary" disabled={busy||!native} onClick={confirmAction}>Confirm action</button></div></section>}
    {notice&&<p className="chat-notice" role="status">{notice}</p>}
    {unsavedResults.map(report=><section className="settings-section" key={report.id}><p>Unsaved device report for {report.conversationId===conversationId?'this conversation':'a previous conversation'}: {report.summary}</p><button className="secondary" disabled={busy} onClick={async()=>{if(actionLock.current)return;actionLock.current=true;setBusy(true);await saveResult(report);actionLock.current=false;if(mounted.current)setBusy(false)}}>Retry saving result</button></section>)}
    {deleteId&&<section className="settings-section"><h2>Delete this conversation?</h2><p>This removes its saved messages.</p><div className="settings-actions"><button className="secondary" disabled={busy} onClick={()=>setDeleteId(null)}>Keep conversation</button><button className="danger" disabled={busy} onClick={deleteChat}>Delete conversation</button></div></section>}
    <form className="composer" onSubmit={send} noValidate><textarea style={{resize:"none"}} name="message" aria-label="Message" value={draft} onChange={e=>{retryTurn.current=null;setDraft(e.target.value)}} placeholder="Tell ICARUS what you need…" disabled={busy||Boolean(pending)} onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.nativeEvent.isComposing){e.preventDefault();e.currentTarget.form.requestSubmit()}}}/><button className="primary" disabled={busy||Boolean(pending)||!draft.trim()}>{busy?'Wait…':'Send'}<span>→</span></button></form>
    <label><input type="checkbox" checked={search} disabled={!canSearch||busy||Boolean(pending)} onChange={e=>{retryTurn.current=null;setSearch(e.target.checked)}}/> Search web {canSearch?'(read-only turn)':'(not configured)'}</label>
    <small className="assistant-disclosure">ICARUS can make mistakes. Verify important information. An Android acknowledgement does not prove a call connected or a task finished.</small>
    </div></div>
  </section>;
}
