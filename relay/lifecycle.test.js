const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { once } = require("node:events");
const { spawnSync } = require("node:child_process");
const { WebSocket } = require("ws");
const { createRelay } = require("./server");
const { createStore } = require("./src/storage");
const { hashToken } = require("./src/common");
const { validateConfig } = require("./src/config");
async function main() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "openintel lifecycle "));
  const relays = [];
  const quiet = { log() {}, error() {} };
  try {
    const make = (dir, extra = {}) => {
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, "users.json"), JSON.stringify({ users: [{ name: "Tester", role: "admin", tokenHash: hashToken("test-token") }] }));
      const relay = createRelay({ dataDir: dir, config: { minecraftServer: "play.example.net", host: "127.0.0.1", port: 0 },
        logger: quiet, verify: async name => ({ name, id: "a".repeat(32) }), ...extra });
      relays.push(relay); return relay;
    };
    const a = make(path.join(root, "a")), b = make(path.join(root, "b"));
    assert.equal(a.status, "new");
    assert(!fs.existsSync(path.join(root, "a", "relay.lock")), "construction has no side effects");
    await Promise.all([a.start(), b.start()]);
    assert.notEqual(a.address().port, b.address().port);
    const health = await fetch(`http://127.0.0.1:${a.address().port}/readyz`);
    assert.equal(health.status, 200); assert.deepEqual(await health.json(), { status: "ready" });
    const socket = new WebSocket(`ws://127.0.0.1:${a.address().port}/openintel-relay`);
    await once(socket, "open");
    const next = () => new Promise(resolve => socket.once("message", raw => resolve(JSON.parse(raw))));
    let message = next();
    socket.send(JSON.stringify({ type: "hello", token: "test-token", minecraftServer: "play.example.net" }));
    assert.equal((await message).type, "auth_challenge");
    message = next(); socket.send(JSON.stringify({ type: "authenticate", clientNonce: "b".repeat(40) }));
    assert.equal((await message).type, "welcome");
    assert.equal(b.services.sessions.socketsFor("Tester").length, 0);
    const closed = once(socket, "close"); await a.stop(); await closed;
    await a.stop(); assert.equal(a.status, "stopped");
    assert(!fs.existsSync(path.join(root, "a", "relay.lock")));
    const broken = make(path.join(root, "broken"));
    fs.writeFileSync(path.join(root, "broken", "users.json"), "{broken");
    await assert.rejects(broken.start(), /Invalid JSON/);
    assert.equal(fs.readFileSync(path.join(root, "broken", "users.json"), "utf8"), "{broken");
    assert(!fs.existsSync(path.join(root, "broken", "relay.lock")));
    const occupied = make(path.join(root, "occupied"), { config: { minecraftServer: "play.example.net", host: "127.0.0.1", port: b.address().port } });
    await assert.rejects(occupied.start(), /EADDRINUSE/);
    assert(!fs.existsSync(path.join(root, "occupied", "relay.lock")));
    let cancelled = false;
    const pending = make(path.join(root, "pending"), { verify: (name, id, signal) => new Promise((resolve, reject) => {
      signal.addEventListener("abort", () => { cancelled = true; reject(new Error("aborted")); }, { once: true });
    }) });
    await pending.start();
    const waiting = new WebSocket(`ws://127.0.0.1:${pending.address().port}`);
    await once(waiting, "open");
    const challenge = once(waiting, "message");
    waiting.send(JSON.stringify({ type: "hello", token: "test-token", minecraftServer: "play.example.net" }));
    await challenge;
    waiting.send(JSON.stringify({ type: "authenticate", clientNonce: "b".repeat(40) }));
    await new Promise(resolve => setTimeout(resolve, 30));
    await pending.stop();
    assert(cancelled, "shutdown aborts pending identity requests");
    const { EventEmitter } = require("node:events");
    const optional = make(path.join(root, "optional"), {
      config: { minecraftServer: "play.example.net", host: "127.0.0.1", port: 0, discord: { botToken: "test" } },
      discordFactory: () => Object.assign(new EventEmitter(), { login: async () => { throw new Error("offline"); }, destroy() {} }),
    });
    await optional.start();
    assert.equal(optional.status, "running");
    assert.equal(optional.discordStatus, "degraded", "Discord failure does not take down core service");
    const lock = createStore(path.join(root, "b"));
    assert.throws(() => lock.lock(), /locked/);
    assert.throws(() => validateConfig({ minecraftServer: "x", port: -1 }), /port/);
    const local = path.join(root, "setup"), config = path.join(local, "config.json");
    const cli = (...args) => spawnSync(process.execPath, ["src/cli.js", ...args, "--config", config, "--data-dir", local],
      { cwd: __dirname, encoding: "utf8" });
    const initialized = cli("init", "--minecraft-server", "play.example.net", "--admin-name", "AdminOne", "--noninteractive");
    assert.equal(initialized.status, 0, initialized.stderr);
    const first = fs.readFileSync(path.join(local, "users.json"), "utf8");
    assert(!first.includes('"token"')); assert(first.includes("tokenHash"));
    assert.equal(cli("init", "--noninteractive").status, 0);
    assert.equal(fs.readFileSync(path.join(local, "users.json"), "utf8"), first);
    const held = createStore(local).lock();
    assert.notEqual(cli("invite", "--name", "Another", "--token-file", path.join(root, "invite.txt")).status, 0);
    held();
    assert.equal(cli("check-config").status, 0);
    fs.writeFileSync(path.join(local, "relay.lock"), String(process.pid));
    assert.notEqual(cli("unlock-stale").status, 0, "cannot steal a live writer lock");
    fs.writeFileSync(path.join(local, "relay.lock"), "2147483647");
    assert.equal(cli("unlock-stale").status, 0, "dead writer lock can be recovered");
    assert(!fs.existsSync(path.join(local, "relay.lock")));
    const migrated = path.join(root, "migrated");
    const migrationArgs = ["src/cli.js", "migrate", "--from", local, "--config", path.join(migrated, "config.json"),
      "--data-dir", path.join(migrated, "data")];
    const migrate = (...extra) => spawnSync(process.execPath, [...migrationArgs, ...extra], { cwd: __dirname, encoding: "utf8" });
    assert.equal(migrate("--dry-run").status, 0);
    assert(!fs.existsSync(migrated), "dry run must not create state/directories");
    assert.equal(migrate().status, 0);
    assert.equal(fs.readFileSync(path.join(migrated, "data", "users.json"), "utf8"), first);
    assert(!fs.existsSync(path.join(migrated, "data", "initial-token.txt")), "private disclosure files are not migration inputs");
    assert.notEqual(migrate().status, 0, "migration must not overwrite existing state");
    fs.writeFileSync(config, "{");
    assert.notEqual(cli("check-config").status, 0);
    console.log("Lifecycle/setup tests passed: real sockets, isolation, request cancellation, optional Discord, corrupt state, locks, initialization and migration");
  } finally {
    await Promise.all(relays.map(r => r.stop()));
    fs.rmSync(root, { recursive: true, force: true });
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
