import React,{useState} from 'react';
import './driving-preview.css';

// Intentionally isolated from navigation and telemetry. These coordinates are
// fictional design artwork, never a route to be followed on a real road.
export function DrivingPreview(){
 const [open,setOpen]=useState(false),[line,setLine]=useState(true),[view,setView]=useState('map');
 return <section className="drive-design" aria-labelledby="drive-design-title">
  <div className="drive-design-heading"><div><p className="eyebrow">NEXT PATCH · DESIGN PREVIEW</p><h2 id="drive-design-title">The road. At a glance.</h2></div><button className="secondary" aria-expanded={open} onClick={()=>setOpen(!open)}>{open?'Close graphics preview':'Preview driving graphics'}</button></div>
  <p>Racing-game-inspired instruments, a high-contrast map and a cyan route line. This parked preview uses fictional roads and sample values. Live route geometry is not connected.</p>
  {open&&<><div className="drive-design-tools"><button aria-pressed={view==='map'} onClick={()=>setView('map')}>Phone map</button><button aria-pressed={view==='hud'} onClick={()=>setView('hud')}>Glasses HUD layout</button><label><input type="checkbox" checked={line} onChange={e=>setLine(e.target.checked)}/> Show sample route line</label></div>
   <div className={`race-preview race-preview-${view}`} role="img" aria-label="Fictional driving display preview. Sample speed 42 miles per hour; right turn in 800 feet. Not live navigation.">
    <div className="race-preview-label">DESIGN DEMO · NOT LIVE NAVIGATION</div>
    {view==='map'&&<svg className="race-map" viewBox="0 0 1000 560" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <rect width="1000" height="560" fill="#0a1622"/>
      <path d="M0 50 Q250 170 360 50 T740 90 L1000 0 V160 Q750 260 550 180 T0 210Z" fill="#102e39"/>
      <g fill="#132533" stroke="#1a3342" strokeWidth="2"><path d="M80 270h180v100H80zM320 260h130v110H320zM570 250h130v110H570zM770 220h180v130H770zM70 440h200v100H70zM330 440h160v110H330zM580 440h120v110H580zM780 430h160v120H780z"/></g>
      <g fill="none" stroke="#344958" strokeWidth="16" strokeLinejoin="round"><path d="M-30 410H1030M520 600V215H1030M-10 240H300V-20M740 600V-20M40 600V-20M-10 90H1030"/></g>
      <g fill="none" stroke="#7c91a0" strokeOpacity=".3" strokeWidth="2"><path d="M-30 410H1030M520 600V215H1030M-10 240H300V-20M740 600V-20M40 600V-20"/></g>
      {line&&<g fill="none" strokeLinejoin="round" strokeLinecap="round"><path d="M520 570V260Q520 215 565 215H890" stroke="#35d4fa" strokeOpacity=".15" strokeWidth="34"/><path d="M520 570V260Q520 215 565 215H890" stroke="#49ddff" strokeWidth="9"/><path d="m520 475 0-20m0-55 0-20m0-55 0-20m80-90 20 0m65 0 20 0" stroke="#e9fcff" strokeWidth="4"/></g>}
      <path d="m520 378-16 37 16-8 16 8z" fill="#fff" stroke="#49ddff" strokeWidth="3"/>
      <g fill="#829daa" fontSize="13" fontFamily="sans-serif" letterSpacing="3"><text x="100" y="397">DEMO AVENUE</text><text x="580" y="196">SAMPLE ROAD</text></g>
    </svg>}
    <div className="race-turn"><span className="race-arrow">↱</span><div><b>800 <small>FT</small></b><span>Right onto Sample Road</span></div></div>
    {view==='hud'&&<div className="race-clear-center">FORWARD VIEW KEPT CLEAR<span>Layout preview only · stationary evaluation</span></div>}
    <div className="race-speed"><span>SAMPLE SPEED</span><strong>42</strong><span>MPH</span><div className="race-revs"><i/><i/><i/><i/><i/><i/><i/><i/></div></div>
    <div className="race-trip"><span>DEMO ROUTE</span><b>12 <small>MIN</small></b><span>4.8 MI · SAMPLE DATA</span></div>
   </div><p className="settings-muted">The cyan line represents a sample route, not a racing line, braking advice or a lane-position instruction. Real guidance continues in Maps. XREAL remains a stationary prototype.</p></>}
 </section>;
}
