const crypto = require('node:crypto');

// Only Mojang's HTTPS response can establish account ownership. No client-supplied
// name, UUID, or saved relay credential is an identity proof.
async function verifyMinecraftSession(name, serverId) {
  const url = new URL('https://sessionserver.mojang.com/session/minecraft/hasJoined');
  url.searchParams.set('username', name);
  url.searchParams.set('serverId', serverId);
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(10_000) });
  if (response.status !== 200) return null;
  return response.json();
}

function sessionServerId(challenge, clientNonce) {
  return crypto.createHash('sha256').update(`openintel-relay-auth-v1\0${challenge}\0${clientNonce}`)
    .digest('hex').slice(0, 40);
}

function createIdentityVerification(ws, { userByToken, onVerified, onRejected = () => {},
  verify = verifyMinecraftSession, timeoutMs = 30_000 }) {
  let state = 'new';
  let pending;
  const deadline = Date.now() + timeoutMs;
  const timer = setTimeout(() => deny('authentication_timeout'), timeoutMs);
  timer.unref?.();
  function deny(reason = 'identity_verification_failed') {
    if (state === 'closed') return;
    state = 'closed';
    pending = null;
    clearTimeout(timer);
    ws.authedAs = null;
    onRejected(reason);
    if (ws.readyState === 1) {
      ws.send(JSON.stringify({ type: 'deny', reason }));
      ws.close(4001, reason);
    }
  }
  ws.on('close', () => { state = 'closed'; pending = null; clearTimeout(timer); });
  return {
    begin(token) {
      if (state !== 'new' || Date.now() >= deadline) return deny();
      const user = typeof token === 'string' && token.length > 0 ? userByToken(token) : null;
      if (!user || !/^[A-Za-z0-9_]{3,16}$/.test(user.name)) return deny('bad_token');
      pending = { token, name: user.name, challenge: crypto.randomBytes(20).toString('hex') };
      state = 'challenge';
      ws.send(JSON.stringify({ type: 'auth_challenge', challenge: pending.challenge }));
    },
    async complete(clientNonce) {
      if (state !== 'challenge' || Date.now() >= deadline || typeof clientNonce !== 'string' || !/^[a-f0-9]{40}$/.test(clientNonce)) return deny();
      state = 'verifying';
      const attempt = pending;
      try {
        const profile = await verify(attempt.name, sessionServerId(attempt.challenge, clientNonce));
        if (state !== 'verifying' || pending !== attempt || ws.readyState !== 1) return;
        if (Date.now() >= deadline) return deny('authentication_timeout');
        const current = userByToken(attempt.token);
        if (!profile || typeof profile.name !== 'string'
            || profile.name.toLowerCase() !== attempt.name.toLowerCase()
            || typeof profile.id !== 'string' || !/^[a-f0-9]{32}$/i.test(profile.id)
            || !current || current.name.toLowerCase() !== attempt.name.toLowerCase()
            || (current.minecraftUuid != null && (typeof current.minecraftUuid !== 'string'
              || current.minecraftUuid.toLowerCase() !== profile.id.toLowerCase()))) return deny();
        state = 'verified';
        pending = null;
        clearTimeout(timer);
        onVerified(current, profile.id.toLowerCase());
      } catch { deny(); }
    },
  };
}

module.exports = { createIdentityVerification, verifyMinecraftSession, sessionServerId };
