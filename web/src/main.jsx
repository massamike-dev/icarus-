import React,{useEffect,useState}from'react';
import{createRoot}from'react-dom/client';
import'./styles.css';
import'./mobile.css';
import './dashboard.css';
import {House,CarFront,Mic,MessageSquare,BrainCircuit,Settings2,CalendarDays,BriefcaseBusiness,Wrench,SlidersHorizontal,ArrowRight,ChevronRight,Keyboard,X} from 'lucide-react';
import {api,isLocalMode} from './local-core.js';
import {IntelligenceMode} from './intelligence-mode.jsx';
import {Dashboard} from './dashboard.jsx';
import {Settings, VoiceControls, ScreenBoundary} from './settings';
import {Chat} from './chat';
import {IcarusCompanion} from './icarus-companion';
import {VehicleControls} from './vehicle.jsx';
import {PrivateDownload} from './private-download.jsx';
import {installVoiceControls} from './native-session';
import {captureIntegrationCallback} from './integrations-client.js';
import './glass-pages.css';
const integrationCallback=captureIntegrationCallback();
installVoiceControls();
const NAV=[['home','Home'],['drive','Vehicle'],['voice','Voice'],['chat','Chat'],['memory','Memory'],['settings','Settings']];


function Icon({name}){const Component={home:House,drive:CarFront,voice:Mic,chat:MessageSquare,memory:BrainCircuit,settings:Settings2,calendar:CalendarDays,project:BriefcaseBusiness,tools:Wrench,controls:SlidersHorizontal,arrow:ArrowRight,chevron:ChevronRight,keyboard:Keyboard,clear:X}[name]||Settings2;return <Component aria-hidden="true" strokeWidth={1.7}/>;}
function Auth({onAuth}){
  const[register,setRegister]=useState(false),[error,setError]=useState('');
  const[authConfig,setAuthConfig]=useState({registrationEnabled:false,privateTest:false});
  useEffect(()=>{
    let active=true;
    api('/auth/config').then(config=>{if(active){setAuthConfig(config);if(config.registrationEnabled!==true)setRegister(false);}}).catch(()=>{});
    return()=>{active=false};
  },[]);
  const submit=async e=>{
    e.preventDefault();setError('');
    const form=Object.fromEntries(new FormData(e.currentTarget));
    if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email||'')){setError('Enter a valid email address.');e.currentTarget.email.focus();return}
    if((form.password||'').length<10){setError('Use a password with at least 10 characters.');e.currentTarget.password.focus();return}
    if(register&&!form.name?.trim()){setError('Enter your name.');e.currentTarget.name.focus();return}
    try{const result=await api(`/auth/${register&&authConfig.registrationEnabled?'register':'login'}`,{method:'POST',body:JSON.stringify(form)});localStorage.setItem('icarus_token',result.token);onAuth(result.user)}catch(x){setError(x.message)}
  };
  return <main className="auth"><img src="/brand/icarus-app-icon.png" alt="ICARUS crest"/><p>{authConfig.privateTest?'ICARUS TEST · PRIVATE ACCESS':'I.C.A.R.U.S. COMMAND SYSTEM'}</p><h1>{register?'Create secure access':authConfig.privateTest?'Sign in to ICARUS Test':'Welcome back'}</h1>
    {authConfig.privateTest&&<p>Use your private test sign-in. Your regular ICARUS account and saved chats stay separate.</p>}
    <form noValidate onSubmit={submit}>{register&&<input name="name" placeholder="Name" required/>}<input name="email" type="email" placeholder="Email" required/><input name="password" type="password" placeholder="Password (10+ characters)" minLength="10" required/>{error&&<div className="error">{error.replaceAll('_',' ')}</div>}<button className="primary">{register?'Create account':'Sign in'}<span>→</span></button></form>
    {authConfig.registrationEnabled&&<button className="text-button" onClick={()=>setRegister(!register)}>{register?'I already have an account':'Create an account'}</button>}
    <IntelligenceMode/><p className="legal-links"><a href="/privacy-policy">Privacy</a> · <a href="/account-deletion">Account deletion</a></p>
  </main>;
}

function Memory(){
  const[items,setItems]=useState([]),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const load=()=>api('/memories').then(r=>setItems(r.memories)).catch(e=>setError(e.message));
  useEffect(()=>{load()},[]);
  const add=async e=>{e.preventDefault();if(busy)return;const input=e.currentTarget.content;if(!input.value.trim()){setError('Enter something to remember.');input.focus();return}setBusy(true);setError('');try{await api('/memories',{method:'POST',body:JSON.stringify({content:input.value})});input.value='';await load()}catch(e){setError(e.message)}finally{setBusy(false)}};
  const remove=async id=>{if(busy)return;setBusy(true);setError('');try{await api(`/memories/${id}`,{method:'DELETE'});await load()}catch(e){setError(e.message)}finally{setBusy(false)}};
  return <section className="workspace memory-workspace"><p className="eyebrow">MEMORY CORE</p><h1>Your context, under your control.</h1><form noValidate className="memory-add" onSubmit={add}><input name="content" aria-label="Something ICARUS should remember" placeholder="Something ICARUS should remember" required/><button className="primary" disabled={busy} aria-busy={busy}>Remember</button></form>{error&&<div className="error" role="alert">{error}</div>}<div className="memory-list">{items.length===0&&<div className="empty">No saved memories yet.</div>}{items.map(x=><article key={x.id}><p>{x.content}</p><button disabled={busy} onClick={()=>remove(x.id)} aria-label={`Forget ${x.content}`}>Forget</button></article>)}</div></section>
}
function Legal(){return <main className="legal"><a href={isLocalMode()?'/assets/web/index.html':'/'}>← ICARUS</a><p className="eyebrow">PRIVACY POLICY · EFFECTIVE SEPTEMBER 15, 2026</p><h1>Privacy is part of the structure.</h1><h2>Information ICARUS handles</h2><p>ICARUS handles account details, conversations, memories you choose to save, voice transcripts after a wake event, and feature-specific information such as contacts, location, camera input, Bluetooth devices, and vehicle telemetry when you use those features.</p><h2>How information is used</h2><p>Information is used to provide assistant responses, remember user-approved context, perform requested device actions, support navigation, connect optional accessories, secure accounts, and diagnose failures. Continuous wake audio is processed locally and is not intentionally uploaded. A short command after wake detection may use Android speech recognition.</p><h2>Sharing and processing</h2><p>Local mode keeps conversations and memories on this device and uses the on-device engine when available. If you choose cloud intelligence, prompts may be processed by the configured AI provider. Android, Google Play, Meta, XREAL, mapping, speech-recognition, and hosting providers may process information needed for their enabled features under their own terms. ICARUS does not sell personal information.</p><h2>Security and retention</h2><p>Network traffic uses HTTPS. Passwords are stored as salted scrypt hashes. In local mode, conversations and memories stay in this app’s device storage until you erase local data or uninstall. In cloud mode, conversations and memories are isolated by account and retained until the user deletes them or the account. Operational backups may persist briefly before scheduled expiration.</p><h2>Your choices</h2><p>Permissions can be denied or revoked in Android settings. Optional integrations can be disabled. Memories can be removed individually. You can delete your account and its active conversations and memories from the deletion page.</p><h2>Contact</h2><p>Privacy and deletion questions: <a href="mailto:wennigworks@gmail.com">wennigworks@gmail.com</a>.</p>{isLocalMode()?<p>Remove local conversations in Chat and saved memories in Memory. Cloud account deletion is available when using a cloud account.</p>:<p><a href="/account-deletion">Delete an ICARUS account →</a></p>}</main>}
function DeleteAccount(){const[message,setMessage]=useState('');const remove=async()=>{if(!confirm('Permanently delete this ICARUS account, conversations, and memories?'))return;try{await api('/account',{method:'DELETE'});localStorage.removeItem('icarus_token');setMessage('Account and active stored data deleted.')}catch{setMessage('Sign in to ICARUS first, then return here to delete the account.')}};return <main className="legal"><a href={isLocalMode()?'/assets/web/index.html':'/'}>← ICARUS</a><p className="eyebrow">ACCOUNT DELETION</p><h1>Delete your ICARUS account.</h1><p>This permanently removes the signed-in account, conversations, messages, and saved memories from active storage. Store purchase records retained by Google and short-lived operational backups are governed by their required retention periods.</p><button className="danger" onClick={remove}>Permanently delete my account</button>{message&&<p>{message}</p>}<p>If you cannot sign in, email <a href="mailto:wennigworks@gmail.com?subject=ICARUS%20account%20deletion">wennigworks@gmail.com</a> from the account address and request deletion.</p></main>}
function Overview({page,onNavigate,onStartChat,user}){
  if(page==='home')return <Dashboard onNavigate={onNavigate} onStartChat={onStartChat} user={user} Icon={Icon}/>;
  return <section className="workspace voice-workspace"><p className="eyebrow">VOICE CONTROL</p><h1>Hands free. In control.</h1><p className="workspace-intro">Manage wake listening and speech on your Android device.</p><VoiceControls/></section>;
}
function App(){const path=location.pathname;if(path==='/privacy-policy'||path==='/privacy'||(isLocalMode()&&new URLSearchParams(location.search).get('view')==='privacy'))return <Legal/>;if(path==='/account-deletion')return <DeleteAccount/>;const[user,setUser]=useState(null),[checking,setChecking]=useState(true),[chatDraft,setChatDraft]=useState(''),[page,setPage]=useState(integrationCallback?'settings':'home');useEffect(()=>{api('/me').then(r=>setUser(r.user)).catch(()=>{if(!isLocalMode())localStorage.removeItem('icarus_token')}).finally(()=>setChecking(false))},[]);const navigate=(id,draft='')=>{if(id==='chat')setChatDraft(draft);setPage(id);document.title=`${NAV.find(([key])=>key===id)?.[1]||'Home'} | ICARUS`;window.scrollTo({top:0,behavior:'smooth'})};if(checking)return <div className="loading">ICARUS INITIALIZING</div>;if(!user)return <Auth onAuth={setUser}/>;if(path==="/test-download")return <PrivateDownload/>;return <div className="app-shell" data-page={page}><aside><button className="brand bare" onClick={()=>navigate('home')}><img src="/brand/icarus-app-icon.png" alt=""/><span><b>I.C.A.R.U.S.</b><small>COMMAND SYSTEM</small></span></button><nav aria-label="Primary">{NAV.map(([id,label])=><button key={id} data-destination={id} className={page===id?'active':''} aria-current={page===id?'page':undefined} onClick={()=>navigate(id)}><Icon name={id}/><span>{label}</span></button>)}</nav><div className="system-note"><i></i><span><b>{isLocalMode()?'On-device workspace':'Cloud workspace'}</b><small>{isLocalMode()?'Your device · your control':'Account connected'}</small></span></div></aside><main><header className="app-header"><span className="mobile-wordmark">ICARUS</span><div className="mode"><i></i>{isLocalMode()?'ON DEVICE':'CLOUD SESSION'}</div><button className="profile" onClick={()=>{if(isLocalMode())navigate('settings');else{localStorage.removeItem('icarus_token');setUser(null)}}} title={isLocalMode()?'Open settings':'Sign out'} aria-label={isLocalMode()?'Open settings':'Sign out'}>{user.name?.split(/\s+/).map(x=>x[0]).join('').slice(0,2).toUpperCase()||'IC'}</button></header><ScreenBoundary key={page}>{page==='settings'?<Settings user={user} integrationCallback={integrationCallback} onSignOut={()=>{localStorage.removeItem('icarus_token');setUser(null)}}/>:page==='chat'?<Chat initialDraft={chatDraft}/>:page==='memory'?<Memory/>:page==='drive'?<VehicleControls onStartChat={draft=>navigate('chat',draft)}/>:<Overview page={page} onNavigate={navigate} onStartChat={draft=>navigate('chat',draft)} user={user}/>}</ScreenBoundary></main>{page!=='home'&&<IcarusCompanion onNavigate={navigate}/>}</div>}
createRoot(document.getElementById('root')).render(<App/>);
