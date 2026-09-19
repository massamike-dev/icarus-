import test from 'node:test';
import assert from 'node:assert/strict';
import {getNativeTransport} from '../src/native-transport.js';

test('current Android channel is preferred over the legacy bridge',()=>{
  const current={postMessage(){}};
  const legacy={postMessage(){}};
  assert.equal(getNativeTransport({ICARUS_NATIVE_CHANNEL:current,IcarusNative:legacy}),current);
});

test('legacy Android bridge remains compatible',()=>{
  const legacy={postMessage(){}};
  assert.equal(getNativeTransport({IcarusNative:legacy}),legacy);
  assert.equal(getNativeTransport({}),null);
});
