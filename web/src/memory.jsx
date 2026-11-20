import React,{useEffect,useRef,useState} from 'react';
import {api,isLocalMode} from './local-core.js';
import {IntelligenceMode} from './intelligence-mode.jsx';

export function Memory({request=api,local=isLocalMode(),onStartChat}){
  const [items,setItems]=useState([]),[phase,setPhase]=useState('loading'),[error,setError]=useState(''),[busy,setBusy]=useState(false),[notice,setNotice]=useState('');
  const [query,setQuery]=useState('');
  const alive=useRef(false),locked=useRef(false),searchField=useRef(null);
  const load=async()=>{
    setPhase('loading');setError('');
    try{const result=await request('/memories');
      if(!Array.isArray(result.memories)||!result.memories.every(m=>m&&typeof m.id==='string'&&typeof m.content==='string'))throw Error('Saved memories could not be read. Try again without clearing app data.');
      if(alive.current){setItems(result.memories);setPhase('ready');}return true;
    }catch(e){if(alive.current){setError(e.message);setPhase('error');}return false;}
  };
  useEffect(()=>{alive.current=true;load();return()=>{alive.current=false;};},[request]);
  const add=async event=>{
    event.preventDefault();if(locked.current)return;
    const input=event.currentTarget.elements.content,content=input.value.trim();
    if(!content){setError('Enter something to remember.');input.focus();return;}
    locked.current=true;setBusy(true);setError('');setNotice('');
    try{const result=await request('/memories',{method:'POST',body:JSON.stringify({content})});
      if(!result.memory||typeof result.memory.id!=='string'||result.memory.content!==content)throw Error('The save was not confirmed. Refresh before trying again.');
      if(!alive.current)return;
      input.value='';setQuery('');setNotice(local?'Saved on this device.':'Saved to your signed-in account.');await load();
    }catch(e){if(alive.current)setError(e.message);}finally{locked.current=false;if(alive.current)setBusy(false);}
  };
  const remove=async id=>{
    if(locked.current)return;locked.current=true;setBusy(true);setError('');setNotice('');
    try{await request(`/memories/${encodeURIComponent(id)}`,{method:'DELETE'});await load();}
    catch(e){if(alive.current)setError(e.message);}finally{locked.current=false;if(alive.current)setBusy(false);}
  };
  const matches=items.filter(item=>item.content.toLowerCase().includes(query.trim().toLowerCase()));
  return <section className="workspace memory-workspace"><p className="eyebrow">{local?'ON-DEVICE MEMORY':'ACCOUNT MEMORY'}</p><h1>Your context, under your control.</h1>
    <p>{local?'These memories stay on this phone. Memories saved in your account appear after sign-in to the same cloud workspace.':'These memories belong to your signed-in account. On-device memories remain separate.'}</p>
    <p>Use Remember to save a detail explicitly. Ordinary chats do not automatically become saved memories.</p>
    <form noValidate className="memory-add" onSubmit={add}><input name="content" maxLength={4000} aria-label="Something ICARUS should remember" placeholder="Something ICARUS should remember" disabled={busy} required/><button className="primary" disabled={busy||phase!=='ready'}>Remember</button></form>
    {notice&&<p role="status">{notice}</p>}{error&&<div className="error" role="alert">{error}</div>}
    {phase==='ready'&&items.length>0&&<div className="workspace-filter">
      <input ref={searchField} type="text" aria-label="Search memories" placeholder="Search memories" value={query} onChange={event=>setQuery(event.target.value)}/>
      {query&&<button className="secondary" onClick={()=>{setQuery('');searchField.current?.focus();}}>Clear search</button>}
    </div>}
    <div className="memory-list" aria-busy={phase==='loading'}>{phase==='loading'?<p role="status">Loading saved memories…</p>:phase==='ready'&&items.length===0?<div className="empty">{local?'No memories saved on this device yet.':'No memories saved in this account yet.'}</div>:null}
      {phase==='ready'&&items.length>0&&matches.length===0&&<p role="status">No matching memories.</p>}
      {phase==='ready'&&matches.map(item=><article key={item.id}><p>{item.content}</p><div className="workspace-shortcuts">
        {onStartChat&&<button disabled={busy} onClick={()=>onStartChat(item.content)} aria-label={`Use in Chat: ${item.content}`}>Use in Chat</button>}
        <button disabled={busy} onClick={()=>remove(item.id)} aria-label={`Forget ${item.content}`}>Forget</button>
      </div></article>)}
    </div><button className="secondary" disabled={busy||phase==='loading'} onClick={load}>Refresh memories</button>
    {local&&<IntelligenceMode/>}
  </section>;
}
