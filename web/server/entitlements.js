const normalizeEmail=value=>String(value||'').trim().toLowerCase();

export function developerGrantEmails(env=process.env) {
  return new Set(String(env.ICARUS_PREMIUM_GRANT_EMAILS||'')
    .split(',')
    .map(normalizeEmail)
    .filter(Boolean));
}

export function entitlementFor(user,env=process.env) {
  const granted=developerGrantEmails(env).has(normalizeEmail(user?.email));
  return {
    premium: granted,
    source: granted ? 'developer_grant' : 'none',
    expiresAt: null,
  };
}
