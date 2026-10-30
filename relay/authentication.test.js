const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { createIdentityVerification, verifyMinecraftSession, sessionServerId } = require('./authentication');

const validProfile = { name: 'Invited', id: 'a'.repeat(32) };
class Socket extends EventEmitter {
  constructor() { super(); this.readyState = 1; this.authedAs = null; this.messages = []; }
  send(raw) { this.messages.push(JSON.parse(raw)); }
  close(code) { this.code = code; this.readyState = 3; this.emit('close'); }
}
function fixture(verify = async () => validProfile, timeoutMs) {
  const socket = new Socket();
  const users = [{ name: 'Invited', token: 'secret' }];
  let verified = 0;
  const auth = createIdentityVerification(socket, {
    verify, timeoutMs, userByToken: token => users.find(u => !u.disabled && u.token === token),
    onVerified(user) { verified++; socket.authedAs = user.name; },
  });
  const complete = auth.complete;
  auth.complete = (clientNonce = 'b'.repeat(40)) => complete(clientNonce);
  return { socket, auth, users, get verified() { return verified; },
    challenge() { return socket.messages.find(m => m.type === 'auth_challenge').challenge; } };
}
async function main() {
  assert.equal(sessionServerId('a'.repeat(40), 'b'.repeat(40)), '08d5243bfa543aafa1458bb8c609bd4b6c80aa67');
  // Copied config/token, including a forged invited name/UUID, grants no access.
  const wrong = fixture(async () => ({ name: 'OtherAccount', id: 'b'.repeat(32) }));
  wrong.auth.begin('secret');
  assert.equal(wrong.socket.authedAs, null);
  assert.equal(wrong.verified, 0);
  await wrong.auth.complete();
  assert.equal(wrong.verified, 0);
  assert.equal(wrong.socket.code, 4001);

  const good = fixture(async (name, challenge) => {
    assert.equal(name, 'Invited');
    assert.equal(challenge, sessionServerId(good.challenge(), 'b'.repeat(40)));
    return { ...validProfile, name: 'invited' };
  });
  good.auth.begin('secret');
  assert.match(good.challenge(), /^[0-9a-f]{40}$/);
  await good.auth.complete();
  assert.equal(good.verified, 1);
  assert.equal(good.socket.authedAs, 'Invited');
  await good.auth.complete();
  assert.equal(good.verified, 1); // consumed once
  assert.equal(good.socket.authedAs, null);

  // No token-only fallback, missing/empty tokens, or proof before hello.
  for (const token of [undefined, null, '', {}, 'wrong']) {
    const f = fixture(); f.auth.begin(token);
    assert.equal(f.socket.code, 4001); assert.equal(f.verified, 0);
  }
  const noHello = fixture(); await noHello.auth.complete();
  assert.equal(noHello.socket.code, 4001);
  const legacy = fixture(undefined, 5); legacy.auth.begin('secret');
  await new Promise(resolve => setTimeout(resolve, 15));
  assert.equal(legacy.socket.code, 4001); assert.equal(legacy.verified, 0);

  // Challenges are socket-specific: proof registered for A cannot authorize B.
  const joined = new Set();
  const verify = async (name, challenge) => joined.has(challenge) ? validProfile : null;
  const a = fixture(verify), b = fixture(verify);
  a.auth.begin('secret'); b.auth.begin('secret');
  assert.notEqual(a.challenge(), b.challenge()); joined.add(sessionServerId(a.challenge(), 'b'.repeat(40)));
  await b.auth.complete(); assert.equal(b.verified, 0);
  await a.auth.complete(); assert.equal(a.verified, 1); a.socket.close();

  for (const profile of [null, {}, { name: 'Invited' }, { name: 'Invited', id: '../fake' },
    { ...validProfile, name: null }]) {
    const f = fixture(async () => profile); f.auth.begin('secret'); await f.auth.complete();
    assert.equal(f.verified, 0); assert.equal(f.socket.code, 4001);
  }
  for (const nonce of [null, '', {}, 'A'.repeat(40), 'b'.repeat(41)]) {
    const f = fixture(); f.auth.begin('secret'); await f.auth.complete(nonce);
    assert.equal(f.verified, 0); assert.equal(f.socket.code, 4001);
  }
  const outage = fixture(async () => { throw new Error('upstream unavailable'); });
  outage.auth.begin('secret'); await outage.auth.complete(); assert.equal(outage.verified, 0);

  for (const uuid of ['b'.repeat(32), '', {}, 'a'.repeat(31)]) {
    const f = fixture(); f.users[0].minecraftUuid = uuid;
    f.auth.begin('secret'); await f.auth.complete(); assert.equal(f.verified, 0);
  }
  const pinned = fixture(); pinned.users[0].minecraftUuid = validProfile.id.toUpperCase();
  pinned.auth.begin('secret'); await pinned.auth.complete(); assert.equal(pinned.verified, 1); pinned.socket.close();

  // Recheck invitations after the asynchronous proof; invalidate late callbacks.
  for (const change of ['rotate', 'disable', 'remove', 'rename', 'close', 'duplicate', 'hello', 'expire']) {
    let resolve;
    const f = fixture(() => new Promise(done => { resolve = done; }), change === 'expire' ? 5 : undefined);
    f.auth.begin('secret'); const pending = f.auth.complete();
    if (change === 'rotate') f.users[0].token = 'new-token';
    if (change === 'disable') f.users[0].disabled = true;
    if (change === 'remove') f.users.length = 0;
    if (change === 'rename') f.users[0].name = 'OtherAccount';
    if (change === 'close') f.socket.close();
    if (change === 'duplicate') await f.auth.complete();
    if (change === 'hello') f.auth.begin('secret');
    if (change === 'expire') await new Promise(done => setTimeout(done, 15));
    resolve(validProfile); await pending;
    assert.equal(f.verified, 0, change); assert.equal(f.socket.authedAs, null, change);
  }

  // Exercise the real verifier's fixed endpoint, HTTP status and failure handling.
  const originalFetch = global.fetch;
  try {
    for (const status of [200, 204, 403, 500]) {
      global.fetch = async (url, options) => {
        assert.equal(url.origin, 'https://sessionserver.mojang.com');
        assert.equal(url.pathname, '/session/minecraft/hasJoined');
        assert.equal(url.searchParams.get('username'), 'Invited');
        assert.equal(url.searchParams.get('serverId'), 'a'.repeat(40));
        assert.equal(options.redirect, 'error'); assert(options.signal);
        return { status, json: async () => validProfile };
      };
      assert.equal(await verifyMinecraftSession('Invited', 'a'.repeat(40)), status === 200 ? validProfile : null);
    }
    global.fetch = async () => { throw new Error('network'); };
    await assert.rejects(verifyMinecraftSession('Invited', 'a'.repeat(40)));
  } finally { global.fetch = originalFetch; }
  console.log('Minecraft identity tests passed: copied token, wrong account, replay, expiry, revocation, races and upstream failures');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
