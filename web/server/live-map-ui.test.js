import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
const entry=fileURLToPath(new URL('../public/live-map/map.js',import.meta.url));
const built=await build({configFile:false,logLevel:'silent',build:{write:false,minify:false,lib:{entry,name:'LiveMapTest',formats:['iife']}}});
const code=(Array.isArray(built)?built[0]:built).output.find(x=>x.type==='chunk').code;
const html=await readFile(new URL('../public/live-map/index.html',import.meta.url),'utf8');
const tick=()=>new Promise(r=>setTimeout(r,10));
async function mount(fetcher){
 const dom=new JSDOM(html,{url:'https://appassets.androidplatform.net/assets/web/live-map/index.html',runScripts:'outside-only'}),w=dom.window,actions=[],layers=[],events={};
 const layer=()=>({addTo(){return this;},on(){return this;},setLatLng(){return this;},setRadius(){return this;},getBounds(){return[];}});
 w.L={map(){return{setView(){return this;},on(k,v){events[k]=v;return this;},remove(){actions.push('remove');},removeLayer(){},getZoom(){return 16;},panTo(){},fitBounds(){actions.push('fit');}};},tileLayer(){actions.push('tiles');return layer();},circleMarker:layer,circle:layer,polyline(points){layers.push(points);return layer();}};
 w.IcarusMapHost={begin(){actions.push('begin');},stop(){actions.push('stop');},close(){actions.push('close');}};w.fetch=fetcher||(()=>Promise.reject(Error('offline')));w.eval(code);
 const click=id=>w.document.getElementById(id).click();
 const fix=()=>w.icarusMapLocation({state:'fix',latitude:0,longitude:0,accuracy:5,timestamp:Date.now(),speed:0});
 return {dom,w,actions,layers,events,click,fix};
}
const response={code:'Ok',waypoints:[{distance:0},{distance:0}],routes:[{distance:1112,duration:100,geometry:{type:'LineString',coordinates:[[0,0],[.01,0]]}}]};
test('no online tiles before explicit start and native acknowledgement',async()=>{const a=await mount();try{
 assert.deepEqual(a.actions,[]);a.click('start');assert.deepEqual(a.actions,['begin']);
 a.w.icarusMapLocation({state:'started'});assert.ok(a.actions.includes('tiles'));
 a.fix();a.events.click({latlng:{lng:.01,lat:0}});assert.equal(a.w.document.getElementById('calculate').disabled,false);
 a.w.icarusMapLocation({state:'fix',latitude:0,longitude:0,accuracy:5,timestamp:Date.now(),speed:10});assert.equal(a.w.document.getElementById('calculate').disabled,true);
 a.click('stop');assert.ok(a.actions.includes('stop'));assert.equal(a.w.document.getElementById('session').hidden,true);
}finally{a.dom.window.close();}});
test('real response draws road geometry; delayed response after Stop cannot restore a route',async()=>{
 let finish;const a=await mount(()=>new Promise(r=>{finish=r;}));try{
 a.click('start');a.w.icarusMapLocation({state:'started'});a.fix();a.events.click({latlng:{lng:.01,lat:0}});a.click('calculate');
 a.click('stop');finish({ok:true,text:async()=>JSON.stringify(response)});await tick();assert.equal(a.layers.length,0);
}finally{a.dom.window.close();}});
test('successful routing uses returned coordinates and missing GPS disables guidance',async()=>{const a=await mount(async()=>({ok:true,text:async()=>JSON.stringify(response)}));try{
 a.click('start');a.w.icarusMapLocation({state:'started'});a.fix();a.events.click({latlng:{lng:.01,lat:0}});a.click('calculate');await tick();
 assert.equal(a.layers.length,1);assert.equal(a.layers[0][1][1],.01);
 a.w.icarusMapLocation({state:'waiting'});assert.equal(a.w.document.getElementById('speed').textContent,'—');assert.equal(a.w.document.getElementById('calculate').disabled,true);assert.match(a.w.document.getElementById('status').textContent,/unavailable/);
}finally{a.dom.window.close();}});
