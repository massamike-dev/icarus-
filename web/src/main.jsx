import React,{useState}from'react';import{createRoot}from'react-dom/client';import'./styles.css';

const NAV=[['home','Command'],['drive','Vehicle'],['voice','Voice'],['chat','Chat'],['memory','Memory'],['settings','Settings']];
const COPY={
 home:{k:'SYSTEM OVERVIEW',title:'Ready when you are, Michael.',sub:'One command surface for your road, work, home, and memory.',action:'Speak to ICARUS'},
 drive:{k:'VEHICLE MODE',title:'The road is yours.',sub:'Navigation, hands-free control, and vehicle status share one focused view.',action:'Start vehicle mode'},
 voice:{k:'VOICE CONTROL',title:'Wake without touching.',sub:'Tune wake phrase, voice response, and screen-off listening.',action:'Test wake phrase'},
 chat:{k:'ICARUS CHAT',title:'Ask. Build. Move.',sub:'A direct workspace for questions, plans, and commands.',action:'Start a conversation'},
 memory:{k:'MEMORY CORE',title:'Your context, carried forward.',sub:'Review what ICARUS remembers and decide what stays.',action:'Review memory'},
 settings:{k:'SYSTEM SETTINGS',title:'Make it yours.',sub:'Manage engines, devices, privacy, and connections.',action:'Open settings'}
};
function Icon({name}){const paths={home:'M4 11 12 4l8 7v9h-5v-6H9v6H4z',drive:'M5 17h14l-2-7H7zM7 17v3m10-3v3M8 13h8',voice:'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zm-7 9a7 7 0 0 0 14 0M12 19v3',chat:'M4 5h16v11H9l-5 4z',memory:'M8 3h8v3h3v12h-3v3H8v-3H5V6h3zm1 5h6v8H9z',settings:'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm0-5v2m0 14v2M3 12h2m14 0h2M5 5l2 2m10 10 2 2M19 5l-2 2M7 17l-2 2'};return <svg viewBox="0 0 24 24" aria-hidden="true"><path d={paths[name]}/></svg>}
function App(){const[page,setPage]=useState('home');const[awake,setAwake]=useState(false);const c=COPY[page];return <div className="app-shell">
 <aside><a className="brand" href="#home" onClick={()=>setPage('home')} aria-label="ICARUS command home"><img src="/brand/icarus-crest.svg"/><span><b>I.C.A.R.U.S.</b><small>COMMAND SYSTEM</small></span></a><nav aria-label="Primary">{NAV.map(([id,label])=><button key={id} className={page===id?'active':''} onClick={()=>setPage(id)}><Icon name={id}/><span>{label}</span></button>)}</nav><div className="system-note"><i></i><span><b>Local systems online</b><small>Android remains independent</small></span></div></aside>
 <main><header><div className="mode"><i></i>SECURE SESSION</div><button className="profile" aria-label="Open Michael's profile">MW</button></header>
 <section className="hero"><div className="copy"><p>{c.k}</p><h1>{c.title}</h1><div className="rule"></div><h2>{c.sub}</h2><button className="primary" onClick={()=>setAwake(!awake)}>{awake?'Listening…':c.action}<span>→</span></button></div>
 <div className={'core '+(awake?'awake':'')}><div className="orbit orbit-a"></div><div className="orbit orbit-b"></div><div className="core-center"><span>W</span><small>{awake?'LISTENING':'STANDBY'}</small></div><p>ICARUS CORE</p></div></section>
 <section className="cards"><article><span>01 / VOICE</span><h3>Wake listener</h3><p>Screen-off voice service is managed by the Android app.</p><b>Native channel ↗</b></article><article><span>02 / ROAD</span><h3>Vehicle link</h3><p>Navigation and OBD connection are ready for web service wiring.</p><b>Integration staged ↗</b></article><article><span>03 / MEMORY</span><h3>Context vault</h3><p>Memory controls will move behind authenticated API access.</p><b>Backend pending ↗</b></article></section>
 </main></div>}
createRoot(document.getElementById('root')).render(<App/>);
