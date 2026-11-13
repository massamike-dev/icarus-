import test from 'node:test';
import assert from 'node:assert/strict';
import {usableFix,parseRoute,projectRoute,routeUrl} from '../public/live-map/route-core.js';
const fixture={code:'Ok',waypoints:[{distance:0},{distance:0}],routes:[{distance:1112,duration:100,geometry:{type:'LineString',coordinates:[[0,0],[.01,0]]}}]};
test('fix requires valid fresh accurate coordinates and never invents speed',()=>{
 const f={state:'fix',longitude:0,latitude:0,accuracy:10,timestamp:100000,speed:null};
 assert.equal(usableFix(f,101000),true);
 for(const patch of [{timestamp:200000},{timestamp:1},{accuracy:100},{longitude:NaN},{latitude:90},{state:'stopped'}])assert.equal(usableFix({...f,...patch},101000),false);
});
test('road routes reject missing, invalid or far-snapped geometry',()=>{
 assert.equal(parseRoute(fixture).points.length,2);
 for(const bad of [{code:'NoRoute'}, {...fixture,waypoints:[{distance:999},{distance:0}]},{...fixture,routes:[{...fixture.routes[0],geometry:{type:'LineString',coordinates:[[0,0],[Infinity,0]]}}]}])assert.throws(()=>parseRoute(bad));
});
test('progress projects onto segments and detects off-route positions',()=>{
 const route=parseRoute(fixture),middle=projectRoute(route,[.005,0]);
 assert.ok(middle.distance<1);assert.ok(middle.remaining>550&&middle.remaining<570);
 assert.ok(projectRoute(route,[.005,.01]).distance>1000);
 assert.equal(projectRoute(route,[.02,0]).remaining,0);
});
test('routing uses HTTPS real geometry, bounded numeric coordinates and fixed service',()=>{
 assert.match(routeUrl([-122.3,47.6],[-122.31,47.61]),/^https:\/\/routing.openstreetmap.de\/routed-car\/route\/v1\/driving\//);
 assert.match(routeUrl([0,0],[1,1]),/geometries=geojson/);
 assert.throws(()=>routeUrl(['evil',0],[1,1]));
});
