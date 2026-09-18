import { createHmac, randomUUID } from 'node:crypto';

// Private builds must never fall back to public registration or a remembered
// production account. Credentials are supplied by the deployment, not the APK.
export function privateTestConfig(env = process.env) {
  const flag = env.ICARUS_PRIVATE_TEST;
  if (flag === undefined || flag === '' || flag === 'false') return null;
  if (flag !== 'true') throw new Error('ICARUS_PRIVATE_TEST must be true or false.');
  const email = String(env.ICARUS_TEST_EMAIL || '').trim().toLowerCase();
  const passwordHash = String(env.ICARUS_TEST_PASSWORD_HASH || '');
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new Error('Private testing requires ICARUS_TEST_EMAIL.');
  if (!/^[a-f0-9]{32}:[a-f0-9]{128}$/i.test(passwordHash)) throw new Error('Private testing requires a valid ICARUS_TEST_PASSWORD_HASH.');
  return { email, passwordHash };
}

export function isPrivateTester(user, config) {
  return Boolean(user && user.privateTester === true && user.email === config.email && user.passwordHash === config.passwordHash);
}

export function privateSessionSecret(secret, config) {
  // A private environment cannot accept public tokens, even if an operator
  // accidentally reuses the session secret. Rotating the test password revokes
  // existing test tokens without deleting the tester's conversations.
  return config ? createHmac('sha256', secret).update(`icarus-private-test\0${config.email}\0${config.passwordHash}`).digest('hex') : secret;
}

export async function bootstrapPrivateTester(store, config) {
  if (!config) {
    if ((await store.read()).privateTest) throw new Error('Private test data store requires ICARUS_PRIVATE_TEST=true.');
    return;
  }
  await store.update(data => {
    const marker = data.privateTest;
    if (!marker) {
      if (Object.values(data).some(value => Array.isArray(value) ? value.length > 0 : value != null)) {
        throw new Error('Private testing requires a new, empty data store. Refusing to import existing accounts or data.');
      }
      const userId = randomUUID();
      data.privateTest = { version: 1, email: config.email, userId };
      data.users.push({ id: userId, email: config.email, name: 'Michael', privateTester: true, passwordHash: config.passwordHash, createdAt: new Date().toISOString() });
      return;
    }
    if (marker.version !== 1 || marker.email !== config.email || !marker.userId || data.users.length > 1) {
      throw new Error('Private test data store does not match the configured tester.');
    }
    const user = data.users[0];
    if (!user) return; // An account deleted by its owner stays deleted.
    if (user.id !== marker.userId || user.email !== config.email || user.privateTester !== true) {
      throw new Error('Private test data store contains an unexpected account.');
    }
    user.passwordHash = config.passwordHash;
  });
}
