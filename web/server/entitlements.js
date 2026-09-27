// Grants target immutable account IDs, never an unverified registration email.
export function developerGrantUserIds(env=process.env) {
  return new Set(String(env.ICARUS_PREMIUM_GRANT_USER_IDS||'').split(',').map(v=>v.trim()).filter(Boolean));
}
export function entitlementFor(user,env=process.env,records=[],now=Date.now()) {
  if(user?.id&&developerGrantUserIds(env).has(user.id))return {premium:true,source:'developer_grant',expiresAt:null};
  const record=records.filter(p=>p.userId===user?.id&&!p.replacedBy&&!p.deleted&&p.acknowledged===true&&p.expiresAt>now&&p.verifiedAt>now-600000&&['SUBSCRIPTION_STATE_ACTIVE','SUBSCRIPTION_STATE_IN_GRACE_PERIOD','SUBSCRIPTION_STATE_CANCELED'].includes(p.state)).sort((a,b)=>b.expiresAt-a.expiresAt)[0];
  return record?{premium:true,source:'google_play',productId:record.productId,expiresAt:new Date(record.expiresAt).toISOString(),autoRenewing:record.autoRenewing===true,testPurchase:record.testPurchase===true}:{premium:false,source:'none',expiresAt:null};
}
