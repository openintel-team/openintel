const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const visibility = require('./visibility');

function fixture() {
  let now = 1800000000000;
  const users = [
    { name: 'AdminOne', token: 'a', role: 'admin', relayCut: true },
    { name: 'AdminTwo', token: 'b', role: 'admin' },
    { name: 'Member', token: 'm', role: 'member' },
    { name: 'Trial', token: 't', role: 'trial' },
    { name: 'Quarantine', token: 'q', role: 'admin', quarantined: true },
  ];
  const files = new Map(Object.entries({
    'config.json': { minecraftServer: 'play.example.net', discord: {}, webhooks: {}, staleMs: 10000 },
    'users.json': { users }, 'allegiances.json': { allies: [], enemies: [], focus: [] },
    'wartable.json': { strokes: [] },
  }).map(([k, v]) => [k, JSON.stringify(v)]));
  let server;
  const routes = new Map();
  const app = { use() {}, get() {}, put(route, ...handlers) { routes.set(route, handlers); } };
  const timers = [];
  class FakeServer extends EventEmitter {
    constructor() { super(); this.clients = new Set(); server = this; }
  }
  class Socket extends EventEmitter {
    constructor() { super(); this.readyState = 1; this.messages = []; }
    send(raw) { this.messages.push(JSON.parse(raw)); }
    close() { this.readyState = 3; }
    submit(msg) { this.emit('message', JSON.stringify(msg)); }
    take(type) { const out = this.messages.filter(m => m.type === type); this.messages = this.messages.filter(m => m.type !== type); return out; }
  }
  class Clock extends Date { static now() { return now; } }
  const context = vm.createContext({
    __dirname, Buffer, Date: Clock, console: { log() {}, error() {} }, process: { argv: [], pid: 1 },
    setInterval(fn) { timers.push(fn); }, fetch() { throw new Error('No live services allowed'); },
    require(name) {
      if (name === './visibility') return visibility;
      if (name === 'express') return Object.assign(() => app, { json: () => () => {} });
      if (name === 'ws') return { WebSocketServer: FakeServer };
      if (name === 'http') return { createServer: () => Object.assign(new EventEmitter(), { listen(port, fn) { fn(); } }) };
      if (name === 'fs') return {
        readFileSync(file) { if (!files.has(path.basename(file))) throw new Error('missing'); return files.get(path.basename(file)); },
        writeFileSync(file, data) { files.set(path.basename(file), data); },
        renameSync(a, b) { files.set(path.basename(b), files.get(path.basename(a))); files.delete(path.basename(a)); },
        chmodSync() {}, appendFileSync() {},
      };
      return require(name);
    },
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, 'server.js'), 'utf8'), context);
  function login(index) {
    const socket = new Socket(); server.clients.add(socket);
    server.emit('connection', socket, { socket: { remoteAddress: '127.0.0.1' } });
    socket.submit({ type: 'hello', token: users[index].token, minecraftServer: 'play.example.net' });
    assert.equal(socket.take('welcome')[0].bridgeVersion, 1);
    return socket;
  }
  const sessions = users.map((_, i) => login(i));
  const clear = () => server.clients.forEach(s => { s.messages = []; });
  clear();
  return { sessions, files, context, login, clear, tick() { timers[0](); }, advance(ms) { now += ms; },
    now: () => now, update(index, changes) {
      Object.assign(users[index], changes);
      routes.get('/users').at(-1)({ body: { users } }, { json() {} });
    } };
}
const f = fixture();
const [a, b, member, trial, quarantine] = f.sessions;
let sequence = 0;
const event = (type = 'ping', extra = {}) => ({ type, x: 1, y: 64, z: 2,
  dim: 'minecraft:overworld', t: f.now() - 1000, bridge: { id: 'event-' + ++sequence, hops: 0 }, ...extra });
const forward = (socket, e) => socket.submit({ type: 'bridge', event: e });

// Destination cut routing, shared identity, no origin role/permission transfer.
const ping = event('ping', { from: 'RemoteAdmin', reporter: 'RemoteReporter', role: 'admin', ttl: 60000 });
forward(a, ping);
let received = b.take('ping');
assert.equal(received.length, 1);
assert.equal(received[0].t, ping.t);
assert.deepEqual(received[0].bridge, { id: ping.bridge.id, hops: 1 });
assert.deepEqual(received[0].origin, { from: 'RemoteAdmin', reporter: 'RemoteReporter' });
assert.equal(received[0].from, 'AdminOne');
assert.equal(received[0].reporter, 'AdminOne');
assert.equal(received[0].role, undefined);
assert.equal(member.take('ping').length, 0);
assert.equal(trial.take('ping').length, 0);
f.clear();
// Same ID across sockets, returned through a loop, and normal submission dedupe.
forward(b, ping);
forward(b, received[0]);
b.submit({ ...ping, type: 'ping' });
assert.equal(a.take('ping').length, 0);
const duplicate = f.login(1);
forward(duplicate, ping);
assert.equal(a.take('ping').length, 0);
f.clear();

// Trial may submit intel but never receives it; quarantine cannot submit.
forward(trial, event());
assert.equal(member.take('ping').length, 1);
assert.equal(trial.take('ping').length, 0);
f.clear();
forward(quarantine, event());
assert.equal(b.take('ping').length, 0);
f.update(1, { quarantined: true });
forward(b, event());
assert.equal(member.take('ping').length, 0);
f.update(1, { quarantined: false });
f.clear();

// Stale, future, malformed, exhausted hops and disallowed control frames.
for (const e of [
  event('ping', { t: f.now() - 60001 }), event('ping', { t: f.now() + 5001 }),
  event('ping', { bridge: { id: 'exhausted', hops: 8 } }),
  event('ping', { bridge: { id: 'x'.repeat(161), hops: 0 } }),
  event('ping', { bridge: { id: 'fractional', hops: 0.5 } }),
  event('ping', { x: null }), event('ping', { x: 30000001 }),
  event('ping', { dim: 'not a dimension' }), event('snitch', { player: 'bad-name' }),
  ...['allegiances', 'focus', 'cut', 'welcome', 'intel_reset', 'state', 'positions'].map(type => event(type)),
]) forward(member, e);
assert.equal(a.messages.length, 0);
assert.equal(b.messages.length, 0);
const finalHop = event('ping', { bridge: { id: 'last-hop', hops: 7 } });
forward(member, finalHop);
assert.equal(a.take('ping')[0].bridge.hops, 8);
f.clear();

// Position snapshots preserve per-observation identity and timestamps, never refresh.
const pos = event('position', { name: 'Target', reporter: 'RemoteReporter' });
forward(member, pos);
f.tick();
let player = a.take('state').at(-1).players.find(p => p.name === 'Target');
assert.equal(player.t, pos.t);
assert.equal(player.reporter, 'Member');
assert.equal(player.bridge.id, pos.bridge.id);
assert.equal(player.bridge.hops, 1);
f.advance(2000);
forward(member, event('position', { name: 'Target', x: 9, t: pos.t - 100 }));
f.tick();
assert.equal(a.take('state').at(-1).players.find(p => p.name === 'Target').x, 1);
f.advance(8001);
f.tick();
assert.equal(a.take('state').at(-1).players.length, 0);
f.clear();
forward(member, pos);
assert.equal(a.take('state').length, 0);
// Locally submitted position observations get shared IDs and ordinary TTL behavior.
member.submit({ type: 'positions', reports: [{ subject: 'Target', x: 3, y: 64, z: 4,
  dim: 'minecraft:overworld', bridge: { id: 'client-position', hops: 0 } }] });
f.tick();
player = a.take('state').at(-1).players[0];
assert.equal(player.bridge.id, 'client-position');
assert.equal(player.t, f.now());
f.clear();

// Normal events honor a client ID across recipients and loopbacks.
const local = event('ping');
member.submit(local);
const localA = a.take('ping')[0], localB = b.take('ping')[0];
assert.deepEqual(localA.bridge, local.bridge);
assert.deepEqual(localA.bridge, localB.bridge);
forward(b, localA);
assert.equal(a.take('ping').length, 0);
f.clear();
for (const type of ['snitch', 'where']) {
  const e = event(type, { player: 'Target', message: 'observed' });
  forward(member, e);
  assert.equal(a.take(type)[0].t, e.t);
}
f.clear();

// Snitch compatibility: text-only alerts and unresolved dimensions remain
// forwardable without fabricating evidence; other event types stay strict.
const snitch = extra => event('snitch', { x: undefined, y: undefined, z: undefined,
  dim: undefined, message: 'Text alert ' + sequence, ...extra });
const text = snitch({ message: 'Sensor alert without a coordinate or player' });
forward(member, text);
const textOut = a.take('snitch')[0];
assert.equal(textOut.message, text.message);
assert.equal(textOut.t, text.t);
assert.equal(textOut.bridge.id, text.bridge.id);
assert.equal(textOut.from, 'Member');
for (const key of ['x', 'y', 'z', 'dim', 'world', 'player']) assert.equal(Object.hasOwn(textOut, key), false);
assert.equal(trial.take('snitch').length, 0);
f.clear();
forward(b, textOut);
assert.equal(a.take('snitch').length, 0);
// Semantic text dedupe remains active independently of identity dedupe.
forward(member, snitch({ message: text.message }));
assert.equal(a.take('snitch').length, 0);
f.clear();
for (const [world, dim] of [['world', 'minecraft:overworld'], ['world_nether', 'minecraft:the_nether'],
  ['world_the_end', 'minecraft:the_end']]) {
  const e = snitch({ world, player: 'LegacyHit', x: ++sequence, y: 64, z: 9 });
  forward(member, e);
  const out = a.take('snitch')[0];
  assert.equal(out.dim, dim);
  assert.equal(out.world, dim);
  assert.equal(out.t, e.t);
  assert.equal(out.bridge.id, e.bridge.id);
  assert.equal(vm.runInContext(`lastSeen.get('legacyhit').dim`, f.context), dim);
  f.clear();
}
for (const world of ['openintel:unknown', 'UnresolvedWorld', undefined]) {
  const e = snitch({ world, player: 'UnknownHit', x: ++sequence, y: 64, z: 9 });
  forward(member, e);
  const out = a.take('snitch')[0];
  assert.equal(out.world, world);
  assert.equal(Object.hasOwn(out, 'dim'), false);
  assert.equal(vm.runInContext(`lastSeen.has('unknownhit')`, f.context), false);
  f.clear();
}
for (const e of [snitch({ x: 1 }), snitch({ x: 1, y: 2 }), snitch({ x: 1, y: null, z: 2 }),
  snitch({ x: 30000001, y: 2, z: 3 }), snitch({ message: '' }), snitch({ message: 'x'.repeat(1501) }),
  snitch({ message: 'bad\u0000text' }), snitch({ player: 'bad-name' }), snitch({ world: { unsafe: true } }),
  event('where', { player: 'Target', dim: undefined, world: 'world' }),
]) forward(member, e);
assert.equal(a.messages.length, 0);
assert.equal(b.messages.length, 0);
// Text-only alerts use destination cut/quarantine restrictions too.
forward(a, snitch({ message: 'Cut reporter text' }));
assert.equal(b.take('snitch').length, 1);
assert.equal(member.take('snitch').length, 0);
f.clear();
forward(quarantine, snitch({ message: 'Quarantined text' }));
assert.equal(a.take('snitch').length, 0);
f.clear();

// Destination admin required, add/delete only; sync contains stroke identity.
const stroke = { id: 'bridge-stroke', tool: 'line', color: -1, width: 3, points: [1, 2, 3, 4] };
const add = event('wartable', { action: 'add', stroke });
forward(member, add);
forward(trial, add);
forward(quarantine, add);
assert.equal(JSON.parse(f.files.get('wartable.json')).strokes.length, 0);
forward(a, add);
const added = b.take('wartable')[0];
assert.equal(added.stroke.bridge.id, add.bridge.id);
assert.equal(added.stroke.t, add.t);
assert.equal(added.stroke.from, 'AdminOne');
assert.equal(member.take('wartable').length, 0);
const sync = f.login(1).take('wartable_sync')[0];
assert.equal(sync.strokes[0].bridge.id, add.bridge.id);
assert.equal(sync.strokes[0].t, add.t);
forward(a, event('wartable', { action: 'clear' }));
assert.equal(JSON.parse(f.files.get('wartable.json')).strokes.length, 1);
forward(b, event('wartable', { action: 'delete', id: stroke.id }));
assert.equal(JSON.parse(f.files.get('wartable.json')).strokes.length, 0);
f.clear();
a.submit({ type: 'wartable', action: 'add', stroke: { ...stroke, id: 'local-stroke' },
  bridge: { id: 'local-stroke-event', hops: 0 } });
assert.equal(b.take('wartable')[0].stroke.bridge.id, 'local-stroke-event');
assert.equal(JSON.parse(f.files.get('wartable.json')).strokes[0].bridge.id, 'local-stroke-event');
f.clear();

// Server-minted IDs are stable across recipients, including legacy clients.
f.clear();
member.submit({ type: 'ping', x: 1, y: 64, z: 2, dim: 'minecraft:overworld' });
const minted = a.take('ping')[0];
assert.equal(minted.bridge.hops, 0);
assert.equal(minted.bridge.id, b.take('ping')[0].bridge.id);
f.clear();
// Stale snitch/where and position evidence, malformed timestamps and names.
for (const e of [event('snitch', { player: 'Target', t: f.now() - 600001 }),
  event('where', { player: 'Target', t: f.now() - 6 * 60 * 60 * 1000 - 1 }),
  event('position', { name: 'Target', t: f.now() - 10001 }),
  event('position', { name: 12345 }), event('ping', { t: 'now' }),
]) forward(member, e);
assert.equal(a.messages.length, 0);
// Invalid frames cannot crash or mutate anything.
for (const e of [null, [], {}, { type: 'bridge' }]) forward(member, e);
member.submit(null);
member.submit([]);
f.update(2, { disabled: true });
forward(member, event());
assert.equal(member.readyState, 3);
// Role announcements reflect current destination roles, including demotion to
// trial and same-visibility-lane changes (captain/member), without reconnecting.
f.clear();
f.update(1, { role: 'trial' });
assert.equal(b.take('allegiances').at(-1).role, 'trial');
f.update(1, { role: 'captain' });
assert.equal(b.take('allegiances').at(-1).role, 'captain');
f.update(1, { role: 'member' });
assert.equal(b.take('allegiances').at(-1).role, 'member');
// Cache bounds/TTL are exercised against the actual VM, not a copied helper.
vm.runInContext(`bridgeSeen.clear(); for (let i = 0; i < 10020; i++) claimBridge({id: 'bounded-' + i});`, f.context);
assert.equal(vm.runInContext('bridgeSeen.size', f.context), 10000);
f.advance(6 * 60 * 60 * 1000 + 1);
vm.runInContext(`claimBridge({id: 'after-expiry'})`, f.context);
assert.equal(vm.runInContext('bridgeSeen.size', f.context), 1);
console.log('Bridge relay tests passed: identity, loop dedupe, timestamps, validation, permissions, cut, persistence and bounded TTL');
