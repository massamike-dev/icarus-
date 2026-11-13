export const validPoint=p=>Array.isArray(p)&&p.length>=2&&Number.isFinite(p[0])&&Number.isFinite(p[1])&&Math.abs(p[0])<=180&&Math.abs(p[1])<=85;
export function usableFix(f,now=Date.now()){
 return f?.state==='fix'&&validPoint([f.longitude,f.latitude])&&Number.isFinite(f.accuracy)&&f.accuracy>=0&&f.accuracy<=75&&Number.isFinite(f.timestamp)&&now-f.timestamp>=0&&now-f.timestamp<=15000;
}
export function meters(a,b){
 const r=Math.PI/180,dl=(b[1]-a[1])*r,dn=(b[0]-a[0])*r;
 const h=Math.sin(dl/2)**2+Math.cos(a[1]*r)*Math.cos(b[1]*r)*Math.sin(dn/2)**2;
 return 6371000*2*Math.asin(Math.sqrt(Math.min(1,h)));
}
export function parseRoute(data){
 const route=data?.routes?.[0],points=route?.geometry?.coordinates;
 if(data?.code!=='Ok'||route?.geometry?.type!=='LineString'||!Array.isArray(points)||points.length<2||points.length>30000||!points.every(validPoint)||!Number.isFinite(route.distance)||route.distance<0||!Number.isFinite(route.duration)||route.duration<0)throw Error('Routing returned no usable road geometry. Choose a reachable road and retry.');
 if(!Array.isArray(data.waypoints)||data.waypoints.length!==2||data.waypoints.some(w=>!Number.isFinite(w.distance)||w.distance>250))throw Error('A selected point is too far from a routable road. Choose a nearby road.');
 const cumulative=[0];for(let i=1;i<points.length;i++)cumulative.push(cumulative[i-1]+meters(points[i-1],points[i]));
 return {points,cumulative,distance:route.distance,duration:route.duration};
}
export function projectRoute(route,point){
 const lat=point[1]*Math.PI/180,kx=111320*Math.cos(lat),ky=111320;
 let best={distance:Infinity,along:0};
 for(let i=1;i<route.points.length;i++){
  const a=route.points[i-1],b=route.points[i],ax=(a[0]-point[0])*kx,ay=(a[1]-point[1])*ky,bx=(b[0]-point[0])*kx,by=(b[1]-point[1])*ky;
  const dx=bx-ax,dy=by-ay,t=Math.max(0,Math.min(1,-(ax*dx+ay*dy)/(dx*dx+dy*dy||1))),distance=Math.hypot(ax+t*dx,ay+t*dy);
  if(distance<best.distance)best={distance,along:route.cumulative[i-1]+t*(route.cumulative[i]-route.cumulative[i-1])};
 }
 return {...best,remaining:Math.max(0,route.cumulative.at(-1)-best.along)};
}
export function routeUrl(start,end){
 if(!validPoint(start)||!validPoint(end))throw Error('Choose valid start and destination points.');
 return 'https://routing.openstreetmap.de/routed-car/route/v1/driving/'+[start,end].map(p=>p.map(n=>n.toFixed(6)).join(',')).join(';')+'?overview=full&geometries=geojson&steps=false&alternatives=false';
}
