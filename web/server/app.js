import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { hashPassword, issueToken, verifyPassword, verifyToken } from './auth.js';
import { interpretCommand } from './commands.js';

const json = (res, status, body) => { res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' }); res.end(JSON.stringify(body)); };
const body = async req => { const chunks=[]; for await (const chunk of req) chunks.push(chunk); if (Buffer.concat(chunks).length > 1_000_000) throw new Error('Request too large'); return JSON.parse(Buffer.concat(chunks).toString() || '{}'); };
const bearer = req => req.headers.authorization?.replace(/^Bearer\s+/i, '');
const safeUser = user => ({ id:user.id, email:user.email, name:user.name });
const systemPrompt = `You are ICARUS: Intelligent Companion for Assistance, Reasoning, Understanding, and Support. Be warm, direct, concise, capable, and safety-conscious. Answer the user's actual question before adding detail. Never flatter reflexively or agree with a claim merely to please the user. Distinguish facts, estimates, and uncertainty; say when you do not know. Never invent sources, memories, device state, completed actions, or integration results. Never claim a phone or external action succeeded unless the native bridge or connected service confirms it. Ask for confirmation before consequential actions. Use remembered context only when it is relevant.`;

async function assistantReply(messages, memories, env, fetchImpl) {
  const key = env.ICARUS_AI_API_KEY;
  if (!key) return 'Cloud AI is not configured on this server yet. Your conversation and memory are working, and the Android app can continue using its local model.';
  const endpoint = (env.ICARUS_AI_BASE_URL || 'https://api.openai.com/v1').replace(/\/$/, '') + '/chat/completions';
  const context = memories.slice(-20).map(m => `- ${m.content}`).join('\n');
  const response = await fetchImpl(endpoint, { method:'POST', headers:{ authorization:`Bearer ${key}`, 'content-type':'application/json' }, body:JSON.stringify({ model:env.ICARUS_AI_MODEL || 'gpt-5-mini', messages:[{role:'system',content:`${systemPrompt}\nRemembered context:\n${context || '(none)'}`}, ...messages.slice(-30).map(({role,content})=>({role,content}))] }) });
  if (!response.ok) throw new Error(`AI provider returned ${response.status}`);
  const result = await response.json();
  return result.choices?.[0]?.message?.content || 'I could not form a response.';
}

export function createHandler({ store, env=process.env, publicDir, fetchImpl=fetch }) {
  const secret = env.ICARUS_SESSION_SECRET || (env.NODE_ENV === 'test' ? 'test-secret-at-least-32-characters' : '');
  if (secret.length < 32) throw new Error('ICARUS_SESSION_SECRET must be at least 32 characters.');
  const authenticated = async req => { const id=verifyToken(bearer(req),secret); if(!id) return null; return (await store.read()).users.find(u=>u.id===id)||null; };
  return async (req,res) => {
    try {
      const url=new URL(req.url,'http://localhost'); const path=url.pathname;
      if (path==='/api/health') return json(res,200,{ok:true,service:'icarus-api',base44:false});
      if (path==='/api/auth/register' && req.method==='POST') { const input=await body(req); const email=String(input.email||'').trim().toLowerCase(); if(!email.includes('@')) return json(res,400,{error:'valid_email_required'}); let created; await store.update(data=>{ if(data.users.some(u=>u.email===email)) throw Object.assign(new Error('Account exists'),{status:409}); created={id:randomUUID(),email,name:String(input.name||'Michael').trim().slice(0,80),passwordHash:hashPassword(input.password),createdAt:new Date().toISOString()}; data.users.push(created); }); return json(res,201,{token:issueToken(created.id,secret),user:safeUser(created)}); }
      if (path==='/api/auth/login' && req.method==='POST') { const input=await body(req); const user=(await store.read()).users.find(u=>u.email===String(input.email||'').trim().toLowerCase()); if(!user||!verifyPassword(input.password,user.passwordHash)) return json(res,401,{error:'invalid_credentials'}); return json(res,200,{token:issueToken(user.id,secret),user:safeUser(user)}); }
      const user=await authenticated(req); if(path.startsWith('/api/')&&!user) return json(res,401,{error:'authentication_required'});
      if(path==='/api/me') return json(res,200,{user:safeUser(user)});
      if(path==='/api/commands/interpret'&&req.method==='POST') {
        const input=await body(req); const command=String(input.command||'').trim().slice(0,1000);
        if(!command) return json(res,400,{error:'command_required'});
        return json(res,200,await interpretCommand(command,env,fetchImpl));
      }
      if(path==='/api/account'&&req.method==='DELETE') { await store.update(d=>{d.users=d.users.filter(x=>x.id!==user.id);d.conversations=d.conversations.filter(x=>x.userId!==user.id);d.messages=d.messages.filter(x=>x.userId!==user.id);d.memories=d.memories.filter(x=>x.userId!==user.id)}); return json(res,200,{ok:true,deleted:true}); }
      if(path==='/api/memories'&&req.method==='GET') return json(res,200,{memories:(await store.read()).memories.filter(x=>x.userId===user.id)});
      if(path==='/api/memories'&&req.method==='POST') { const input=await body(req); const content=String(input.content||'').trim().slice(0,4000); if(!content)return json(res,400,{error:'content_required'}); const memory={id:randomUUID(),userId:user.id,content,createdAt:new Date().toISOString()}; await store.update(d=>d.memories.push(memory)); return json(res,201,{memory}); }
      if(path.startsWith('/api/memories/')&&req.method==='DELETE') { const id=path.split('/').pop(); await store.update(d=>{d.memories=d.memories.filter(x=>x.userId!==user.id||x.id!==id)}); return json(res,200,{ok:true}); }
      if(path==='/api/conversations'&&req.method==='GET') { const d=await store.read(); return json(res,200,{conversations:d.conversations.filter(x=>x.userId===user.id).sort((a,b)=>String(b.updatedAt||b.createdAt).localeCompare(String(a.updatedAt||a.createdAt))).map(c=>({...c,messages:d.messages.filter(m=>m.conversationId===c.id)}))}); }
      if(path.startsWith('/api/conversations/')&&req.method==='DELETE') { const id=path.split('/').pop(); await store.update(d=>{const owned=d.conversations.some(c=>c.id===id&&c.userId===user.id);if(owned){d.conversations=d.conversations.filter(c=>c.id!==id);d.messages=d.messages.filter(m=>m.conversationId!==id)}}); return json(res,200,{ok:true}); }
      if((path==='/api/chat'||path==='/api/functions/nativeConversationTurn')&&req.method==='POST') { const input=await body(req); const text=String(input.message||input.command||'').trim().slice(0,12000); if(!text)return json(res,400,{error:'message_required'}); let conversationId=input.conversationId; const now=new Date().toISOString(); await store.update(d=>{ const existing=d.conversations.find(c=>c.id===conversationId&&c.userId===user.id); if(!existing){conversationId=randomUUID();d.conversations.push({id:conversationId,userId:user.id,title:text.slice(0,60),createdAt:now,updatedAt:now});}else existing.updatedAt=now; d.messages.push({id:randomUUID(),conversationId,userId:user.id,role:'user',content:text,createdAt:now}); }); const d=await store.read(); const history=d.messages.filter(m=>m.conversationId===conversationId&&m.userId===user.id); const reply=await assistantReply(history,d.memories.filter(m=>m.userId===user.id),env,fetchImpl); await store.update(data=>data.messages.push({id:randomUUID(),conversationId,userId:user.id,role:'assistant',content:reply,createdAt:new Date().toISOString()})); return json(res,200,{conversationId,reply}); }
      if(path==='/api/chat/temporary'&&req.method==='POST') { const input=await body(req); const text=String(input.message||'').trim().slice(0,12000); if(!text)return json(res,400,{error:'message_required'}); const history=Array.isArray(input.history)?input.history.slice(-20).map(x=>({role:x.role==='assistant'?'assistant':'user',content:String(x.content||'').slice(0,12000)})):[]; history.push({role:'user',content:text}); const reply=await assistantReply(history,[],env,fetchImpl); return json(res,200,{temporary:true,reply}); }
      if(path.startsWith('/api/')) return json(res,404,{error:'not_found'});
      if(!publicDir) return json(res,404,{error:'not_found'});
      const requested=path==='/'?'index.html':normalize(path).replace(/^(\.\.(\/|\\|$))+/, ''); const file=join(publicDir,requested); try { const content=await readFile(file); const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.json':'application/json'}; res.writeHead(200,{'content-type':types[extname(file)]||'application/octet-stream'}); return res.end(content); } catch { const content=await readFile(join(publicDir,'index.html')); res.writeHead(200,{'content-type':'text/html'}); return res.end(content); }
    } catch(error) { return json(res,error.status||500,{error:error.status?error.message:'internal_error'}); }
  };
}
