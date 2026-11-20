import React,{useEffect,useState} from 'react';
import {CornerUpRight, Navigation} from 'lucide-react';
import './driving-preview.css';

const settleMs = 500; // Matches the forward-settle delay in HeadMotionVisibility.cs.
const routeSteps = [
 {x: 520, y: 390, heading: 0, distance: 800, speed: 42},
 {x: 520, y: 344, heading: 0, distance: 500, speed: 38},
 {x: 520, y: 298, heading: 0, distance: 200, speed: 26},
 {x: 533, y: 230, heading: 45, distance: 0, speed: 16},
 {x: 586, y: 215, heading: 90, distance: null, speed: 26},
 {x: 624, y: 215, heading: 90, distance: null, speed: 33},
 {x: 668, y: 215, heading: 90, distance: null, speed: 0},
];
const lastStep = routeSteps.length - 1;

// Intentionally isolated from navigation and telemetry. These coordinates are
// fictional design artwork, never a route to be followed on a real road.
export function DrivingPreview({initiallyOpen = false} = {}){
 const [open,setOpen]=useState(initiallyOpen),[line,setLine]=useState(true),[view,setView]=useState('map');
 const [pose, setPose] = useState('forward');
 const [tracking, setTracking] = useState(true);
 const [settled, setSettled] = useState(true);
 const [step, setStep] = useState(0);
 const [playing, setPlaying] = useState(false);
 const hud = view === 'hud';
 const suppressed = hud && (!tracking || pose !== 'forward' || !settled);
 const sample = routeSteps[step];
 const arrived = step === lastStep;
 const maneuver = arrived ? 'Arrived at Sample Road' : sample.distance === null ? 'Continue on Sample Road' : 'Right onto Sample Road';

 useEffect(() => {
  if (!open || !playing || arrived) return;
  const timer = window.setTimeout(() => setStep(step + 1), 1100);
  return () => window.clearTimeout(timer);
 }, [open, playing, arrived, step]);

 function togglePlayback() {
  if (arrived) {
   setStep(0);
   setPlaying(true);
   return;
  }
  setPlaying(!playing);
 }

 useEffect(() => {
  if (!open || !hud || !tracking || pose !== 'forward' || settled) return;
  const timer = window.setTimeout(() => setSettled(true), settleMs);
  return () => window.clearTimeout(timer);
 }, [open, hud, tracking, pose, settled]);

 function selectPose(next) {
  if (next === pose) return;
  setSettled(false);
  setPose(next);
 }

 function toggleTracking() {
  setSettled(false);
  setTracking(!tracking);
 }

 const status = !tracking ? 'Tracking lost. HUD hidden.'
  : pose !== 'forward' ? `Looking ${pose}. HUD hidden.`
  : !settled ? 'Facing forward. Waiting for the HUD to return.'
  : 'Facing forward. HUD visible.';

 return <section className="drive-design" aria-labelledby="drive-design-title">
  <div className="drive-design-heading"><h2 id="drive-design-title">Vehicle graphics preview</h2><button className="secondary" aria-expanded={open} onClick={()=>setOpen(!open)}>{open?'Close graphics preview':'Preview driving graphics'}</button></div>
  <p>Explore the phone map and test how the glasses HUD responds to simulated head turns and tracking loss. Both views use sample data.</p>
  {open&&<><div className="drive-design-tools" role="group" aria-label="Preview display"><button aria-pressed={!hud} onClick={()=>setView('map')}>Phone map</button><button aria-pressed={hud} onClick={()=>setView('hud')}>Glasses HUD layout</button>{!hud&&<label><input type="checkbox" checked={line} onChange={e=>setLine(e.target.checked)}/> Show sample route line</label>}</div>
   {hud&&<div className="drive-motion">
    <div className="drive-design-tools" role="group" aria-label="Simulated head position">
     <button aria-pressed={pose==='left'} onClick={()=>selectPose('left')}>Look left</button>
     <button aria-pressed={pose==='forward'} onClick={()=>selectPose('forward')}>Look forward</button>
     <button aria-pressed={pose==='right'} onClick={()=>selectPose('right')}>Look right</button>
     <button onClick={toggleTracking}>{tracking?'Simulate tracking loss':'Restore tracking'}</button>
    </div>
    <p role="status">{status}</p>
   </div>}
   <div className="drive-playback">
    <button className="secondary" onClick={togglePlayback}>{arrived ? 'Replay sample route' : playing ? 'Pause sample route' : step ? 'Resume sample route' : 'Play sample route'}</button>
    <progress value={step} max={lastStep} aria-label="Sample route progress"/>
   </div>
   <div className={`race-preview race-preview-${view}`} data-pose={hud ? pose : undefined} role="img" aria-label={suppressed ? 'Sample glasses view with instruments hidden' : `Sample speed ${sample.speed} miles per hour. ${maneuver}${sample.distance ? ` in ${sample.distance} feet` : ''}.`}>
    <div className="race-preview-label">{hud ? 'GLASSES SIMULATION' : 'SAMPLE MAP · NOT LIVE NAVIGATION'}</div>
    {view==='map'&&<svg className="race-map" viewBox="0 0 1000 560" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <rect width="1000" height="560" fill="#101d29"/>
      <path d="M-20 106Q135 123 249 58T495 57T755 67T1020 37" fill="none" stroke="#294c5e" strokeWidth="71"/>
      <path d="M-20 106Q135 123 249 58T495 57T755 67T1020 37" fill="none" stroke="#173747" strokeWidth="66"/>
      <g fill="#16332f" stroke="#21413b" strokeWidth="2">
       <path d="M331 252h112v73l-29 52h-83zM576 252h137v80l-47 47h-90zM775 420h194v100H775zM70 292h183v84H70z"/>
      </g>
      <path d="M70 140V560M176 140V560M305 126V560M440 112V560M650 118V560M740 108V560M873 111V560M960 110V560M0 157H1000M0 277H1000M0 342H1000M0 474H1000M0 521H1000M740 390L902 275" fill="none" stroke="#263b4c" strokeWidth="5"/>
      <path d="M-30 410H1030M520 600V260Q520 215 565 215H1030M-10 240H305V-20M740 600V-20" fill="none" stroke="#3a5366" strokeWidth="15" strokeLinejoin="round"/>
      <path d="M-30 410H1030M520 600V260Q520 215 565 215H1030M-10 240H305V-20M740 600V-20" fill="none" stroke="#8ca9b7" strokeOpacity=".38" strokeWidth="1.5" strokeDasharray="6 8"/>
      {line&&<g fill="none" strokeLinejoin="round" strokeLinecap="round">
       <path d="M520 463V390" stroke="#4a7a8b" strokeWidth="6" strokeDasharray="2 10"/>
       <path d="M520 390V260Q520 215 565 215H668" stroke="#52d9ff" strokeOpacity=".22" strokeWidth="27"/>
       <path d="M520 390V260Q520 215 565 215H668" stroke="#49ddff" strokeWidth="9"/>
       <path d="M520 390V260Q520 215 565 215H668" stroke="#c5f8ff" strokeOpacity=".77" strokeWidth="2"/>
       <circle cx="668" cy="215" r="10" fill="#102735" stroke="#adefff" strokeWidth="3"/>
      </g>}
      <g className="race-position" style={{transform: `translate(${sample.x}px, ${sample.y}px) rotate(${sample.heading}deg)`}}>
       <circle r="28" fill="#50d9ff" fillOpacity=".15"/>
       <circle r="21" fill="#102c44" stroke="#49ddff" strokeWidth="2"/>
       <path d="m0 -14-10 22 10-5 10 5z" fill="#e9fcff"/>
      </g>
      <g fill="#97b1c2" fontSize="13" fontFamily="system-ui, sans-serif" fontWeight="650" letterSpacing="1.7"><text x="365" y="398">DEMO AVENUE</text><text x="553" y="199">SAMPLE ROAD</text><text x="581" y="311">HARBOR PARK</text></g>
    </svg>}
    {hud&&<div className="race-horizon" aria-hidden="true"><div className="race-road"/></div>}
    <div className="race-instruments" data-hidden={suppressed} data-tracking={tracking} aria-hidden="true">
     <div className="race-turn">
      {sample.distance === null ? <Navigation className="race-arrow"/> : <CornerUpRight className="race-arrow" strokeWidth={2.5}/>}
      <div><b>{sample.distance > 0 ? <>{sample.distance} <small>FT</small></> : arrived ? 'Arrived' : sample.distance === 0 ? 'Turn now' : 'Continue'}</b><span>{maneuver}</span></div>
     </div>
     <div className="race-speed"><span>DEMO SPEED</span><div><strong>{sample.speed}</strong><small>MPH</small></div></div>
     <div className="race-trip"><Navigation size={17}/><div><b>{(12 - step * 2)} <small>MIN</small></b><span>{((lastStep - step) * .8).toFixed(1)} MI REMAINING</span></div></div>
    </div>
   </div></>}
 </section>;
}
