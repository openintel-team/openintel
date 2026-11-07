// Exercises the real server.js handlers in a VM with fake sockets, a fake
// Discord client, and in-memory files. Nothing contacts Discord or Mojang.
async function main() {
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const visibility = require('./visibility');

const sha256 = value => crypto.createHash('sha256').update(value).digest('hex');
const STRONG_ADMIN = 'k'.repeat(40);
const settle = () => new Promise(resolve => setImmediate(resolve));

function fixture({ config = {}, users } = {}) {
  users = users ?? [
    { name: 'AdminOne', token: 'admin-one-token', role: 'admin' },
    { name: 'Member', token: 'member-token', role: 'member' },
    { name: 'Failing', token: 'failing-token', role: 'member' },
  ];
  const files = new Map(Object.entries({
    'config.json': { minecraftServer: 'play.example.net', discord: {},
      webhooks: { admin: 'https://hooks.invalid/admin', alerts: 'https://hooks.invalid/alerts' }, ...config },
    'users.json': { users }, 'allegiances.json': { allies: [], enemies: [], focus: [] },
    'wartable.json': { strokes: [] },
  }).map(([k, v]) => [k, JSON.stringify(v)]));
  const routes = new Map();
  const app = { use() {}, get(route, ...h) { routes.set('GET ' + route, h); }, put(route, ...h) { routes.set('PUT ' + route, h); } };
  const webhooks = [];
  const logs = [];
  const timers = [];
  let server, wssOptions, bot;
  class FakeServer extends EventEmitter {
    constructor(options) { super(); this.clients = new Set(); wssOptions = options; server = this; }
  }
  class Client extends EventEmitter {
    constructor() { super(); bot = this; this.user = { tag: 'bot#0' }; this.guilds = { cache: { size: 1 } };
      this.channels = { cache: new Map(), fetch: async () => null }; }
    login() { return Promise.resolve(); }
  }
  class Stub {}
  const discord = { Client, ActionRowBuilder: Stub, ButtonBuilder: Stub, EmbedBuilder: Stub, ButtonStyle: {},
    GatewayIntentBits: { Guilds: 1, GuildMessages: 2, MessageContent: 3 },
    PermissionsBitField: { Flags: { Administrator: 8n } } };
  const context = vm.createContext({
    __dirname, Buffer, console: { log(...a) { logs.push(a.join(' ')); }, error(...a) { logs.push(a.join(' ')); } },
    process: { argv: [], pid: 1, env: {} },
    setInterval(fn) { timers.push(fn); },
    fetch: async (url, options) => { webhooks.push({ url, body: JSON.parse(options.body) }); return { ok: true }; },
    require(name) {
      if (name === './visibility') return visibility;
      if (name === './authentication') return { createIdentityVerification: (ws, options) => require('./authentication')
        .createIdentityVerification(ws, { ...options, verify: async name => name === 'Failing' ? null : { name, id: 'a'.repeat(32) } }) };
      if (name === 'express') return Object.assign(() => app, { json: () => () => {} });
      if (name === 'ws') return { WebSocketServer: FakeServer };
      if (name === 'discord.js') return discord;
      if (name === 'http') return { createServer: () => Object.assign(new EventEmitter(), { listen(port, fn) { fn(); } }) };
      if (name === 'fs') return {
        readFileSync(file) { if (!files.has(path.basename(file))) throw new Error('missing'); return files.get(path.basename(file)); },
        writeFileSync(file, data) { files.set(path.basename(file), data); },
        renameSync(a, b) { files.set(path.basename(b), files.get(path.basename(a))); files.delete(path.basename(a)); },
        chmodSync() {}, appendFileSync(file, data) { files.set(path.basename(file), (files.get(path.basename(file)) ?? '') + data); },
      };
      return require(name);
    },
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, 'server.js'), 'utf8'), context);

  class Socket extends EventEmitter {
    constructor() { super(); this.readyState = 1; this.messages = []; }
    send(raw) { this.messages.push(JSON.parse(raw)); }
    close(code) { if (this.readyState === 3) return; this.code = code; this.readyState = 3; this.emit('close'); }
    submit(msg) { this.emit('message', JSON.stringify(msg)); }
    take(type) { const out = this.messages.filter(m => m.type === type); this.messages = this.messages.filter(m => m.type !== type); return out; }
  }
  function connect(address = '198.51.100.20') {
    const socket = new Socket(); server.clients.add(socket);
    server.emit('connection', socket, { socket: { remoteAddress: address } });
    return socket;
  }
  async function login(token, address) {
    const socket = connect(address);
    socket.submit({ type: 'hello', token, minecraftServer: 'play.example.net' });
    socket.submit({ type: 'authenticate', clientNonce: 'b'.repeat(40) });
    await settle();
    return socket;
  }
  async function command(content, { dm = async () => {} } = {}) {
    const replies = [], dms = [];
    const msg = { content, channelId: 'terminal', guildId: 'guild', guild: {},
      author: { bot: false, id: '42', username: 'admin', send: async text => { dms.push(text); await dm(text); } },
      member: { displayName: 'Admin', permissions: { has: () => true }, roles: { cache: new Map() } },
      reply: async text => { replies.push(typeof text === 'string' ? text : JSON.stringify(text)); } };
    for (const listener of bot.listeners('messageCreate')) await listener(msg);
    return { replies, dms };
  }
  const usersFile = () => JSON.parse(files.get('users.json')).users;
  return { files, routes, webhooks, logs, connect, login, command, usersFile, tick() { timers.forEach(fn => fn()); },
    get wssOptions() { return wssOptions; }, get bot() { return bot; } };
}
function response() {
  return { code: 200, status(code) { this.code = code; return this; }, json(body) { this.body = body; return this; } };
}
function guarded(f, route, headers) {
  const [guard] = f.routes.get(route);
  const res = response();
  let passed = false;
  guard({ headers }, res, () => { passed = true; });
  return { passed, res };
}

// ---- 3. Admin REST fails closed and compares in constant time.
for (const adminToken of [undefined, '', 'short', 'CHANGE_ME', 'CHANGE_ME_LONG_RANDOM_STRING']) {
  const f = fixture({ config: adminToken === undefined ? {} : { adminToken } });
  for (const route of ['GET /users', 'PUT /users', 'GET /allegiances', 'PUT /allegiances', 'GET /online']) {
    for (const headers of [{}, { 'x-admin-token': adminToken }]) {
      const { passed, res } = guarded(f, route, headers);
      assert.equal(passed, false, `${route} open with adminToken=${adminToken}`);
      assert.equal(res.code, 403);
      assert.equal(res.body.error, 'admin API disabled');
    }
  }
  assert(f.logs.some(line => /Admin HTTP API disabled/.test(line)));
}
{
  const f = fixture({ config: { adminToken: STRONG_ADMIN } });
  assert.equal(guarded(f, 'PUT /users', { 'x-admin-token': STRONG_ADMIN }).passed, true);
  for (const headers of [{}, { 'x-admin-token': '' }, { 'x-admin-token': STRONG_ADMIN.slice(1) },
    { 'x-admin-token': STRONG_ADMIN + 'k' }, { 'x-admin-token': [STRONG_ADMIN] }]) {
    const { passed, res } = guarded(f, 'PUT /users', headers);
    assert.equal(passed, false);
    assert.equal(res.code, 403);
    assert.equal(res.body.error, 'bad admin token');
  }
  assert(!f.logs.some(line => /Admin HTTP API disabled/.test(line)));
}

// ---- 1. Rejections name a per-run client tag, never the address.
{
  const f = fixture();
  const first = '203.0.113.7', second = '203.0.113.99';
  f.login('not-a-token', first);
  await f.login('failing-token', first);
  f.login('not-a-token', second);
  const admin = f.webhooks.filter(w => w.url.endsWith('/admin')).map(w => w.body.content);
  const rejections = admin.filter(line => line.includes('❌'));
  assert.equal(rejections.length, 3);
  for (const line of admin) assert(!line.includes(first) && !line.includes(second), line);
  const tags = rejections.map(line => line.match(/client `([0-9a-f]{8})`/)?.[1]);
  assert(tags.every(Boolean));
  assert.equal(tags[0], tags[1], 'same address keeps one tag across rejection kinds');
  assert.notEqual(tags[0], tags[2], 'different addresses get different tags');
  assert(!f.logs.some(line => line.includes(first)), 'raw addresses stay out of the console by default');
  // Successful logins still name only the account.
  await f.login('member-token', first);
  const connected = f.webhooks.map(w => w.body.content).filter(line => line.includes('connected'));
  assert.deepEqual(connected, ['🛠️ ✅ **Member** connected (member)']);
}
// The invitation owner comes from the recognized token, never a claimed identity.
{
  const f = fixture();
  const socket = f.connect('203.0.113.8');
  socket.submit({ type: 'hello', token: 'failing-token', minecraftServer: 'play.example.net',
    name: 'Forged', minecraftUuid: 'c'.repeat(32) });
  socket.submit({ type: 'authenticate', clientNonce: 'b'.repeat(40), name: 'Forged' });
  await settle();
  assert.equal(socket.code, 4001);
  assert.equal(socket.take('welcome').length, 0);
  const [rejection] = f.webhooks.filter(w => w.url.endsWith('/admin')).map(w => w.body.content).filter(line => line.includes('❌'));
  assert.match(rejection, /Token for \*\*Failing\*\* used from client `[0-9a-f]{8}`; Minecraft account verification failed \(identity_verification_failed\)/);
  for (const secret of ['Forged', '203.0.113.8', 'failing-token', sha256('failing-token')]) {
    assert(!rejection.includes(secret), secret);
  }
}
{
  const f = fixture();
  const socket = f.connect();
  socket.submit({ type: 'hello', token: 'unknown-token', minecraftServer: 'play.example.net', name: 'Member' });
  const [rejection] = f.webhooks.filter(w => w.url.endsWith('/admin')).map(w => w.body.content).filter(line => line.includes('❌'));
  assert.match(rejection, /Auth failure from client `[0-9a-f]{8}`/);
  assert(!rejection.includes('Member') && !rejection.includes('Token for'));
  assert(!rejection.includes('unknown-token') && !rejection.includes(sha256('unknown-token')));
}
{
  const f = fixture({ config: { logRejectedIps: true } });
  f.login('not-a-token', '203.0.113.7');
  assert(f.logs.some(line => line.includes('203.0.113.7')), 'opt-in console logging');
  assert(!f.webhooks.some(w => JSON.stringify(w.body).includes('203.0.113.7')), 'never in the webhook');
}

// ---- 5. Frame size limit and pending logins per address.
{
  const f = fixture();
  assert.equal(f.wssOptions.maxPayload, 256 * 1024);
  assert.equal(f.wssOptions.noServer, true);
  assert.equal(fixture({ config: { maxMessageBytes: 4096 } }).wssOptions.maxPayload, 4096);
}
{
  const f = fixture({ config: { maxPendingPerAddress: 2 } });
  const a = f.connect('192.0.2.1'), b = f.connect('192.0.2.1');
  const refused = f.connect('192.0.2.1');
  assert.equal(refused.code, 1013);
  assert.equal(a.readyState, 1);
  assert.equal(f.connect('192.0.2.2').readyState, 1, 'other addresses are unaffected');
  a.close(1000);
  assert.equal(f.connect('192.0.2.1').readyState, 1, 'a closed pending socket frees its slot');
  b.close(1000);
  // A finished login stops counting toward the cap.
  const member = await f.login('member-token', '192.0.2.3');
  const admin = await f.login('admin-one-token', '192.0.2.3');
  assert.equal(member.take('welcome').length, 1);
  assert.equal(admin.take('welcome').length, 1);
  assert.equal(f.connect('192.0.2.3').readyState, 1);
  assert.equal(f.connect('192.0.2.3').readyState, 1);
  assert.equal(f.connect('192.0.2.3').code, 1013);
  const warnings = f.webhooks.map(w => w.body.content).filter(line => line.includes('Too many pending'));
  assert.equal(warnings.length, 2, 'one rate-limited warning per address');
  assert(!warnings.some(line => line.includes('192.0.2.')));
}
{
  const f = fixture({ config: { maxPendingPerAddress: 0 } });
  for (let i = 0; i < 40; i++) assert.equal(f.connect('192.0.2.9').readyState, 1, '0 turns the cap off');
}

// ---- 6. Direct pings and snitches forward only checked fields.
{
  const f = fixture();
  const admin = await f.login('admin-one-token');
  const member = await f.login('member-token');
  admin.messages.length = 0; member.messages.length = 0;
  const at = { x: 10, y: 64, z: -20, dim: 'minecraft:overworld' };
  member.submit({ type: 'ping', id: 'p1', label: 'here', color: 0xFF00FF, ttl: 9e9, ...at,
    evil: 'x'.repeat(5000), role: 'admin', from: 'Forged', reporter: 'Forged', t: 1 });
  const [ping] = admin.take('ping');
  assert.deepEqual(Object.keys(ping).sort(),
    ['bridge', 'color', 'dim', 'from', 'id', 'label', 'reporter', 't', 'ttl', 'type', 'x', 'y', 'z']);
  assert.equal(ping.ttl, 300000);
  assert.equal(ping.from, 'Member');
  assert.equal(ping.reporter, 'Member');
  assert.notEqual(ping.t, 1);
  for (const bad of [
    { type: 'ping', id: 'no-coords' },
    { type: 'ping', id: 'far', ...at, x: 30000001 },
    { type: 'ping', id: 'nan', ...at, y: 'high' },
    { type: 'ping', id: 'bad-dim', ...at, dim: 'Not A Dimension' },
    { type: 'ping', id: 'long-label', ...at, label: 'x'.repeat(1501) },
    { type: 'ping', id: { nested: true }, ...at },
    { type: 'ping', id: 'bad-player', ...at, player: 'not a name' },
    { type: 'snitch', message: 'partial', player: 'Tripper', x: 1, y: 2 },
    { type: 'snitch', player: 'Tripper' },
    { type: 'snitch', message: 'x'.repeat(1501) },
    { type: 'snitch', message: 'bad\u0000text' },
    { type: 'snitch', message: 'ok', world: { unsafe: true } },
  ]) member.submit(bad);
  assert.equal(admin.messages.length, 0, JSON.stringify(admin.messages));

  member.submit({ type: 'snitch', message: 'Vault: Tripper entered snitch at (1, 2, 3)', player: 'Tripper',
    snitch: 'Vault', action: 'entered snitch', eventKind: 'event', x: 1, y: 2, z: 3, world: 'world',
    reporter: 'Forged', extra: { big: 'x'.repeat(5000) } });
  const [snitch] = admin.take('snitch');
  assert.equal(snitch.extra, undefined);
  assert.equal(snitch.reporter, 'Member');
  assert.equal(snitch.world, 'minecraft:overworld');
  assert.equal(snitch.dim, 'minecraft:overworld');
  member.submit({ type: 'snitch', message: 'Text-only sensor alert', world: 'openintel:unknown' });
  const [text] = admin.take('snitch');
  assert.equal(text.message, 'Text-only sensor alert');
  assert.equal(Object.hasOwn(text, 'dim'), false);

  // Position reports with an oversized or malformed dimension are dropped.
  member.submit({ type: 'positions', reports: [
    { subject: 'Good', x: 1, y: 2, z: 3, dim: 'minecraft:overworld' },
    { subject: 'HugeDim', x: 1, y: 2, z: 3, dim: 'minecraft:' + 'x'.repeat(5000) },
    { subject: 'Spaced', x: 1, y: 2, z: 3, dim: 'Not A Dimension' },
    { subject: 'Far', x: 1e9, y: 2, z: 3, dim: 'minecraft:overworld' },
    null,
  ] });
  f.tick();
  assert.deepEqual(admin.take('state').at(-1).players.map(p => p.name), ['Good']);
}

// ---- 8. users.json keeps only token digests; plaintext tokens are migrated.
{
  const f = fixture({ config: { adminToken: STRONG_ADMIN }, users: [
    { name: 'AdminOne', token: 'admin-one-token', role: 'admin' },
    { name: 'Member', token: 'member-token', role: 'member' },
    { name: 'Hashed', tokenHash: sha256('hashed-token'), role: 'member' },
  ] });
  const stored = f.files.get('users.json');
  for (const secret of ['admin-one-token', 'member-token']) assert(!stored.includes(secret));
  assert(f.usersFile().every(u => !Object.hasOwn(u, 'token')));
  assert.equal(f.usersFile()[1].tokenHash, sha256('member-token'));
  assert(f.logs.some(line => /replaced plaintext relay tokens/.test(line)));
  assert.equal((await f.login('member-token')).take('welcome').length, 1);
  assert.equal((await f.login('hashed-token')).take('welcome').length, 1);
  const wrong = await f.login(sha256('member-token'));
  assert.equal(wrong.take('deny')[0].reason, 'bad_token', 'the digest itself is not a token');
  assert.equal(f.usersFile().find(u => u.name === 'Member').minecraftUuid, 'a'.repeat(32), 'UUID pinning still works');

  // REST accepts a plaintext token (hashed on receipt) or an existing digest.
  const put = f.routes.get('PUT /users').at(-1);
  put({ body: { users: [...f.usersFile(), { name: 'Rested', token: 'rest-token', role: 'member' }, null] } }, response());
  assert.equal(f.usersFile().length, 4);
  assert(!f.files.get('users.json').includes('rest-token'));
  assert.equal(f.usersFile().find(u => u.name === 'Rested').tokenHash, sha256('rest-token'));
  assert.equal(f.usersFile().find(u => u.name === 'Hashed').tokenHash, sha256('hashed-token'));
  assert.equal((await f.login('rest-token')).take('welcome').length, 1);
  const audit = f.files.get('audit.jsonl');
  assert(!audit.includes('rest-token') && !audit.includes(sha256('rest-token')));
  assert(audit.includes(`sha256:${sha256('rest-token').slice(0, 12)}`));
}

// ---- 8 + 9. Discord user commands never expose a stored token.
{
  const f = fixture({ config: { discord: { botToken: 'fake' } } });
  const added = await f.command('!user add NewUser member');
  const token = added.dms[0].match(/`([^`]+)`/)[1];
  assert.equal(f.usersFile().find(u => u.name === 'NewUser').tokenHash, sha256(token));
  assert(!f.files.get('users.json').includes(token));
  assert(!f.files.get('audit.jsonl').includes(token));
  assert.equal((await f.login(token)).take('deny').length, 0);

  const resend = await f.command('!user token NewUser');
  assert.equal(resend.dms.length, 0);
  assert.match(resend.replies[0], /rotate-token NewUser/);

  const info = await f.command('!user info NewUser');
  assert(info.replies[0].includes(`sha256:${sha256(token).slice(0, 12)}`));

  const session = await f.login(token);
  const rotated = await f.command('!user rotate-token NewUser');
  const fresh = rotated.dms[0].match(/`([^`]+)`/)[1];
  assert.notEqual(fresh, token);
  assert.equal(f.usersFile().find(u => u.name === 'NewUser').tokenHash, sha256(fresh));
  assert.equal(session.readyState, 3, 'rotation revokes live sessions');
  assert.equal((await f.login(token)).take('deny')[0].reason, 'bad_token');

  const failedAdd = await f.command('!user add NoDms', { dm: async () => { throw new Error('closed'); } });
  assert.equal(f.usersFile().some(u => u.name === 'NoDms'), false);
  assert(!/`[A-Za-z0-9_-]{20,}`/.test(failedAdd.replies.join('\n')));

  const generated = await f.command('!gentoken');
  assert.match(generated.dms[0], /[0-9a-f]{64}/);
  const failedGen = await f.command('!gentoken', { dm: async () => { throw new Error('closed'); } });
  assert.equal(failedGen.replies.length, 1);
  assert(!/[0-9a-f]{64}/.test(failedGen.replies[0]), 'no token in the channel when the DM fails');
  assert.match(failedGen.replies[0], /Enable DMs/);
  assert(!(await f.command('!help')).replies[0].includes('!user token'));
}

console.log('Relay security tests passed: admin API fails closed, tagged rejections without IPs, frame and pending limits, checked ping/snitch fields, hashed tokens, and DM-only tokens');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
