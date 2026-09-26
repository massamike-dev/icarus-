import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { hashPassword, issueToken, verifyPassword, verifyToken } from './auth.js';
import { interpretCommand } from './commands.js';
import {assistantTurn, capabilities} from './assistant.js';
import {conversationService} from './conversation-service.js';
import { isPrivateTester, privateSessionSecret, privateTestConfig } from './private-test.js';
import { privateApkService } from './private-apk.js';
import { entitlementFor } from './entitlements.js';

const json = (res, status, body) => { res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' }); res.end(JSON.stringify(body)); };
const body = async req => { const chunks=[]; for await (const chunk of req) chunks.push(chunk); if (Buffer.concat(chunks).length > 1_000_000) throw new Error('Request too large'); return JSON.parse(Buffer.concat(chunks).toString() || '{}'); };
const bearer = req => req.headers.authorization?.replace(/^Bearer\s+/i, '');
const safeUser = (user,env) => ({ id:user.id, email:user.email, name:user.name, entitlement:entitlementFor(user,env) });
export function createHandler({ store, env=process.env, publicDir, fetchImpl=fetch }) {
  const conversations=conversationService(store);
  const privateTest = privateTestConfig(env);
  const privateApk = privateApkService({ env, fetchImpl });
  const configuredSecret = env.ICARUS_SESSION_SECRET || (!privateTest && env.NODE_ENV === 'test' ? 'test-secret-at-least-32-characters' : '');
  if (configuredSecret.length < 32) throw new Error('ICARUS_SESSION_SECRET must be at least 32 characters.');
  const secret = privateSessionSecret(configuredSecret, privateTest);
  const authenticated = async req => { const id=verifyToken(bearer(req),secret); if(!id) return null; const user=(await store.read()).users.find(u=>u.id===id)||null; return privateTest&&!isPrivateTester(user,privateTest)?null:user; };
  return async (req,res) => {
    try {
      const url=new URL(req.url,'http://localhost'); const path=url.pathname;
      if (path==='/api/health') return json(res,200,{ok:true,service:'icarus-api',base44:false,...(privateTest?{privateTest:true,...(/^[a-f0-9]{40}$/.test(env.RENDER_GIT_COMMIT||'')?{commitSha:env.RENDER_GIT_COMMIT}:{})}:{})});
      if (path==='/api/auth/config'&&req.method==='GET') return json(res,200,{privateTest:Boolean(privateTest),registrationEnabled:!privateTest});
      if (path==='/api/auth/register' && req.method==='POST') { if(privateTest)return json(res,403,{error:'registration_disabled'}); const input=await body(req); const email=String(input.email||'').trim().toLowerCase(); if(!email.includes('@')) return json(res,400,{error:'valid_email_required'}); let created; await store.update(data=>{ if(data.users.some(u=>u.email===email)) throw Object.assign(new Error('Account exists'),{status:409}); created={id:randomUUID(),email,name:String(input.name||'Michael').trim().slice(0,80),passwordHash:hashPassword(input.password),createdAt:new Date().toISOString()}; data.users.push(created); }); return json(res,201,{token:issueToken(created.id,secret),user:safeUser(created,env)}); }
      if (path==='/api/auth/login' && req.method==='POST') { const input=await body(req); const user=(await store.read()).users.find(u=>u.email===String(input.email||'').trim().toLowerCase()); if(!user||(privateTest&&!isPrivateTester(user,privateTest))||!verifyPassword(input.password,user.passwordHash)) return json(res,401,{error:'invalid_credentials'}); return json(res,200,{token:issueToken(user.id,secret),user:safeUser(user,env)}); }
      if(path==='/api/private-apk'||path==='/api/private-apk/metadata'){
        if(!privateTest)return json(res,404,{error:'not_found'});
        if(path==='/api/private-apk'&&req.method==='POST'){
          try{return json(res,201,await privateApk.upload(req));}
          catch(error){res.setHeader('connection','close');throw error;}
        }
        if(!await authenticated(req))return json(res,401,{error:'authentication_required'});
        if(req.method!=='GET')return json(res,405,{error:'method_not_allowed'});
        return path.endsWith('/metadata')?json(res,200,await privateApk.metadata()):await privateApk.download(res);
      }
      const user=await authenticated(req); if(path.startsWith('/api/')&&!user) return json(res,401,{error:'authentication_required'});
      if(path==='/api/capabilities') return json(res,200,capabilities(env));
      if(path==='/api/me') return json(res,200,{user:safeUser(user,env)});
      if(path==='/api/entitlement'&&req.method==='GET') return json(res,200,{entitlement:entitlementFor(user,env)});
      if(path==='/api/commands/interpret'&&req.method==='POST') {
        const input=await body(req), command=String(input.command||'').trim().slice(0,1000);
        if(!command)return json(res,400,{error:'command_required'});
        const produce=async(history,memories)=>{
          if(/^(search (the )?(web|internet)|look up)\b/i.test(command)){
            const turn=await assistantTurn(history,memories,env,fetchImpl,{search:true,voice:true});
            return {...turn,action:'unknown',executionStatus:'not_executed'};
          }
          const interpreted=await interpretCommand(command,env,fetchImpl);
          if(interpreted.action!=='unknown')return {...interpreted,proposal:interpreted,reply:`Requested ${interpreted.action.replaceAll('_',' ')}${interpreted.value?' '+interpreted.value:''}. Execution is not yet confirmed.`};
          const turn=await assistantTurn(history,memories,env,fetchImpl,{voice:true});
          return {...turn,...interpreted,reply:turn.reply};
        };
        const turn=input.temporary===true?await produce([{role:'user',content:command}],[]):await conversations.savedTurn({userId:user.id,input,text:command,channel:'voice',produce});
        return json(res,200,turn);
      }
      if(/^\/api\/actions\/[^/]+\/result$/.test(path)&&req.method==='POST')return json(res,200,await conversations.reportResult(user.id,path.split('/')[3],await body(req)));
      if(path==='/api/account'&&req.method==='DELETE') { await store.update(d=>{d.users=d.users.filter(x=>x.id!==user.id);d.conversations=d.conversations.filter(x=>x.userId!==user.id);d.messages=d.messages.filter(x=>x.userId!==user.id);d.memories=d.memories.filter(x=>x.userId!==user.id);d.turns=(d.turns||[]).filter(x=>x.userId!==user.id);d.actionRequests=(d.actionRequests||[]).filter(x=>x.userId!==user.id)}); return json(res,200,{ok:true,deleted:true}); }
      if(path==='/api/memories'&&req.method==='GET') return json(res,200,{memories:(await store.read()).memories.filter(x=>x.userId===user.id)});
      if(path==='/api/memories'&&req.method==='POST') { const input=await body(req); const content=String(input.content||'').trim().slice(0,4000); if(!content)return json(res,400,{error:'content_required'}); const memory={id:randomUUID(),userId:user.id,content,createdAt:new Date().toISOString()}; await store.update(d=>d.memories.push(memory)); return json(res,201,{memory}); }
      if(path.startsWith('/api/memories/')&&req.method==='DELETE') { const id=path.split('/').pop(); await store.update(d=>{d.memories=d.memories.filter(x=>x.userId!==user.id||x.id!==id)}); return json(res,200,{ok:true}); }
      if(path==='/api/conversations'&&req.method==='GET') { const d=await store.read(); return json(res,200,{conversations:d.conversations.filter(x=>x.userId===user.id).sort((a,b)=>String(b.updatedAt||b.createdAt).localeCompare(String(a.updatedAt||a.createdAt))).map(c=>({...c,messages:d.messages.filter(m=>m.conversationId===c.id)}))}); }
      if(path.startsWith('/api/conversations/')&&req.method==='DELETE') { const id=path.split('/').pop(); await store.update(d=>{const owned=d.conversations.some(c=>c.id===id&&c.userId===user.id);if(owned){d.conversations=d.conversations.filter(c=>c.id!==id);d.messages=d.messages.filter(m=>m.conversationId!==id);d.turns=(d.turns||[]).map(t=>t.response?.conversationId===id?{userId:t.userId,clientTurnId:t.clientTurnId,fingerprint:t.fingerprint,deleted:true}:t);d.actionRequests=(d.actionRequests||[]).filter(a=>a.conversationId!==id)}}); return json(res,200,{ok:true}); }
      if((path==='/api/chat'||path==='/api/functions/nativeConversationTurn')&&req.method==='POST') {
        const input=await body(req), text=String(input.message||input.command||'').trim().slice(0,12000);
        if(!text)return json(res,400,{error:'message_required'});
        const turn=await conversations.savedTurn({userId:user.id,input,text,channel:'chat',produce:(history,memories)=>assistantTurn(history,memories,env,fetchImpl,{native:input.native===true,search:input.search===true})});
        return json(res,200,turn);
      }
      if(path==='/api/chat/temporary'&&req.method==='POST') { const input=await body(req); const text=String(input.message||'').trim().slice(0,12000); if(!text)return json(res,400,{error:'message_required'}); const history=Array.isArray(input.history)?input.history.slice(-20).map(x=>({role:x.role==='assistant'?'assistant':'user',content:String(x.content||'').slice(0,12000)})):[]; history.push({role:'user',content:text}); const turn=await assistantTurn(history,[],env,fetchImpl,{native:input.native===true,search:input.search===true}); return json(res,200,{temporary:true,...turn}); }
      if(path.startsWith('/api/')) return json(res,404,{error:'not_found'});
      if(!publicDir) return json(res,404,{error:'not_found'});
      const requested=path==='/'?'index.html':normalize(path).replace(/^(\.\.(\/|\\|$))+/, ''); const file=join(publicDir,requested); try { const content=await readFile(file); const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.json':'application/json'}; res.writeHead(200,{'content-type':types[extname(file)]||'application/octet-stream'}); return res.end(content); } catch { const content=await readFile(join(publicDir,'index.html')); res.writeHead(200,{'content-type':'text/html'}); return res.end(content); }
    } catch(error) { return json(res,error.status||500,{error:error.status?error.message:'internal_error'}); }
  };
}
