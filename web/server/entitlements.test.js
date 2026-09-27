import test from 'node:test';
import assert from 'node:assert/strict';
import {developerGrantUserIds,entitlementFor} from './entitlements.js';
test('developer grants are exact server-configured immutable account IDs',()=>{
 const env={ICARUS_PREMIUM_GRANT_USER_IDS:' account-a,account-b,account-a '};
 assert.deepEqual([...developerGrantUserIds(env)],['account-a','account-b']);
 assert.deepEqual(entitlementFor({id:'account-a'},env),{premium:true,source:'developer_grant',expiresAt:null});
 assert.deepEqual(entitlementFor({id:'ACCOUNT-A'},env),{premium:false,source:'none',expiresAt:null});
});
