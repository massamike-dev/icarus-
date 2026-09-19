import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {checkDevice,executeProposal,readDeviceStatus} from '../web/src/device-actions.js';

// Take the injected property from the real Android host, not a web-only mock
// name. This catches a cross-layer rename even when each side builds alone.
const activity=readFileSync(new URL('../native/android/app/src/main/java/com/icarusalmighty/app/MainActivity.kt',import.meta.url),'utf8');
const channel=activity.match(/\bNATIVE_CHANNEL\s*=\s*"([^"]+)"/)?.[1];
assert.ok(channel,'Android must declare its injected message channel');
assert.match(activity,/addWebMessageListener\(\s*webView,\s*NATIVE_CHANNEL,/);

function androidHost(version){
  const requests=[],host={};
  host[channel]={postMessage(raw){
    const request=JSON.parse(raw);requests.push(request);
    if(request.bridgeRequest==='status')host.ICARUS_NATIVE_STATUS?.(JSON.stringify({requestId:request.requestId,connected:true,actionProtocolVersion:version}));
    else host.ICARUS_NATIVE_RESULT?.(JSON.stringify({requestId:request.requestId,ok:true,data:{level:25,charging:false}}));
  }};
  return {host,requests};
}

test('web status and reviewed actions use the message channel declared by Android',async()=>{
  const {host,requests}=androidHost(1);
  assert.equal((await readDeviceStatus(host))?.connected,true);
  assert.equal(await checkDevice(host),true);
  assert.equal(await executeProposal({action:'get_battery'},host),'Battery: 25%. Not charging.');
  assert.equal(requests.filter(r=>r.action==='get_battery').length,1);
  assert.equal(host.IcarusNative,undefined,'the test must not supply the historical web alias');
});

test('an older Android host can confirm its native link without enabling newer Chat actions',async()=>{
  const {host,requests}=androidHost(undefined);
  assert.equal((await readDeviceStatus(host))?.connected,true);
  assert.equal(await checkDevice(host),false);
  assert.match(await executeProposal({action:'get_battery'},host),/Nothing was sent/);
  assert.equal(requests.filter(r=>r.action).length,0);
});
