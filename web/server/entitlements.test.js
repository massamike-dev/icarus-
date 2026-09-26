import test from 'node:test';
import assert from 'node:assert/strict';
import { developerGrantEmails, entitlementFor } from './entitlements.js';

test('developer Premium grants are server-controlled and normalized',()=>{
  const env={ICARUS_PREMIUM_GRANT_EMAILS:'  Owner@Example.com,friend@example.com  '};
  assert.deepEqual([...developerGrantEmails(env)].sort(),['friend@example.com','owner@example.com']);
  assert.deepEqual(entitlementFor({email:'OWNER@example.com'},env),{
    premium:true,
    source:'developer_grant',
    expiresAt:null,
  });
  assert.deepEqual(entitlementFor({email:'other@example.com'},env),{
    premium:false,
    source:'none',
    expiresAt:null,
  });
});
