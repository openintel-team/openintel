async function main() {
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const visibility = require('./visibility');

// Real server handlers in a VM with fake sockets, a fake clock, and manual timers.
let now = 1800000000000;
const users = [
  { name: 'Viewer', token: 'v', role: 'member' },
  { name: 'Legacy', token: 'l', role: 'member' },
  { name: 'Reporter', token: 'r', role: 'member' },
  { name: 'Scout', token: 's', role: 'member' },
  { name: 'Trial', token: 't', role: 'trial' },
];
const files = new Map(Object.entries({
  'config.json': { minecraftServer: 'play.example.net', discord: {}, webhooks: {}, staleMs: 10000 },
  'users.json': { users }, 'allegiances.json': { allies: [], enemies: [], focus: [] },
  'wartable.json': { strokes: [] },
}).map(([k, v]) => [k, JSON.stringify(v)]));
let server;
const timers = [];
const app = { use() {}, get() {}, put() {} };
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
  __dirname, Buffer, Date: Clock, console: { log() {}, error() {} }, process: { argv: [], pid: 1, env: {} },
  setInterval(fn, ms) { timers.push({ fn, ms }); }, fetch() { throw new Error('No live services allowed'); },
  require(name) {
    if (name === './visibility') return visibility;
    if (name === './authentication') return { createIdentityVerification: (ws, options) => require('./authentication').createIdentityVerification(ws, { ...options, verify: async name => ({ name, id: 'a'.repeat(32) }) }) };
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
assert.equal(timers.length, 2, 'full-list timer and delta flush timer');
assert.equal(timers[0].ms, 250, 'legacy full list keeps the 250 ms default');
assert.equal(timers[1].ms, 50, 'delta pushes default to at most 20 Hz');
const fullTick = () => timers[0].fn();
const flushTick = () => timers[1].fn();

async function login(index, positionVersion) {
  const socket = new Socket(); server.clients.add(socket);
  server.emit('connection', socket, { socket: { remoteAddress: '127.0.0.1' } });
  const hello = { type: 'hello', token: users[index].token, minecraftServer: 'play.example.net' };
  if (positionVersion != null) hello.positionVersion = positionVersion;
  socket.submit(hello);
  socket.submit({ type: 'authenticate', clientNonce: 'b'.repeat(40) });
  await new Promise(resolve => setImmediate(resolve));
  const welcome = socket.take('welcome')[0];
  assert.equal(welcome.positionVersion, 2, 'welcome advertises position deltas to every client');
  return socket;
}
const viewer = await login(0, 2);
const legacy = await login(1);
const reporter = await login(2, 2);
const scout = await login(3, 2);
const trial = await login(4, 2);
const all = [viewer, legacy, reporter, scout, trial];
const clear = () => all.forEach(s => { s.messages = []; });
const advance = (ms) => { now += ms; };
const report = (socket, subject, x, extra = {}) => socket.submit({ type: 'positions', reports: [
  { subject, x, y: 64, z: 0, dim: 'minecraft:overworld', ...extra }] });
const rows = (socket) => socket.take('pos').flatMap(m => m.p.map(row => ({
  name: row[0], x: row[1], y: row[2], z: row[3], dim: m.s[row[4]], age: row[5], reporter: m.s[row[6]],
  bridge: row[7], hops: row[8], length: row.length })));
// First full-list tick reaches everyone; later ticks pace delta clients.
fullTick();
clear();

// A report reaches delta clients at once, compact and age-adjusted.
report(reporter, 'Target', 10.123456, { age: 40, vx: 4.256, vz: -1.5, bridge: { id: 'obs-1', hops: 0 } });
let seen = rows(viewer);
assert.equal(seen.length, 1, 'delta client gets the change immediately');
assert.deepEqual(seen[0], { name: 'Target', x: 10.12, y: 64, z: 0, dim: 'minecraft:overworld', age: 40,
  reporter: 'Reporter', bridge: 'obs-1', hops: 0, length: 9 });
assert.equal(legacy.take('pos').length, 0, 'older clients never see the new message type');
assert.equal(legacy.take('state').length, 0, 'older clients wait for the full list');
assert.equal(trial.take('pos').length, 0, 'trial stays send-only');
assert.equal(rows(reporter).length, 1, 'the reporter gets its own observation like everyone else');
assert.equal(rows(scout).length, 1);
clear();

// The push rate is capped: a second change inside 50 ms waits for the timer.
advance(10);
report(reporter, 'Target', 12);
assert.equal(viewer.take('pos').length, 0, 'second push inside the window is held');
advance(40);
flushTick();
seen = rows(viewer);
assert.equal(seen.length, 1);
assert.equal(seen[0].x, 12);
clear();
// Unchanged reports do not generate pushes.
advance(60);
report(reporter, 'Target', 12);
flushTick();
assert.equal(viewer.take('pos').length, 0, 'an unchanged position is not pushed again');
clear();

// Full list: every 250 ms for older clients, every second for delta clients.
advance(1000);
fullTick();
const fullForLegacy = legacy.take('state');
assert.equal(fullForLegacy.length, 1);
const legacyTarget = fullForLegacy[0].players.find(p => p.name === 'Target');
assert.equal(legacyTarget.x, 12);
assert.equal(legacyTarget.age, now - legacyTarget.t, 'full list carries the observation age too');
assert.equal(fullForLegacy[0].replace, true);
assert.equal(viewer.take('state').length, 1, 'delta clients get a full list each second');
advance(250);
fullTick();
assert.equal(legacy.take('state').length, 1);
assert.equal(viewer.take('state').length, 0, 'not every 250 ms');
advance(750);
fullTick();
assert.equal(viewer.take('state').length, 1);
clear();

// A player's own report beats another client's slightly newer view of them.
advance(100);
report(scout, 'Scout', 100, { bridge: { id: 'self-1', hops: 0 } });
advance(100);
report(reporter, 'Scout', 140, { bridge: { id: 'other-1', hops: 0 } });
advance(100);
flushTick();
let scoutRows = rows(viewer).filter(r => r.name === 'Scout');
assert.equal(scoutRows.at(-1).x, 100, 'self-report kept while fresh');
assert.equal(scoutRows.at(-1).reporter, 'Scout');
advance(1200);
report(reporter, 'Scout', 180, { bridge: { id: 'other-2', hops: 0 } });
scoutRows = rows(viewer).filter(r => r.name === 'Scout');
assert.equal(scoutRows.at(-1).x, 180, 'a stale self-report gives way to a fresh sighting');
clear();

// Far players (another dimension or beyond 1000 blocks) are paced to 2 Hz per recipient.
advance(100);
report(viewer, 'Viewer', 0);
advance(100);
report(reporter, 'Distant', 5000, { bridge: { id: 'far-1', hops: 0 } });
assert.equal(rows(viewer).filter(r => r.name === 'Distant').length, 1, 'first sighting goes out at once');
assert.equal(rows(reporter).filter(r => r.name === 'Distant').length, 1, 'Reporter has no own position, so nothing is far for it');
advance(100);
report(reporter, 'Distant', 5010, { bridge: { id: 'far-2', hops: 0 } });
assert.equal(rows(viewer).filter(r => r.name === 'Distant').length, 0, 'far change held for the viewer');
assert.equal(rows(reporter).filter(r => r.name === 'Distant').length, 1, 'other recipients are not held back');
advance(100);
report(reporter, 'Nearby', 50, { bridge: { id: 'near-1', hops: 0 } });
assert.equal(rows(viewer).filter(r => r.name === 'Nearby').length, 1, 'near players still go out at once');
advance(300);
flushTick();
const held = rows(viewer).filter(r => r.name === 'Distant');
assert.equal(held.length, 1, 'held far change goes out after 500 ms');
assert.equal(held[0].x, 5010);
clear();

// Position IDs use their own cache and never evict snitch/ping/War Table IDs.
const bridgeBefore = vm.runInContext('bridgeSeen.size', context);
for (let i = 0; i < 5; i++) { advance(50); report(reporter, 'Churn', i); }
assert.equal(vm.runInContext('bridgeSeen.size', context), bridgeBefore, 'position uploads leave bridgeSeen alone');
assert(vm.runInContext('positionBridgeSeen.size', context) >= 5);
const minted = vm.runInContext(`positions.get('churn@reporter').bridge.id`, context);
assert.equal(minted.length, 12, 'relay-minted position IDs are short');
// Bridged positions also use the position cache.
advance(60);
const bridged = { type: 'position', name: 'Bridged', x: 7, y: 64, z: 7, dim: 'minecraft:overworld',
  t: now - 20, vx: 2, vz: 3, bridge: { id: 'bridged-1', hops: 0 } };
reporter.submit({ type: 'bridge', event: bridged });
const bridgedRow = rows(viewer).find(r => r.name === 'Bridged');
assert.equal(bridgedRow.hops, 1);
assert.equal(bridgedRow.length, 9, 'velocity from an older client is not forwarded');
assert.equal(bridgedRow.age, 20);
assert.equal(vm.runInContext('bridgeSeen.size', context), bridgeBefore);
assert(vm.runInContext(`positionBridgeSeen.has('bridged-1')`, context));
clear();

// Bounds: ages are clamped, uploads are rate-limited.
advance(100);
report(reporter, 'Clamp', 1, { age: 999999 });
const clamped = vm.runInContext(`positions.get('clamp@reporter')`, context);
assert.equal(now - clamped.t, 1000, 'report age is capped at one second');
assert.equal(clamped.vx, undefined, 'velocity is never stored');
advance(1000);
const before = vm.runInContext('positions.size', context);
for (let i = 0; i < 20; i++) report(legacy, 'Flood' + i, i);
assert.equal(vm.runInContext('positions.size', context) - before, 8, 'a burst is allowed, a flood is not');
advance(25);
report(legacy, 'Refill', 1);
assert.equal(vm.runInContext('positions.size', context) - before, 9, 'one token returns every 25 ms');
console.log('Position relay tests passed: immediate capped deltas, compact rows, full-list pacing, older clients, self-report preference, far pacing, separate ID cache, bounds and rate limit');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
