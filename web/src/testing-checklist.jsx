import React,{useEffect,useRef,useState} from 'react';
import {api} from './local-core.js';
import './testing-checklist.css';

export function TestingChecklist(){
  const [items,setItems]=useState([]),[loaded,setLoaded]=useState(false),[error,setError]=useState(''),[busy,setBusy]=useState(false),[message,setMessage]=useState('');
  const [title,setTitle]=useState(''),[notes,setNotes]=useState('');
  const lock=useRef(false),alive=useRef(false),heading=useRef(null);
  async function load(){setError('');try{const r=await api('/testing-checklist');if(alive.current){setItems(r.items);setLoaded(true)}}catch(e){if(alive.current)setError(e.message.replaceAll('_',' '))}}
  useEffect(()=>{alive.current=true;load();return()=>{alive.current=false}},[]);
  async function save(id,status){
    if(lock.current)return;lock.current=true;setBusy(true);setError('');
    try{
      const r=await api('/testing-checklist'+(id?'/'+id:''),{method:id?'PATCH':'POST',body:JSON.stringify(id?{status}:{title,notes})});
      if(alive.current){setItems(old=>id?old.map(x=>x.id===id?r.item:x):[...old,r.item]);if(!id){setTitle('');setNotes('')}setMessage(id?(status==='done'?'Completed and moved to history.':status==='todo'?'Returned to your active list.':'Ready for testing.'):'Task added.');heading.current?.focus()}
    }catch(e){if(alive.current)setError(e.message.replaceAll('_',' '))}
    finally{lock.current=false;if(alive.current)setBusy(false)}
  }
  const active=items.filter(x=>x.status!=='done'),done=items.filter(x=>x.status==='done');
  return <section className="testing-checklist" aria-labelledby="testing-title">
    <p className="eyebrow">YOUR PRIVATE TEST WORKSPACE</p><h2 ref={heading} tabIndex={-1} id="testing-title">Build it. Test it. Check it off.</h2>
    <p>Only your private test account can access this list. Check items off after you verify them.</p>
    {error&&<div role="alert"><p>{error}</p>{!loaded&&<button onClick={load}>Retry loading</button>}</div>}
    <p role="status">{message||(!loaded&&!error?'Loading your checklist…':loaded?`${active.length} remaining · ${done.length} completed`:'')}</p>
    {loaded&&<>
      <div className="testing-items">{active.map(item=><article key={item.id}><h3>{item.title}</h3><p>{item.notes}</p><span>{item.status==='testing'?'Testing':'To do'}</span><div className="settings-actions"><button disabled={busy} onClick={()=>save(item.id,item.status==='testing'?'todo':'testing')}>{item.status==='testing'?'Back to do':'Start testing'}<span className="testing-sr">: {item.title}</span></button><button className="primary" disabled={busy} onClick={()=>save(item.id,'done')}>Mark complete<span className="testing-sr">: {item.title}</span></button></div></article>)}</div>
      {!active.length&&<p>All checked off. Add your next improvement below.</p>}
      <form onSubmit={e=>{e.preventDefault();save()}}><label htmlFor="test-task-title">New task</label><input id="test-task-title" required maxLength={160} value={title} onChange={e=>setTitle(e.target.value)} disabled={busy}/><label htmlFor="test-task-notes">What to implement or verify</label><textarea id="test-task-notes" maxLength={4000} value={notes} onChange={e=>setNotes(e.target.value)} disabled={busy}/><button className="primary" disabled={busy||!title.trim()}>Add task</button></form>
      {!!done.length&&<details><summary>Completed ({done.length})</summary>{done.map(item=><article key={item.id}><h3>{item.title}</h3><p>{item.notes}</p><button disabled={busy} onClick={()=>save(item.id,'todo')}>Reopen<span className="testing-sr">: {item.title}</span></button></article>)}</details>}
    </>}
  </section>;
}
