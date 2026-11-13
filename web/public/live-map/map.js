import {usableFix,parseRoute,projectRoute,routeUrl,meters} from './route-core.js';
const $=id=>document.getElementById(id),L=window.L;
let active=false,map=null,marker=null,accuracy=null,line=null,goal=null,goalMarker=null,fix=null,route=null,follow=true,pending=null,generation=0,lastRequest=-Infinity,routeStart=null,tileErrors=0;
const message=t=>{$('status').textContent=t;};
const fresh=()=>usableFix(fix);
const moving=()=>fresh()&&Number.isFinite(fix.speed)&&fix.speed>1.5;
function cancelRequest(){generation++;pending?.abort();pending=null;}
function eraseRoute(){cancelRequest();if(line)map.removeLayer(line);line=null;route=null;routeStart=null;$('remaining').textContent='No route selected';$('estimate').textContent='';$('failure').textContent='';}
function update(){
 if(!active)return;
 const good=fresh();$('map').classList.toggle('stale',!good);
 $('speed').textContent=good&&Number.isFinite(fix.speed)&&fix.speed>=0?String(Math.round(fix.speed*2.236936)):'—';
 $('accuracy').textContent=good?`GPS accuracy ±${Math.round(fix.accuracy)} m`:'GPS not precise or fresh enough';
 $('calculate').disabled=!good||!goal||Boolean(pending)||moving();$('clear').disabled=!goal||moving();
 if(!good){$('remaining').textContent=route?'Route retained · GPS unavailable':'Waiting for a precise location';message('Waiting for a fresh GPS fix. Guidance and speed are unavailable.');return;}
 if(route){const progress=projectRoute(route,[fix.longitude,fix.latitude]),off=progress.distance>Math.max(60,fix.accuracy*2);$('map').classList.toggle('offroute',off);
  if(off){message('Off the planned route. Stop safely before calculating a new route.');$('remaining').textContent='Off route';}
  else if(progress.remaining<35&&meters([fix.longitude,fix.latitude],route.points.at(-1))<50){message('Near the route endpoint. Check your destination surroundings.');$('remaining').textContent='Near destination';}
  else {message(moving()?'Following GPS · destination controls locked while moving':'Route ready. Follow GPS or pan to inspect.');$('remaining').textContent=`${(progress.remaining/1609.344).toFixed(1)} mi along remaining line`;}
 }else message(moving()?'GPS live · stop safely to select a destination':'GPS live. Tap a road to choose your destination.');
}
function stop(notify=true){active=false;cancelRequest();fix=null;goal=null;route=null;line=null;marker=null;accuracy=null;goalMarker=null;map?.remove();map=null;$('session').hidden=true;$('intro').hidden=false;$('start').disabled=false;if(notify)window.IcarusMapHost?.stop();}
function startSession(){
 if(active)return;
 if(!window.IcarusMapHost){$('intro').querySelector('p').textContent='Open this map in the updated ICARUS Android app to use foreground GPS.';return;}
 active=true;follow=true;tileErrors=0;$('intro').hidden=true;$('session').hidden=false;$('failure').textContent='';
 map=L.map('map',{zoomControl:true,attributionControl:true}).setView([39,-98],3);
 L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,updateWhenIdle:true,keepBuffer:0,attribution:'© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>',referrerPolicy:'strict-origin-when-cross-origin'}).on('tileerror',()=>{if(active&&++tileErrors>=3)$('failure').textContent='Map tiles unavailable. Check internet access; the background may be incomplete.';}).addTo(map);
 map.on('dragstart',()=>{follow=false;});
 map.on('click',e=>{if(!fresh()||moving()||pending)return;eraseRoute();goal=[e.latlng.lng,e.latlng.lat];if(goalMarker)map.removeLayer(goalMarker);goalMarker=L.circleMarker(e.latlng,{radius:9,color:'#ecc77e',fillOpacity:.9}).addTo(map);$('destination').textContent=`Destination: ${goal[1].toFixed(5)}, ${goal[0].toFixed(5)}. Review, then calculate.`;update();});
 update();
};
$('start').onclick=()=>{if(active)return;if(!window.IcarusMapHost){message('Open the updated Android app.');return;}$('start').disabled=true;window.IcarusMapHost.begin();};
$('stop').onclick=()=>stop();$('close').onclick=()=>{stop();window.IcarusMapHost?.close();};
$('follow').onclick=()=>{follow=true;if(fresh())map.setView([fix.latitude,fix.longitude],Math.max(map.getZoom(),16));};
$('clear').onclick=()=>{eraseRoute();goal=null;if(goalMarker)map.removeLayer(goalMarker);goalMarker=null;$('destination').textContent='Tap a road on the map to select your destination.';update();};
$('calculate').onclick=async()=>{
 if(!active||!fresh()||!goal||pending||moving())return;
 if(performance.now()-lastRequest<5000){$('failure').textContent='Wait a few seconds before requesting another route.';return;}
 eraseRoute();lastRequest=performance.now();const token=++generation,controller=new AbortController();pending=controller;
 const start=[fix.longitude,fix.latitude],destination=[...goal];let timeout=setTimeout(()=>controller.abort(),15000);update();message('Calculating a road route…');
 try{const response=await fetch(routeUrl(start,destination),{signal:controller.signal,credentials:'omit',referrerPolicy:'strict-origin-when-cross-origin'});
  if(!response.ok)throw Error('Routing service unavailable. Try again later.');
  const text=await response.text();if(text.length>4000000)throw Error('Route is too large. Choose a closer destination.');const result=parseRoute(JSON.parse(text));
  if(!active||token!==generation)return;
  if(!fresh()||meters(start,[fix.longitude,fix.latitude])>100)throw Error('Location changed while routing. Stop safely and calculate again.');
  route=result;routeStart=start;line=L.polyline(result.points.map(p=>[p[1],p[0]]),{color:'#13badf',weight:7,opacity:.95,lineCap:'round'}).addTo(map);
  map.fitBounds(line.getBounds(),{padding:[35,35],maxZoom:17});follow=false;
  $('estimate').textContent=`Planned ${(result.distance/1609.344).toFixed(1)} mi · ${Math.ceil(result.duration/60)} min estimate (no traffic)`;
 }catch(e){if(active&&token===generation)$('failure').textContent=e.name==='AbortError'?'Routing timed out. No route was added.':e.message;}
 finally{clearTimeout(timeout);if(token===generation){pending=null;update();}}
};
window.icarusMapLocation=value=>{
 if(value.state==='started'){startSession();return;}
 if(value.state==='denied'&&!active){$('start').disabled=false;$('intro').querySelector('p').textContent='Location permission was denied. Allow location for ICARUS, then start again.';return;}
 if(value.state==='stopped'){stop(false);return;}
 if(!active)return;
 if(value.state==='fix'){fix=value;if(fresh()){
  const pos=[fix.latitude,fix.longitude];if(!marker){marker=L.circleMarker(pos,{radius:8,color:'#fff',weight:3,fillColor:'#137df5',fillOpacity:1}).addTo(map);accuracy=L.circle(pos,{radius:fix.accuracy,weight:1,color:'#4faaff',fillOpacity:.08}).addTo(map);map.setView(pos,16);}else{marker.setLatLng(pos);accuracy.setLatLng(pos).setRadius(fix.accuracy);if(follow)map.panTo(pos,{animate:false});}
 }}else{fix=null;$('failure').textContent=value.state==='denied'?'Location permission denied. Stop and restart after granting it.':value.state==='disabled'?'Device location is off. Enable location, then restart.':'';}
 update();
};
setInterval(update,1000);
document.addEventListener('visibilitychange',()=>{if(document.hidden&&active)stop();});
window.addEventListener('pagehide',()=>stop());
