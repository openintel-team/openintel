const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { isAdmin, isTrial, canReceiveIntel, visiblePositions, visibilityKey } = require("./visibility");

const USER_ROLES = ["trial", "member", "operator", "captain", "admin"];
const MINECRAFT_NAME = /^[A-Za-z0-9_]{3,16}$/;
const lower = (s) => String(s).toLowerCase();
const normalizeMinecraftServer = (value) => {
  let normalized = lower(value ?? "").trim().replace(/^[a-z]+:\/\//, "").split("/")[0];
  while (normalized.endsWith(".")) normalized = normalized.slice(0, -1);
  if (normalized.endsWith(":25565")) normalized = normalized.slice(0, -6);
  return normalized;
};
const validName = (name) => MINECRAFT_NAME.test(String(name ?? ""));
const validRole = (role) => USER_ROLES.includes(lower(role));
const roleRank = (role) => Math.max(0, USER_ROLES.indexOf(lower(role ?? "member")));
const hasTier = (role, required) => roleRank(role) >= roleRank(required);
// users.json keeps only SHA-256 digests of relay tokens. A digest fingerprint
// matches the fingerprint of the token it came from, so audit entries written
// before and after hashing still line up.
function hashToken(token) {
  return crypto.createHash("sha256").update(String(token)).digest("hex");
}
const TOKEN_HASH = /^[a-f0-9]{64}$/;
const hashFingerprint = (digest) => typeof digest === "string" && TOKEN_HASH.test(digest.toLowerCase())
  ? digest.toLowerCase().slice(0, 12) : null;
const tokenFingerprint = (token) => token ? hashToken(token).slice(0, 12) : null;
// Replaces each plaintext `token` with `tokenHash` and drops malformed entries.
// A plaintext token wins over an older digest, so editing the file by hand to
// issue a new token works.
function sealUsers(data) {
  let changed = false;
  const users = (Array.isArray(data?.users) ? data.users : []).filter((user) => {
    const keep = user && typeof user === "object" && !Array.isArray(user);
    if (!keep) changed = true;
    return keep;
  }).map((user) => {
    if (!Object.hasOwn(user, "token")) return user;
    changed = true;
    const plain = typeof user.token === "string" && user.token.length > 0 ? user.token : null;
    const sealed = {};
    for (const [key, value] of Object.entries(user)) {
      if (key === "tokenHash" && plain) continue;
      if (key === "token") { if (plain) sealed.tokenHash = hashToken(plain); continue; }
      sealed[key] = value;
    }
    return sealed;
  });
  return { data: { ...(data && typeof data === "object" ? data : {}), users }, changed };
}
function safeAuditValue(value, key = "") {
  if (value == null || typeof value !== "object") {
    if (key === "tokenHash" && value != null) return `sha256:${hashFingerprint(value)}`;
    return /token|secret/i.test(key) && value != null ? `sha256:${tokenFingerprint(value)}` : value;
  }
  if (Array.isArray(value)) return value.map((item) => safeAuditValue(item));
  return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, safeAuditValue(v, k)]));
}
function pageText(label, entries, requestedPage = 1, pageSize = 20) {
  const pages = Math.max(1, Math.ceil(entries.length / pageSize));
  const page = Math.min(pages, Math.max(1, Number.parseInt(requestedPage, 10) || 1));
  const rows = entries.slice((page - 1) * pageSize, page * pageSize);
  return `${label} — page ${page}/${pages} (${entries.length})\n${rows.length ? rows.join("\n") : "(none)"}`;
}
function consumeRateLimit(entries, key, now, cooldown) {
  const previous = entries.get(key);
  if (previous && now - previous.last < cooldown) {
    previous.suppressed++;
    return { allowed: false, suppressed: previous.suppressed };
  }
  const suppressed = previous?.suppressed ?? 0;
  entries.set(key, { last: now, suppressed: 0 });
  return { allowed: true, suppressed };
}
// The admin HTTP API stays off unless adminToken is a real secret: a missing
// value, the example placeholder, or a short string would otherwise let any
// caller replace users and allegiances.
const ADMIN_TOKEN_PLACEHOLDERS = new Set(["CHANGE_ME", "CHANGE_ME_LONG_RANDOM_STRING"]);
const ADMIN_TOKEN_MIN_LENGTH = 32;
function adminTokenDigest(token) {
  if (typeof token !== "string" || token.length < ADMIN_TOKEN_MIN_LENGTH
      || ADMIN_TOKEN_PLACEHOLDERS.has(token)) return null;
  return crypto.createHash("sha256").update(token).digest();
}
// Compares digests so neither the length nor the contents leak through timing.
function adminTokenMatches(expectedDigest, supplied) {
  if (!expectedDigest || typeof supplied !== "string" || !supplied) return false;
  return crypto.timingSafeEqual(expectedDigest, crypto.createHash("sha256").update(supplied).digest());
}
function runSelfTest() {
  const assert = require("assert");
  assert.equal(normalizeMinecraftServer("PLAY.EXAMPLE.NET:25565"), "play.example.net");
  assert.equal(normalizeMinecraftServer("minecraft://play.example.net/"), "play.example.net");
  assert(validName("Player_123"));
  assert(!validName("ab"));
  assert(!validName("bad-name"));
  assert(validRole("operator"));
  assert(validRole("trial"));
  assert(!validRole("owner"));
  assert(!hasTier("trial", "member"));
  assert(hasTier("member", "trial"));
  assert(hasTier("admin", "captain"));
  assert(!hasTier("operator", "captain"));
  assert.equal(pageText("users", ["a", "b", "c"], 2, 2), "users — page 2/2 (3)\nc");
  const limits = new Map();
  assert(consumeRateLimit(limits, "user", 1000, 5000).allowed);
  assert(!consumeRateLimit(limits, "user", 2000, 5000).allowed);
  assert.equal(consumeRateLimit(limits, "user", 7000, 5000).suppressed, 1);
  const safe = safeAuditValue({ token: "test-token", nested: { botToken: "bot" } });
  assert.equal(safe.token, `sha256:${tokenFingerprint("test-token")}`);
  assert(!JSON.stringify(safe).includes("test-token"));
  const digest = hashToken("test-token");
  assert.equal(safeAuditValue({ tokenHash: digest }).tokenHash, `sha256:${tokenFingerprint("test-token")}`);
  const sealed = sealUsers({ users: [
    { name: "Plain", token: "plain-token", role: "member" },
    { name: "Replaced", tokenHash: "0".repeat(64), token: "new-token" },
    { name: "Hashed", tokenHash: digest },
    { name: "Empty", token: "" },
    null, ["bad"],
  ] });
  assert(sealed.changed);
  assert.deepEqual(sealed.data.users.map((u) => u.name), ["Plain", "Replaced", "Hashed", "Empty"]);
  assert.deepEqual(Object.keys(sealed.data.users[0]), ["name", "tokenHash", "role"]);
  assert.equal(sealed.data.users[0].tokenHash, hashToken("plain-token"));
  assert.equal(sealed.data.users[1].tokenHash, hashToken("new-token"));
  assert.equal(sealed.data.users[2].tokenHash, digest);
  assert.equal(Object.hasOwn(sealed.data.users[3], "tokenHash"), false);
  assert(!JSON.stringify(sealed.data).includes("plain-token"));
  assert.equal(sealUsers({ users: [{ name: "Hashed", tokenHash: digest }] }).changed, false);
  for (const weak of [undefined, null, "", "short", "CHANGE_ME", "CHANGE_ME_LONG_RANDOM_STRING", 42])
    assert.equal(adminTokenDigest(weak), null);
  const strong = "s".repeat(ADMIN_TOKEN_MIN_LENGTH);
  assert(adminTokenMatches(adminTokenDigest(strong), strong));
  for (const supplied of [undefined, "", "s", strong + "s", ["x"]])
    assert(!adminTokenMatches(adminTokenDigest(strong), supplied));
  assert(!adminTokenMatches(null, strong));
  console.log("relay self-test passed");
}
if (process.argv.includes("--self-test")) {
  runSelfTest();
  process.exit(0);
}

/**
 * OpenIntel relay server
 * ----------------------
 * - Authenticates approved users (users.json) over WebSocket
 * - Merges position reports from every client, broadcasts shared state ~4x/sec
 * - Pushes allegiance lists live to clients when admins change them
 * - Fires the ALERT webhook (with role ping) when an enemy enters anyone's
 *   render — deduplicated + cooldown, so one enemy = one ping
 * - Fires the ADMIN webhook for connects, auth failures, and admin changes
 *   (your admin Discord channel becomes the audit log / "admin panel" feed).
 *   Rejections name a per-run client tag, never the remote address.
 *
 * Run:  npm install && node server.js
 */
const http = require("http");
const express = require("express");
const { WebSocketServer } = require("ws");
const { attachRelayTransport } = require("./transport");
const { createIdentityVerification } = require("./authentication");

// Unset keeps the server directory. Tests set this so fixtures stay out of tracked JSON.
const relayHome = process.env.OPENINTEL_RELAY_HOME;
const filePath = (name) => path.join(relayHome ? relayHome : __dirname, name);
const CONFIG = JSON.parse(fs.readFileSync(filePath("config.json"), "utf8"));
const MINECRAFT_SERVER = normalizeMinecraftServer(CONFIG.minecraftServer);
if (!MINECRAFT_SERVER) throw new Error("config.minecraftServer is required");
let USERS = loadJson("users.json", { users: [] });
{
  const sealed = sealUsers(USERS);
  USERS = sealed.data;
  if (sealed.changed) {
    saveJson("users.json", USERS);
    console.log("users.json: replaced plaintext relay tokens with SHA-256 digests");
  }
}
let ALLEGIANCES = loadJson("allegiances.json", { allies: [], enemies: [] });
// War Table: persistent admin-drawn map annotations shared with all clients.
let WARTABLE = loadJson("wartable.json", { strokes: [] });
const WARTABLE_MAX_STROKES = 400;
const WARTABLE_TOOLS = new Set(["pen", "line", "arrow", "area", "label", "marker"]);

function wartableSync(recipient) {
  return { type: "wartable_sync", strokes: WARTABLE.strokes.filter(
    stroke => canReceiveIntel(recipient, stroke.from, null, userByName)) };
}

// Mirrors the client's Stroke validation — bounds-checks before anything
// an admin draws gets persisted or fanned out.
function validStroke(s) {
  if (!s || typeof s !== "object") return false;
  if (typeof s.id !== "string" || !/^[A-Za-z0-9_-]{4,64}$/.test(s.id)) return false;
  if (!WARTABLE_TOOLS.has(String(s.tool ?? "").toLowerCase())) return false;
  if (!Array.isArray(s.points) || s.points.length < 2 || s.points.length > 1024
      || s.points.length % 2 !== 0) return false;
  if (!s.points.every(v => Number.isFinite(v) && Math.abs(v) <= 1e6)) return false;
  if (!(typeof s.color === "number" && Number.isInteger(s.color))) return false;
  if (!(typeof s.width === "number" && s.width >= 1 && s.width <= 64)) return false;
  if (s.label != null && (typeof s.label !== "string" || s.label.length > 96)) return false;
  if (String(s.tool).toLowerCase() === "label" && (s.label == null || s.label === "")) return false;
  return true;
}

function loadJson(name, fallback) {
  try { return JSON.parse(fs.readFileSync(filePath(name), "utf8")); } catch { return fallback; }
}
function saveJson(name, data) {
  const destination = filePath(name);
  const temporary = `${destination}.${process.pid}.tmp`;
  const mode = name === "users.json" ? 0o600 : 0o640;
  fs.writeFileSync(temporary, JSON.stringify(data, null, 2), { mode });
  fs.chmodSync(temporary, mode);
  fs.renameSync(temporary, destination);
}

const userByToken = (token) => {
  if (typeof token !== "string" || !token) return undefined;
  const digest = hashToken(token);
  return USERS.users.find((u) => !u.disabled && typeof u.tokenHash === "string"
    && u.tokenHash.toLowerCase() === digest);
};
const userByName = (name) => USERS.users.find((u) => lower(u.name) === lower(name));
const userNames = () => USERS.users.filter((u) => !u.disabled).map((u) => u.name);
const isEnemy = (name) =>
  ALLEGIANCES.enemies.map(lower).includes(lower(name)) ||
  (ALLEGIANCES.focus ?? []).map(lower).includes(lower(name));
const isOperator = (role) => hasTier(role, "operator");

// ---------------------------------------------------------------- webhooks
async function postWebhook(url, payload) {
  if (!url) return;
  try {
    await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
  } catch (e) {
    console.error("webhook failed:", e.message);
  }
}
const adminLog = (msg) =>
  postWebhook(CONFIG.webhooks.admin, {
    content: `🛠️ ${msg}`, username: "OpenIntel Admin", allowed_mentions: { parse: [] },
  });
const recentAudit = [];
function audit({ actor, tier, action, target = null, source = {}, before = null, after = null, success = true, reason = null }) {
  const entry = safeAuditValue({
    timestamp: new Date().toISOString(), actor, tier, action, target,
    source: { guild: source.guild ?? null, channel: source.channel ?? null },
    before, after, success: Boolean(success), reason,
  });
  try { fs.appendFileSync(filePath("audit.jsonl"), `${JSON.stringify(entry)}\n`, { mode: 0o600 }); }
  catch (e) { console.error("audit write failed:", e.message); }
  recentAudit.unshift(entry);
  if (recentAudit.length > 20) recentAudit.length = 20;
  const outcome = entry.success ? "succeeded" : `failed${entry.reason ? `: ${entry.reason}` : ""}`;
  adminLog(`**${entry.actor}** (${entry.tier}) — \`${entry.action}\`${entry.target ? ` on **${entry.target}**` : ""} ${outcome}`);
  return entry;
}

const authRejectionLogs = new Map();
// Rejections name a short per-run tag instead of the address, so admins can
// spot one source retrying without IPs landing in Discord. The key is random
// per process: a bare hash of an IPv4 address is trivially reversible.
const CLIENT_TAG_KEY = crypto.randomBytes(32);
const clientTag = (address) => crypto.createHmac("sha256", CLIENT_TAG_KEY)
  .update(String(address ?? "unknown")).digest("hex").slice(0, 8);
function logAuthRejection(key, message, address = null) {
  const decision = consumeRateLimit(authRejectionLogs, key, Date.now(),
    CONFIG.authRejectionLogCooldownMs ?? 300_000);
  if (!decision.allowed) return;
  const suffix = decision.suppressed ? ` (${decision.suppressed} repeat(s) suppressed)` : "";
  adminLog(message + suffix);
  // Opt-in, console only: raw addresses never go to the webhook.
  if (CONFIG.logRejectedIps === true && address != null) {
    console.log(`relay rejection from ${address} (client ${clientTag(address)})${suffix}`);
  }
  if (authRejectionLogs.size > 500) {
    const cutoff = Date.now() - (CONFIG.authRejectionLogCooldownMs ?? 300_000) * 2;
    for (const [entryKey, value] of authRejectionLogs) {
      if (value.last < cutoff) authRejectionLogs.delete(entryKey);
    }
  }
}

const alertCooldowns = new Map(); // enemyName -> last ping ms
const recentSnitch = new Map();   // dedupe key -> first seen ms

// Semantic dedupe: the same tripper at the same coords is ONE hit, even when
// the in-game chat line and the Discord relay post word it differently.
function snitchDedupeKey(m) {
  if (m.x != null && m.y != null && m.z != null)
    return `${String(m.player ?? "?").toLowerCase()}@${m.x},${m.y},${m.z}`;
  return String(m.message ?? "");
}
function dedupeSnitch(m, audience = "public") {
  const key = audience + ":" + snitchDedupeKey(m);
  if (!key) return true;
  const now = Date.now();
  const last = recentSnitch.get(key) ?? 0;
  if (now - last < 10_000) return false;
  recentSnitch.set(key, now);
  if (recentSnitch.size > 200) {
    for (const [k, v] of recentSnitch) if (now - v > 60_000) recentSnitch.delete(k);
  }
  return true;
}
function enemyAlert(enemy, x, z, dim, reporter) {
  const now = Date.now();
  const last = alertCooldowns.get(lower(enemy)) ?? 0;
  if (now - last < (CONFIG.alertCooldownMs ?? 300000)) return;
  alertCooldowns.set(lower(enemy), now);

  const ping = CONFIG.webhooks.alertRoleId ? `<@&${CONFIG.webhooks.alertRoleId}> ` : "";
  postWebhook(CONFIG.webhooks.alerts, {
    username: "OpenIntel",
    content:
      `${ping}🔴 **${enemy}** spotted at **${Math.round(x)}, ${Math.round(z)}** ` +
      `(${dim.replace("minecraft:", "")}) — reported by ${reporter}`,
    allowed_mentions: { parse: ["roles"] },
  });
}

// ---------------------------------------------------------------- state
// subject(lower) -> { name, x, y, z, dim, t, reporter }
const positions = new Map();
const STALE_MS = CONFIG.staleMs ?? 10000;
// Every position rate is a relay setting, clamped to a sane range.
const configMs = (value, fallback, min, max) =>
  Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : fallback;
// Full player list to clients without position deltas (older mods): the legacy cadence.
const BROADCAST_MS = configMs(CONFIG.broadcastIntervalMs, 250, 50, 10000);
// Clients that announce positionVersion 2 get changed players as they arrive,
// at most once per POSITION_FLUSH_MS (the max push rate, 50 ms = 20 Hz), plus a
// full list every FULL_SYNC_MS so removals and idle players stay current.
const POSITION_PROTOCOL = 2;
const POSITION_FLUSH_MS = configMs(CONFIG.positionFlushMs, 50, 10, 1000);
const FULL_SYNC_MS = configMs(CONFIG.fullSyncMs, 1000, 250, 5000);
// Players farther than NEAR_POSITION_RANGE blocks from the recipient, or in
// another dimension, are pushed at most once per FAR_POSITION_MS. 0 turns this off.
const FAR_POSITION_MS = configMs(CONFIG.farPositionMs, 500, 0, 5000);
const NEAR_POSITION_RANGE = configMs(CONFIG.nearPositionRange, 1000, 0, 30000000);
// Sustained position uploads from one connection are capped at one per this
// many ms (bursts of a few are allowed).
const MIN_REPORT_INTERVAL_MS = configMs(CONFIG.minReportIntervalMs, 25, 0, 1000);
// Reporters may say how old an observation already was when sent (ms).
const MAX_REPORT_AGE_MS = 1000;
const MAX_REPORT_SPEED = 100;   // blocks per second, horizontal velocity bound

// Latest intel of any kind per player — feeds !where and war-table marks.
// name(lower) -> { name, x, y, z, dim, t, kind, reporter }
const lastSeen = new Map();
const LAST_SEEN_MS = 6 * 60 * 60 * 1000;

// channelId -> last handled command / last successful !where hit, so a bare
// !broadcastpos can re-broadcast whatever !where just found in that channel.
const lastCmd = new Map();
const lastWhere = new Map();

// 15414 -> "0:04:16:54" (days:hours:minutes:seconds)
function fmtAge(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  const d = Math.floor(s / 86400), h = Math.floor(s / 3600) % 24,
        m = Math.floor(s / 60) % 60, sec = s % 60;
  return `${d}:${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
}

function noteSeen(name, x, y, z, dim, reporter, kind, t = Date.now()) {
  if (typeof name !== "string" || !validName(name)) return;
  if (![x, y, z].every(Number.isFinite)) return;
  if ((lastSeen.get(lower(name))?.t ?? -Infinity) > t) return;
  lastSeen.set(lower(name), {
    name, x: +x, y: +y, z: +z,
    dim: typeof dim === "string" && dim ? dim : "minecraft:overworld",
    t, kind, reporter,
  });
}

// ---------------------------------------------------------------- websocket
const app = express();
app.use(express.json());
const server = http.createServer(app);
// ws defaults to 100 MiB frames, which anyone can send before logging in. The
// largest legitimate client message (a 1024-point stroke or 100 position
// reports) is well under this.
const MAX_MESSAGE_BYTES = Number.isInteger(CONFIG.maxMessageBytes) && CONFIG.maxMessageBytes > 0
  ? CONFIG.maxMessageBytes : 256 * 1024;
// Connections that have not finished logging in, per remote address. Behind a
// reverse proxy every client shares the proxy's address: raise this, or set 0
// to turn the cap off.
const MAX_PENDING_PER_ADDRESS = Number.isInteger(CONFIG.maxPendingPerAddress) && CONFIG.maxPendingPerAddress >= 0
  ? CONFIG.maxPendingPerAddress : 16;
const pendingByAddress = new Map();
const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_MESSAGE_BYTES });
attachRelayTransport(server, wss, CONFIG.secureWebSocketUrl);

function allegiancePayload(recipient = null) {
  const role = validRole(recipient?.role) ? lower(recipient.role) : "member";
  if (isTrial(recipient)) {
    // Send-only rank: authenticate and report, receive nothing.
    return { type: "allegiances", role, users: [], allies: [], enemies: [], focus: [] };
  }
  return {
    type: "allegiances", role,
    users: userNames(),
    allies: ALLEGIANCES.allies,
    enemies: ALLEGIANCES.enemies,
    focus: [...new Set([...(ALLEGIANCES.focus ?? []), ...(isAdmin(recipient) ? ALLEGIANCES.adminFocus ?? [] : [])])],
  };
}

// Bridge IDs identify observations, not authenticated provenance. Never use origin
// metadata for authorization or visibility. The bounded cache is shared by sockets.
const BRIDGE_TTL_MS = LAST_SEEN_MS;
const BRIDGE_MAX_IDS = 10000;
const bridgeSeen = new Map();
function validBridge(b, maxHops = 8) {
  return b && typeof b.id === "string" && b.id.length > 0 && b.id.length <= 160
    && !/[\u0000-\u001f\u007f]/.test(b.id)
    && Number.isInteger(b.hops) && b.hops >= 0 && b.hops <= maxHops;
}
// Position observations arrive many times a second, so they get their own
// short-lived cache. Sharing bridgeSeen would push snitch, ping, and War Table
// IDs out long before their six-hour window and break multi-relay dedupe.
// A position older than STALE_MS is refused by age anyway, so that TTL suffices.
const POSITION_BRIDGE_MAX_IDS = 100000;
const positionBridgeSeen = new Map();
function claimIn(cache, b, ttl, max) {
  const now = Date.now();
  for (const [id, expiry] of cache) {
    if (expiry > now) break;
    cache.delete(id);
  }
  if (cache.has(b.id)) return false;
  if (cache.size >= max) cache.delete(cache.keys().next().value);
  cache.set(b.id, now + ttl);
  return true;
}
function claimBridge(b) {
  return claimIn(bridgeSeen, b, BRIDGE_TTL_MS, BRIDGE_MAX_IDS);
}
function claimPositionBridge(b) {
  return claimIn(positionBridgeSeen, b, STALE_MS, POSITION_BRIDGE_MAX_IDS);
}
function newBridge(candidate) {
  return validBridge(candidate) ? { id: candidate.id, hops: candidate.hops }
    : { id: crypto.randomBytes(16).toString("hex"), hops: 0 };
}
function localBridge(candidate) {
  const bridge = newBridge(candidate);
  return claimBridge(bridge) ? bridge : null;
}
function localPositionBridge(candidate) {
  const bridge = validBridge(candidate) ? { id: candidate.id, hops: candidate.hops }
    : { id: crypto.randomBytes(9).toString("base64url"), hops: 0 };
  return claimPositionBridge(bridge) ? bridge : null;
}
function validDimension(dim) {
  return typeof dim === "string" && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(dim) && dim.length <= 128;
}
function validCoordinates(e) {
  return [e.x, e.y, e.z].every(v => Number.isFinite(v) && Math.abs(v) <= 30000000);
}
function snitchDimension(value) {
  if (typeof value !== "string" || !value.length || value.length > 128
      || /[\u0000-\u001f\u007f]/.test(value)) return null;
  const aliases = { world: "minecraft:overworld", overworld: "minecraft:overworld",
    world_nether: "minecraft:the_nether", nether: "minecraft:the_nether",
    the_nether: "minecraft:the_nether", world_the_end: "minecraft:the_end",
    the_end: "minecraft:the_end", end: "minecraft:the_end" };
  return Object.hasOwn(aliases, lower(value)) ? aliases[lower(value)] : value;
}
function bridgeOrigin(e) {
  const source = e.origin && typeof e.origin === "object" ? e.origin : e;
  const origin = {};
  for (const key of ["from", "reporter"]) {
    if (typeof source[key] === "string" && source[key].length <= 64
        && !/[\u0000-\u001f\u007f]/.test(source[key])) origin[key] = source[key];
  }
  return origin;
}
const INTEL_TEXT_FIELDS = ["player", "name", "message", "label", "snitch", "action", "eventKind", "kind", "id"];
const pingTtl = (ttl) => Math.min(Number.isFinite(ttl) ? Math.max(0, ttl) : 60000, 300000);
// Shared by direct client submissions and relay bridges. Returns only known
// fields with checked coordinates, names, dimensions, and bounded text, or null
// when the event is malformed. Nothing else a client sends is forwarded.
function intelFields(type, event) {
  if (type === "snitch") {
    const coordinateCount = ["x", "y", "z"].filter(key => Object.hasOwn(event, key)).length;
    if (coordinateCount !== 0 && (coordinateCount !== 3 || !validCoordinates(event))) return null;
    if (coordinateCount === 0 && (typeof event.message !== "string" || !event.message.trim()
        || event.message.length > 1500 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(event.message))) return null;
    for (const key of ["dim", "world"]) {
      if (event[key] != null && snitchDimension(event[key]) == null) return null;
    }
  } else if (!validCoordinates(event) || !validDimension(event.dim ?? event.world)) return null;
  const intelName = value => typeof value === "string" && validName(value);
  if ((type === "position" && !intelName(event.name))
      || (type === "where" && !intelName(event.player))
      || (event.player != null && !intelName(event.player))
      || (event.name != null && !intelName(event.name))) return null;
  for (const key of INTEL_TEXT_FIELDS) {
    if (event[key] != null && (typeof event[key] !== "string" || event[key].length > 1500)) return null;
  }
  const clean = {};
  if (type === "snitch") {
    if (validCoordinates(event)) Object.assign(clean, { x: event.x, y: event.y, z: event.z });
    for (const key of ["dim", "world"]) {
      if (event[key] != null) clean[key] = snitchDimension(event[key]);
    }
    const dimension = clean.dim ?? clean.world;
    if (validDimension(dimension) && dimension !== "openintel:unknown") clean.dim = dimension;
  } else Object.assign(clean, { x: event.x, y: event.y, z: event.z, dim: event.dim ?? event.world });
  for (const key of INTEL_TEXT_FIELDS) if (event[key] != null) clean[key] = event[key];
  if (type === "ping") {
    clean.ttl = pingTtl(event.ttl);
    if (Number.isInteger(event.color)) clean.color = event.color;
  }
  return clean;
}
function handleBridge(ws, user, event) {
  if (user.quarantined || !event || typeof event !== "object" || !validBridge(event.bridge, 7)) return;
  const type = event.type;
  if (!["snitch", "ping", "where", "position", "wartable"].includes(type)) return;
  if (type === "wartable" && (!isAdmin(user) || !["add", "delete"].includes(event.action))) return;
  const now = Date.now();
  const maxAge = type === "position" ? STALE_MS
    : type === "ping" ? pingTtl(event.ttl)
    : type === "snitch" ? 600000 : LAST_SEEN_MS;
  if (!Number.isFinite(event.t) || event.t > now + 5000 || now - event.t > maxAge) return;
  let fields = null;
  if (type !== "wartable") {
    fields = intelFields(type, event);
    if (!fields) return;
  } else {
    if (event.action === "add" ? !validStroke(event.stroke)
        : typeof event.id !== "string" || !/^[A-Za-z0-9_-]{4,64}$/.test(event.id)) return;
    for (const key of ["message", "label", "snitch", "action", "eventKind", "kind", "id"]) {
      if (event[key] != null && (typeof event[key] !== "string" || event[key].length > 1500)) return;
    }
  }
  if (!(type === "position" ? claimPositionBridge : claimBridge)(event.bridge)) return;
  const bridge = { id: event.bridge.id, hops: event.bridge.hops + 1 };
  const clean = { type, t: event.t, bridge, origin: bridgeOrigin(event), from: user.name, reporter: user.name };
  if (type === "wartable") {
    clean.action = event.action;
    if (event.action === "add") {
      const s = event.stroke;
      clean.stroke = { id: s.id, tool: s.tool.toLowerCase(), points: [...s.points], color: s.color,
        width: s.width, from: user.name, t: event.t, bridge, origin: clean.origin };
      if (s.label != null) clean.stroke.label = s.label;
      const existing = WARTABLE.strokes.find(s => s.id === event.stroke.id);
      if (existing && existing.t > event.t) return;
      if (!existing && WARTABLE.strokes.length >= WARTABLE_MAX_STROKES) return;
      WARTABLE.strokes = WARTABLE.strokes.filter(s => s.id !== event.stroke.id);
      WARTABLE.strokes.push(clean.stroke);
    } else {
      clean.id = event.id;
      if (WARTABLE.strokes.some(s => s.id === event.id && s.t > event.t)) return;
      WARTABLE.strokes = WARTABLE.strokes.filter(s => s.id !== event.id);
    }
    saveJson("wartable.json", WARTABLE);
  } else {
    Object.assign(clean, fields);
    if (type === "position") {
      const key = `${lower(event.name)}@${lower(user.name)}`;
      if ((positions.get(key)?.t ?? -Infinity) > event.t) return;
      Object.assign(clean, positionVelocity(event));
      clean.x = roundCoordinate(clean.x); clean.y = roundCoordinate(clean.y); clean.z = roundCoordinate(clean.z);
      storePosition(key, clean);
      noteSeen(event.name, clean.x, clean.y, clean.z, clean.dim, user.name, "position", event.t);
      flushPositions(now);
      return;
    }
    if (type === "where" || (type === "snitch" && validCoordinates(clean)
        && validName(clean.player) && validDimension(clean.dim) && clean.dim !== "openintel:unknown"))
      noteSeen(event.player, clean.x, clean.y, clean.z, clean.dim, user.name, type === "snitch" ? "snitch" : "position", event.t);
  }
  broadcast(clean);
}

function broadcast(obj) {
  if (["snitch", "ping", "where", "wartable"].includes(obj.type) && !(obj.type === "wartable" && obj.action === "clear")) {
    if (!validBridge(obj.bridge)) {
      obj.bridge = newBridge(null);
      claimBridge(obj.bridge);
    }
    if (obj.t == null) obj.t = obj.stroke?.t ?? Date.now();
    if (obj.type === "wartable" && obj.action === "add") {
      obj.stroke.bridge = obj.bridge;
    }
  }
  const delivered = new Set();
  const snitchDecisions = new Map();
  // One filtered, encoded player list per visibility lane, not per recipient.
  const stateByLane = new Map();
  const now = Date.now();
  for (const client of wss.clients) {
    const identity = client.authedAs ? lower(client.authedAs) : null;
    const recipient = identity ? userByName(identity) : null;
    if (client.readyState !== 1 || !recipient || recipient.disabled || delivered.has(identity)) continue;
    const key = visibilityKey(recipient, USERS.users);
    const reset = client.visibilityKey !== key;
    if (reset) {
      client.send(JSON.stringify({ type: "intel_reset" }));
      client.send(JSON.stringify(allegiancePayload(recipient)));
      client.send(JSON.stringify(wartableSync(recipient)));
      client.visibilityKey = key;
    }
    let payload = obj;
    if (obj.type === "state") {
      // Delta clients only need the full list on their own slower cadence.
      if (obj.deltaClients === false && client.positionVersion === POSITION_PROTOCOL && !reset) continue;
      if (!stateByLane.has(key)) {
        const players = visiblePositions(obj.players, recipient, userByName)
          .map(p => ({ ...p, age: Math.max(0, now - p.t) }));
        stateByLane.set(key, JSON.stringify({ type: "state", replace: true, players }));
      }
      client.send(stateByLane.get(key));
      client.farPending?.clear();
      delivered.add(identity);
      continue;
    } else if (obj.type === "allegiances") {
      payload = allegiancePayload(recipient);
    } else if (obj.type === "snitch" || obj.type === "ping" || obj.type === "where") {
      const subject = obj.type === "ping" ? null : obj.player;
      if (!canReceiveIntel(recipient, obj.from, subject, userByName)) continue;
      if (obj.type === "snitch") {
        const audience = isAdmin(recipient) ? "admin" : "public";
        if (!snitchDecisions.has(audience)) snitchDecisions.set(audience, dedupeSnitch(obj, audience));
        if (!snitchDecisions.get(audience)) continue;
      }
    } else if (obj.type === "wartable") {
      // Admin-authored map annotations: same visibility lane as pings —
      // trial is send-only and cut reporters' strokes stay admin-visible.
      if (!canReceiveIntel(recipient, obj.from, null, userByName)) continue;
    }
    client.send(JSON.stringify(payload));
    delivered.add(identity);
  }
  return delivered.size > 0;
}

function sendVisibleState(deltaClients = true) {
  broadcast({ type: "state", players: [...positions.values()], deltaClients });
}

// ------------------------------------------------------ position deltas
// Players whose chosen report changed since the last push (lowercase names).
const dirtyPositions = new Set();
let lastPositionFlush = -Infinity;
const roundCoordinate = (v) => Math.round(v * 100) / 100;
function positionVelocity(r) {
  const ok = (v) => Number.isFinite(v) && Math.abs(v) <= MAX_REPORT_SPEED;
  return ok(r?.vx) && ok(r?.vz) ? { vx: roundCoordinate(r.vx), vz: roundCoordinate(r.vz) } : {};
}
function reportAge(age) {
  return Number.isFinite(age) && age > 0 ? Math.min(age, MAX_REPORT_AGE_MS) : 0;
}
function positionChanged(a, b) {
  return a.dim !== b.dim || a.reporter !== b.reporter
    || Math.abs(a.x - b.x) > 0.01 || Math.abs(a.y - b.y) > 0.01 || Math.abs(a.z - b.z) > 0.01
    || (a.vx ?? 0) !== (b.vx ?? 0) || (a.vz ?? 0) !== (b.vz ?? 0);
}
function storePosition(key, entry) {
  const previous = positions.get(key);
  positions.set(key, entry);
  if (!previous || positionChanged(previous, entry)) dirtyPositions.add(lower(entry.name));
}
// Sustained position uploads from one connection are capped at one per
// MIN_REPORT_INTERVAL_MS; a short burst (a client catching up after a stall)
// still goes through.
const POSITION_BURST = 8;
function takePositionToken(ws, now) {
  if (MIN_REPORT_INTERVAL_MS <= 0) return true;
  const refill = Number.isFinite(ws.positionTokensAt) ? (now - ws.positionTokensAt) / MIN_REPORT_INTERVAL_MS : POSITION_BURST;
  ws.positionTokens = Math.min(POSITION_BURST, (ws.positionTokens ?? 0) + Math.max(0, refill));
  ws.positionTokensAt = now;
  if (ws.positionTokens < 1) return false;
  ws.positionTokens -= 1;
  return true;
}
// Compact delta row: [name, x, y, z, dim#, ageMs, reporter#, bridgeId, hops, vx?, vz?]
// where # indexes the message's string table `s`.
function encodePositions(reports, now) {
  const strings = [], index = new Map();
  const ref = (s) => {
    if (!index.has(s)) { index.set(s, strings.length); strings.push(s); }
    return index.get(s);
  };
  const p = reports.map((r) => {
    const row = [r.name, r.x, r.y, r.z, ref(r.dim), Math.max(0, now - r.t), ref(r.reporter ?? "?"),
      r.bridge?.id ?? "", r.bridge?.hops ?? 0];
    if (r.vx != null && r.vz != null) row.push(r.vx, r.vz);
    return row;
  });
  return { type: "pos", s: strings, p };
}
function isFar(recipientName, report) {
  if (FAR_POSITION_MS <= 0 || NEAR_POSITION_RANGE <= 0) return false;
  const self = positions.get(`${lower(recipientName)}@${lower(recipientName)}`);
  if (!self) return false;
  return self.dim !== report.dim || Math.hypot(self.x - report.x, self.z - report.z) > NEAR_POSITION_RANGE;
}
// Push changed players to delta clients. Runs as reports arrive and on a
// POSITION_FLUSH_MS timer, but never more often than POSITION_FLUSH_MS.
function flushPositions(now = Date.now()) {
  if (now - lastPositionFlush < POSITION_FLUSH_MS) return;
  const recipients = [];
  const wanted = new Set(dirtyPositions);
  for (const client of wss.clients) {
    if (client.readyState !== 1 || !client.authedAs || client.positionVersion !== POSITION_PROTOCOL) continue;
    const user = userByName(client.authedAs);
    if (!user || user.disabled || client.visibilityKey !== visibilityKey(user, USERS.users)) continue;
    recipients.push({ client, user });
    for (const subject of client.farPending?.keys() ?? []) wanted.add(subject);
  }
  if (!wanted.size) return;
  const dirty = new Set(dirtyPositions);
  dirtyPositions.clear();
  if (!recipients.length) return;
  const bySubject = new Map([...wanted].map((subject) => [subject, []]));
  for (const report of positions.values()) bySubject.get(lower(report.name))?.push(report);
  const lanes = new Map();
  const delivered = new Set();
  for (const { client, user } of recipients) {
    if (delivered.has(lower(client.authedAs))) continue;
    const lane = client.visibilityKey;
    if (!lanes.has(lane)) {
      const chosen = new Map();
      for (const [subject, reports] of bySubject) {
        const [best] = visiblePositions(reports, user, userByName);
        if (best) chosen.set(subject, best);
      }
      lanes.set(lane, chosen);
    }
    const chosen = lanes.get(lane);
    client.farPending ??= new Map();
    client.posSentAt ??= new Map();
    const out = [];
    for (const subject of new Set([...dirty, ...client.farPending.keys()])) {
      const report = chosen.get(subject);
      if (!report) { client.farPending.delete(subject); continue; }
      if (lower(report.name) === lower(client.authedAs)) continue;
      if (isFar(client.authedAs, report) && now - (client.posSentAt.get(subject) ?? -Infinity) < FAR_POSITION_MS) {
        client.farPending.set(subject, true);
        continue;
      }
      client.farPending.delete(subject);
      client.posSentAt.set(subject, now);
      out.push(report);
    }
    if (client.posSentAt.size > 4096) client.posSentAt.clear();
    if (!out.length) continue;
    client.send(JSON.stringify(encodePositions(out, now)));
    delivered.add(lower(client.authedAs));
  }
  if (delivered.size) lastPositionFlush = now;
}
function socketsFor(name) {
  return [...wss.clients].filter((client) => client.authedAs && lower(client.authedAs) === lower(name));
}
function revokeSessions(name, reason = "session revoked") {
  const sockets = socketsFor(name);
  for (const socket of sockets) socket.close(4001, reason);
  return sockets.length;
}
function removeReportedPositions(name) {
  for (const [key, value] of positions) if (lower(value.reporter) === lower(name)) positions.delete(key);
}
function currentSocketUser(ws) {
  return ws.authedAs ? userByName(ws.authedAs) : null;
}

// Shared by the in-game /oi focus command and the Discord terminal.
// Returns a human-readable description of what changed, or null if the
// action was invalid / a no-op.
function applyFocus(action, subject, actorLabel, context = {}) {
  const field = context.adminOnly ? "adminFocus" : "focus";
  const before = [...(ALLEGIANCES[field] ?? [])];
  ALLEGIANCES[field] = ALLEGIANCES[field] ?? [];
  if (action === "add" && subject && validName(subject)) {
    if (!ALLEGIANCES[field].map(lower).includes(lower(subject))) ALLEGIANCES[field].push(subject);
  } else if (action === "remove" && subject && validName(subject)) {
    ALLEGIANCES[field] = ALLEGIANCES[field].filter((n) => lower(n) !== lower(subject));
  } else if (action === "clear") {
    ALLEGIANCES[field] = [];
  } else {
    audit({ ...context, actor: actorLabel, action: `focus.${action}`, target: subject, success: false, reason: "invalid action or player name" });
    return null;
  }
  saveJson("allegiances.json", ALLEGIANCES);
  broadcast(allegiancePayload());
  const what = action === "clear" ? "cleared all focus targets"
                                  : `${action === "add" ? "focused" : "unfocused"} **${subject}**`;
  audit({ ...context, actor: actorLabel, action: `focus.${action}`, target: subject, before, after: ALLEGIANCES[field] });
  if (!context.adminOnly) postWebhook(CONFIG.webhooks.alerts, {
    username: "OpenIntel",
    content: `🎯 **${actorLabel}** ${what}`,
  });
  return what;
}

// kind: "allies" | "enemies"; action: "add" | "remove". Returns a result line.
function applyAllegiance(kind, action, name, actorLabel, context = {}) {
  const list = ALLEGIANCES[kind] ?? (ALLEGIANCES[kind] = []);
  const before = [...list];
  const present = list.map(lower).includes(lower(name));
  if (!validName(name)) {
    audit({ ...context, actor: actorLabel, action: `${kind}.${action}`, target: name, before, after: before, success: false, reason: "invalid player name" });
    return "invalid Minecraft player name";
  }
  if (action === "add") {
    if (present) {
      audit({ ...context, actor: actorLabel, action: `${kind}.${action}`, target: name, before, after: before, success: false, reason: "already present" });
      return `${name} is already on the ${kind} list`;
    }
    list.push(name);
  } else {
    if (!present) {
      audit({ ...context, actor: actorLabel, action: `${kind}.${action}`, target: name, before, after: before, success: false, reason: "not present" });
      return `${name} is not on the ${kind} list`;
    }
    ALLEGIANCES[kind] = list.filter((n) => lower(n) !== lower(name));
  }
  saveJson("allegiances.json", ALLEGIANCES);
  broadcast(allegiancePayload());
  audit({ ...context, actor: actorLabel, action: `${kind}.${action}`, target: name, before, after: ALLEGIANCES[kind] });
  return `${action === "add" ? "added" : "removed"} ${name} ${action === "add" ? "to" : "from"} ${kind}`;
}

wss.on("connection", (ws, req) => {
  ws.authedAs = null;
  const address = String(req.socket.remoteAddress ?? "unknown");
  const tag = clientTag(address);
  if (MAX_PENDING_PER_ADDRESS > 0 && (pendingByAddress.get(address) ?? 0) >= MAX_PENDING_PER_ADDRESS) {
    logAuthRejection(`pending:${tag}`, `⚠️ Too many pending logins from client \`${tag}\``, address);
    ws.close(1013, "too many pending connections");
    return;
  }
  pendingByAddress.set(address, (pendingByAddress.get(address) ?? 0) + 1);
  let pending = true;
  const settlePending = () => {
    if (!pending) return;
    pending = false;
    const left = (pendingByAddress.get(address) ?? 1) - 1;
    if (left > 0) pendingByAddress.set(address, left);
    else pendingByAddress.delete(address);
  };
  ws.on("close", settlePending);
  const identity = createIdentityVerification(ws, { userByToken,
    onRejected(reason, invitationName) {
      const message = invitationName
        ? `❌ Token for **${invitationName}** used from client \`${tag}\`; Minecraft account verification failed (${reason})`
        : `❌ Minecraft account verification failed from client \`${tag}\` (${reason})`;
      logAuthRejection(`identity:${tag}`, message, address);
    },
    onVerified(user, minecraftUuid) {
      settlePending();
      // Pin the first verified account so a recycled Minecraft name cannot take
      // over an invitation after its owner changes names.
      if (user.minecraftUuid == null) {
        const pinned = { ...user, minecraftUuid };
        const updated = { ...USERS, users: USERS.users.map(u => u === user ? pinned : u) };
        saveJson("users.json", updated);
        USERS = updated;
        user = pinned;
      }
      ws.authedAs = user.name;
      ws.role = validRole(user.role) ? lower(user.role) : "member";
      ws.visibilityKey = visibilityKey(user, USERS.users);
      ws.send(JSON.stringify({ ...allegiancePayload(user), type: "welcome", minecraftServer: MINECRAFT_SERVER,
        role: ws.role, relayCut: user.relayCut === true, bridgeVersion: 1, positionVersion: POSITION_PROTOCOL }));
      if (!isTrial(user)) ws.send(JSON.stringify(wartableSync(user)));
      adminLog(`✅ **${user.name}** connected (${ws.role})`);
    }
  });

  ws.on("message", (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    if (!msg || typeof msg !== "object" || Array.isArray(msg)) return;

    if (msg.type === "authenticate") {
      void identity.complete(msg.clientNonce);
      return;
    }
    if (msg.type === "hello") {
      const user = typeof msg.token === "string" && msg.token.length > 0 ? userByToken(msg.token) : null;
      if (!user) {
        ws.send(JSON.stringify({ type: "deny", reason: "bad_token" }));
        logAuthRejection(`token:${tag}`, `❌ Auth failure from client \`${tag}\``, address);
        ws.close(4001, "bad token");
        return;
      }
      const minecraftServer = normalizeMinecraftServer(msg.minecraftServer);
      if (minecraftServer !== MINECRAFT_SERVER) {
        ws.send(JSON.stringify({ type: "deny", reason: "wrong_server", expectedServer: MINECRAFT_SERVER }));
        logAuthRejection(`server:${lower(user.name)}:${minecraftServer || "missing"}`,
          `❌ **${user.name}** rejected from Minecraft server \`${minecraftServer || "missing"}\``);
        ws.close(4003, "wrong Minecraft server");
        return;
      }
      ws.minecraftServer = minecraftServer;
      // Optional: newer clients take compact position deltas. Older clients omit it.
      ws.positionVersion = Number.isInteger(msg.positionVersion) && msg.positionVersion >= POSITION_PROTOCOL
        ? POSITION_PROTOCOL : 1;
      identity.begin(msg.token);
      return;
    }

    if (!ws.authedAs) return;
    const user = currentSocketUser(ws);
    if (!user || user.disabled || !user.tokenHash) {
      ws.close(4001, "session revoked");
      return;
    }
    ws.role = validRole(user.role) ? lower(user.role) : "member";

    if (msg.type === "bridge") {
      handleBridge(ws, user, msg.event);
      return;
    }

    if (msg.type === "cut") {
      if (!isAdmin(user)) {
        ws.send(JSON.stringify({ type: "notice", msg: "/oi cut is admin-only" }));
        return;
      }
      if (msg.action === "status") {
        ws.send(JSON.stringify({ type: "notice", msg: `Relay cut ${user.relayCut ? "ON: admins only" : "OFF: all ranks"}` }));
        return;
      }
      if (![undefined, "toggle", "on", "off"].includes(msg.action)) return;
      const before = user.relayCut === true;
      const cut = msg.action === "on" || ((msg.action === undefined || msg.action === "toggle") && !before);
      const updated = { ...USERS, users: USERS.users.map(u => u === user ? { ...u, relayCut: cut } : u) };
      try { saveJson("users.json", updated); }
      catch { ws.send(JSON.stringify({ type: "notice", msg: "Could not save relay cut; unchanged" })); return; }
      user.relayCut = cut;
      sendVisibleState();
      ws.send(JSON.stringify({ type: "notice", msg: `Relay cut ${cut ? "ON: lower ranks no longer receive your relay" : "OFF: sharing with all ranks"}. Admin-to-admin relay is unchanged.` }));
      audit({ actor: user.name, tier: "admin", action: "relay.cut", target: user.name,
        source: { channel: "websocket" }, before: { relayCut: before }, after: { relayCut: cut } });
      return;
    }

    if (msg.type === "focus") {
      if (user.quarantined) {
        ws.send(JSON.stringify({ type: "notice", msg: "quarantined sessions cannot change focus targets" }));
        return;
      }
      if (!isOperator(ws.role)) {
        ws.send(JSON.stringify({ type: "notice", msg: "/oi focus requires the Operator role" }));
        return;
      }
      applyFocus(msg.action, msg.subject ? String(msg.subject) : null, ws.authedAs, {
        tier: ws.role, adminOnly: false, source: { guild: null, channel: "websocket" },
      });
      return;
    }

    // War Table: admin-drawn strokes, persisted + fanned out to everyone.
    if (msg.type === "wartable") {
      if (user.quarantined) return;
      if (!isAdmin(user)) {
        ws.send(JSON.stringify({ type: "notice", msg: "war table edits are admin-only" }));
        return;
      }
      const action = String(msg.action ?? "");
      if (action === "add") {
        const stroke = msg.stroke;
        if (!validStroke(stroke)) {
          ws.send(JSON.stringify({ type: "notice", msg: "invalid map stroke rejected" }));
          return;
        }
        const bridge = localBridge(msg.bridge ?? stroke.bridge);
        if (!bridge) return;
        const clean = {
          bridge,
          id: stroke.id,
          tool: String(stroke.tool).toLowerCase(),
          color: stroke.color,
          width: stroke.width,
          points: stroke.points.map(Number),
          from: ws.authedAs,
          t: Date.now(),
        };
        if (stroke.label != null && stroke.label !== "") clean.label = String(stroke.label).slice(0, 96);
        const existing = WARTABLE.strokes.findIndex((s) => s.id === clean.id);
        if (existing >= 0) WARTABLE.strokes.splice(existing, 1);
        if (WARTABLE.strokes.length >= WARTABLE_MAX_STROKES) {
          ws.send(JSON.stringify({ type: "notice", msg: `war table is full (${WARTABLE_MAX_STROKES} strokes) — clear some first` }));
          return;
        }
        WARTABLE.strokes.push(clean);
        saveJson("wartable.json", WARTABLE);
        broadcast({ type: "wartable", action: "add", stroke: clean, bridge: clean.bridge, t: clean.t, from: ws.authedAs });
        return;
      }
      if (action === "delete") {
        const bridge = localBridge(msg.bridge);
        if (!bridge) return;
        const id = String(msg.id ?? "");
        const before = WARTABLE.strokes.length;
        WARTABLE.strokes = WARTABLE.strokes.filter((s) => s.id !== id);
        if (WARTABLE.strokes.length !== before) {
          saveJson("wartable.json", WARTABLE);
          broadcast({ type: "wartable", action: "delete", id, bridge, from: ws.authedAs });
        }
        return;
      }
      if (action === "clear") {
        const scope = msg.scope === "mine" ? "mine" : "all";
        const before = WARTABLE.strokes.length;
        WARTABLE.strokes = scope === "mine"
          ? WARTABLE.strokes.filter((s) => lower(s.from) !== lower(ws.authedAs))
          : [];
        if (WARTABLE.strokes.length !== before) {
          saveJson("wartable.json", WARTABLE);
          broadcast({ type: "wartable", action: "clear", scope, from: ws.authedAs });
          audit({ actor: ws.authedAs, tier: ws.role, action: `wartable.clear.${scope}`,
            source: { channel: "websocket" }, before: { strokes: before }, after: { strokes: WARTABLE.strokes.length } });
        }
        return;
      }
      return;
    }

    // Shared pings + forwarded snitch alerts: stamp the sender and fan out.
    // Only checked fields go out; anything else the client attached is dropped.
    if (msg.type === "ping" || msg.type === "snitch") {
      if (user.quarantined) return;
      const fields = intelFields(msg.type, msg);
      if (!fields) return;
      // Several clients can see the same snitch line — don't multiply it.
      const bridge = localBridge(msg.bridge);
      if (!bridge) return;
      const clean = { type: msg.type, ...fields, bridge,
        from: ws.authedAs, reporter: ws.authedAs, t: Date.now() };
      broadcast(clean);
      if (clean.type === "snitch" && clean.player)
        noteSeen(clean.player, clean.x, clean.y, clean.z, clean.dim ?? clean.world, ws.authedAs, "snitch");
      return;
    }

    if (msg.type === "positions" && Array.isArray(msg.reports)) {
      if (user.quarantined) return;
      const now = Date.now();
      if (!takePositionToken(ws, now)) return;
      for (const r of msg.reports.slice(0, 100)) {
        if (!r || typeof r !== "object" || typeof r.subject !== "string" || !validName(r.subject)) continue;
        if (!validCoordinates(r) || !validDimension(r.dim)) continue;
        const bridge = localPositionBridge(r.bridge);
        if (!bridge) continue;
        const t = now - reportAge(r.age);
        storePosition(`${lower(r.subject)}@${lower(ws.authedAs)}`, {
          name: r.subject, x: roundCoordinate(+r.x), y: roundCoordinate(+r.y), z: roundCoordinate(+r.z), bridge,
          dim: String(r.dim), t, reporter: ws.authedAs, ...positionVelocity(r),
        });
        noteSeen(r.subject, r.x, r.y, r.z, r.dim, ws.authedAs, "position", t);
        if (isEnemy(r.subject) && canReceiveIntel({ role: "member" }, ws.authedAs, r.subject, userByName))
          enemyAlert(r.subject, r.x, r.z, r.dim, ws.authedAs);
      }
      flushPositions(now);
    }
  });

  ws.on("close", () => {
    if (ws.authedAs) adminLog(`👋 **${ws.authedAs}** disconnected`);
  });
});

// Full player list: every BROADCAST_MS for older clients, every FULL_SYNC_MS
// for delta clients (which get changes in between from flushPositions).
let lastFullSync = -Infinity;
setInterval(() => {
  const now = Date.now();
  for (const [k, v] of positions) {
    if (now - v.t <= STALE_MS) continue;
    positions.delete(k);
    dirtyPositions.add(lower(v.name));   // another reporter's view may now win
  }
  for (const [k, v] of lastSeen) if (now - v.t > LAST_SEEN_MS) lastSeen.delete(k);
  const deltaClients = now - lastFullSync >= FULL_SYNC_MS;
  if (deltaClients) lastFullSync = now;
  sendVisibleState(deltaClients);
}, BROADCAST_MS);
setInterval(() => flushPositions(Date.now()), POSITION_FLUSH_MS);

// ---------------------------------------------------------------- admin REST
const ADMIN_TOKEN_DIGEST = adminTokenDigest(CONFIG.adminToken);
if (!ADMIN_TOKEN_DIGEST) {
  console.log(`Admin HTTP API disabled: set adminToken to a random string of at least ${ADMIN_TOKEN_MIN_LENGTH} characters`);
}
function requireAdmin(req, res, next) {
  if (!ADMIN_TOKEN_DIGEST) return res.status(403).json({ error: "admin API disabled" });
  if (!adminTokenMatches(ADMIN_TOKEN_DIGEST, req.headers["x-admin-token"])) {
    return res.status(403).json({ error: "bad admin token" });
  }
  next();
}

app.get("/allegiances", requireAdmin, (req, res) => res.json(ALLEGIANCES));
app.put("/allegiances", requireAdmin, (req, res) => {
  const before = ALLEGIANCES;
  ALLEGIANCES = {
    allies: req.body.allies ?? [],
    enemies: req.body.enemies ?? [],
    focus: req.body.focus ?? ALLEGIANCES.focus ?? [],
  };
  saveJson("allegiances.json", ALLEGIANCES);
  broadcast(allegiancePayload());
  audit({ actor: "REST admin", tier: "admin", action: "allegiances.replace", source: { channel: "REST" }, before, after: ALLEGIANCES });
  res.json({ ok: true });
});

app.get("/users", requireAdmin, (req, res) =>
  res.json({ users: USERS.users.map((u) => u.name) })); // never expose tokens
app.put("/users", requireAdmin, (req, res) => {
  const before = USERS;
  // Accepts a plaintext `token` (hashed here) or an existing `tokenHash`.
  USERS = sealUsers({ users: req.body?.users ?? [] }).data;
  saveJson("users.json", USERS);
  broadcast(allegiancePayload());
  for (const client of wss.clients) {
    const user = currentSocketUser(client);
    if (client.authedAs && (!user || user.disabled)) client.close(4001, "session revoked");
  }
  audit({ actor: "REST admin", tier: "admin", action: "users.replace", source: { channel: "REST" }, before, after: USERS });
  res.json({ ok: true });
});

app.get("/online", requireAdmin, (req, res) =>
  res.json({ online: [...wss.clients].filter((c) => c.authedAs).map((c) => c.authedAs) }));

// ---------------------------------------------------------------- discord terminal
// Reads plain-text "!" commands from one Discord channel, so the channel works
// like an admin terminal. Disabled unless config.discord.botToken is set.
// Mutating commands require the configured captain role (or Discord Administrator).
// Snitch relay channel: bot-authored JukeAlert posts get parsed and turned
// into the same "snitch" broadcast clients forward — marker, feed line, all
// of it. dedupeSnitch keys on player@coords so a teammate who also saw the
// in-game alert doesn't produce a second hit.
const DISCORD_SNITCH_EVENT =
  /^\s*(?<snitch>.+?):\s*(?<player>\w{3,16})\s+(?<action>entered snitch|logged (?:in|out))\s+at\s*\((?<x>-?\d+)[,\s]+(?<y>-?\d+)[,\s]+(?<z>-?\d+)\s*\)/i;
const DISCORD_SNITCH_INTERACTION =
  /^\s*(?<snitch>.+?):\s*(?<player>\w{3,16})\s+(?<action>(?!entered snitch\b|logged (?:in|out)\b).{2,96}?)\s+at\s*\((?<x>-?\d+)[,\s]+(?<y>-?\d+)[,\s]+(?<z>-?\d+)\s*\)/i;
const DISCORD_SNITCH_INGAME =
  /(?<player>\w{3,16})\s+(?<action>entered snitch)\s+at\s+(?<snitch>\S+)[^\[(]*\[\s*(?<world>\S+)\s+(?<x>-?\d+)\s+(?<y>-?\d+)\s+(?<z>-?\d+)\s*\]/i;

function parseDiscordSnitch(text) {
  let eventKind = "event";
  let m = text.match(DISCORD_SNITCH_EVENT) ?? text.match(DISCORD_SNITCH_INGAME);
  if (!m) {
    m = text.match(DISCORD_SNITCH_INTERACTION);
    eventKind = "interaction";
  }
  if (!m) return null;
  const player = m.groups.player;
  const snitch = m.groups.snitch.replace(/^[+\s*]+|[+\s*]+$/g, "");
  const x = +m.groups.x, y = +m.groups.y, z = +m.groups.z;
  if (!player || !snitch) return null;
  return { player, snitch, world: m.groups.world ?? null, x, y, z,
           action: m.groups.action.trim(), eventKind };
}

function forwardDiscordSnitch(text) {
  const hit = parseDiscordSnitch(text);
  if (!hit) return;
  const msg = {
    type: "snitch", message: text, reporter: "discord", player: hit.player,
    snitch: hit.snitch, action: hit.action, eventKind: hit.eventKind,
    x: hit.x, y: hit.y, z: hit.z,
  };
  if (hit.world) msg.world = hit.world;
  msg.from = "discord";
  msg.t = Date.now();
  if (broadcast(msg)) adminLog(`📡 Snitch hit via Discord relay: ${hit.snitch ?? "?"} by ${hit.player ?? "?"}`);
  noteSeen(hit.player, hit.x, hit.y, hit.z, msg.world, "discord", "snitch");
}

const DISCORD = CONFIG.discord ?? {};
const idSet = (many, one) => new Set([
  ...(Array.isArray(many) ? many : []),
  ...(one ? [one] : []),
].map(String).filter(Boolean));
const TERMINAL_CHANNELS = idSet(DISCORD.terminalChannelIds, DISCORD.terminalChannelId);
const SNITCH_CHANNELS = idSet(DISCORD.snitchChannelIds, DISCORD.snitchChannelId);
const OPERATOR_ROLES = idSet(DISCORD.operatorRoleIds, DISCORD.operatorRoleId);
const CAPTAIN_ROLES = idSet(DISCORD.captainRoleIds, DISCORD.captainRoleId);
const ADMIN_ROLES = idSet(DISCORD.adminRoleIds, DISCORD.adminRoleId);

if (DISCORD.botToken) {
  const {
    ActionRowBuilder, ButtonBuilder, ButtonStyle, Client, EmbedBuilder,
    GatewayIntentBits, PermissionsBitField,
  } = require("discord.js");
  const bot = new Client({
    intents: [
      GatewayIntentBits.Guilds,
      GatewayIntentBits.GuildMessages,
      GatewayIntentBits.MessageContent, // must also be enabled in the dev portal
    ],
  });

  const fence = (s) => "```\n" + String(s).slice(0, 1900) + "\n```";
  const discordTier = (member) => {
    if (!member) return "member";
    if (member.permissions.has(PermissionsBitField.Flags.Administrator)) return "admin";
    if ([...ADMIN_ROLES].some((id) => member.roles.cache.has(id))) return "admin";
    if ([...CAPTAIN_ROLES].some((id) => member.roles.cache.has(id))) return "captain";
    if ([...OPERATOR_ROLES].some((id) => member.roles.cache.has(id))) return "operator";
    return "member";
  };
  const sourceOf = (value) => ({ guild: value.guildId ?? null, channel: value.channelId ?? null });
  const actorOf = (value) => `${value.member?.displayName ?? value.user?.username ?? value.author?.username ?? "unknown"} (${value.user?.id ?? value.author?.id ?? "unknown"})`;
  const onlineRows = () => [...wss.clients]
    .filter((c) => c.authedAs)
    .map((c) => `${c.authedAs} (${c.role}${userByName(c.authedAs)?.quarantined ? ", quarantined" : ""})`);
  const listRows = (kind) => {
    if (kind === "users") return USERS.users.map((u) => `${u.name} (${u.role ?? "member"}${u.disabled ? ", disabled" : ""}${u.quarantined ? ", quarantined" : ""})`);
    if (kind === "allies") return (ALLEGIANCES.allies ?? []).map(String);
    if (kind === "enemies") return (ALLEGIANCES.enemies ?? []).map(String);
    if (kind === "focus") return (ALLEGIANCES.focus ?? []).map(String);
    if (kind === "online") return onlineRows();
    return [
      ...USERS.users.map((u) => `user: ${u.name} (${u.role ?? "member"}${u.disabled ? ", disabled" : ""}${u.quarantined ? ", quarantined" : ""})`),
      ...(ALLEGIANCES.allies ?? []).map((n) => `ally: ${n}`),
      ...(ALLEGIANCES.enemies ?? []).map((n) => `enemy: ${n}`),
      ...(ALLEGIANCES.focus ?? []).map((n) => `focus: ${n}`),
    ];
  };
  const renderList = (kind = "all", page = 1) => pageText(kind, listRows(kind), page);
  const commandAudit = (msg, tier, action, target, details = {}) => audit({
    actor: actorOf(msg), tier, action, target, source: sourceOf(msg), ...details,
  });
  const requireTier = async (msg, tier, required, action, target = null) => {
    if (hasTier(tier, required)) return true;
    commandAudit(msg, tier, action, target, { success: false, reason: `requires ${required}` });
    await msg.reply(`⛔ requires the ${required} role`);
    return false;
  };
  const HELP = fence(
    [
      "!online [page]                       who is connected to the relay",
      "!list [users|allies|enemies|focus|online|all] [page]",
      "!where <player>                      latest intel for a player; marks it on the war table",
      "!broadcastpos [x y z | !where line]  60s 'coord broadcast' marker; bare = last !where result",
      "!gentoken                            generate a random sha256 token (DM)",
      "!broadcast <message>                 relay notice (operator)",
      "!focus <player>|clear / !unfocus     focus management (operator)",
      "!panel                               interactive status panel (operator)",
      "!ally|enemy add|remove <player>      allegiance management (captain)",
      "!kick <user>                         close active sessions (captain)",
      "!quarantine|unquarantine <user>      control submissions (captain)",
      "!user add <name> [role]              create user + DM token (admin)",
      "!user remove|disable|enable <name>   user lifecycle (admin)",
      "!user role <name> <role>             set user role (admin)",
      "!user rotate-token|info <name>       new token by DM / user details (admin)",
      "!help                                this message",
    ].join("\n")
  );
  const panelRows = () => new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId("oi:status").setLabel("Refresh Status").setStyle(ButtonStyle.Primary),
    new ButtonBuilder().setCustomId("oi:online").setLabel("Online").setStyle(ButtonStyle.Secondary),
    new ButtonBuilder().setCustomId("oi:lists").setLabel("Lists").setStyle(ButtonStyle.Secondary),
    new ButtonBuilder().setCustomId("oi:audit").setLabel("Recent Activity").setStyle(ButtonStyle.Secondary),
  );
  const panelEmbed = () => new EmbedBuilder()
    .setTitle("OpenIntel Relay")
    .setDescription("Relay administration and live status")
    .addFields(
      { name: "Online", value: String(onlineRows().length), inline: true },
      { name: "Users", value: String(USERS.users.length), inline: true },
      { name: "Focus", value: String((ALLEGIANCES.focus ?? []).length), inline: true },
      { name: "Minecraft server", value: MINECRAFT_SERVER, inline: false },
    )
    .setTimestamp();

  // !where fallback: snitch channels keep "name: player entered snitch at
  // (x, y, z)" history — scan them newest-first when relay memory is empty.
  const HISTORY_SCAN_LIMIT = 100;
  async function historyWhere(name) {
    const q = lower(name);
    let best = null;
    for (const channelId of SNITCH_CHANNELS) {
      try {
        const channel = bot.channels.cache.get(channelId)
          ?? await bot.channels.fetch(channelId).catch(() => null);
        if (!channel || !channel.isTextBased()) continue;
        const messages = await channel.messages.fetch({ limit: HISTORY_SCAN_LIMIT }).catch(() => null);
        if (!messages) continue;
        for (const m of messages.values()) {
          const hit = parseDiscordSnitch(m.content ?? "");
          if (!hit || lower(hit.player) !== q) continue;
          if (!best || m.createdTimestamp > best.t) {
            best = { name: hit.player, x: hit.x, y: hit.y, z: hit.z,
                     dim: hit.world ?? "minecraft:overworld",
                     t: m.createdTimestamp, kind: "snitch", reporter: "discord" };
          }
        }
      } catch { /* channel gone or no history access — keep scanning others */ }
    }
    return best;
  }

  bot.on("messageCreate", async (msg) => {
    try {
      // Snitch relay channels share one lane and one global dedupe map.
      if (SNITCH_CHANNELS.has(msg.channelId)) {
        forwardDiscordSnitch(msg.content);
        return;
      }
      if (msg.author.bot || !msg.guild) return;
      if (TERMINAL_CHANNELS.size > 0 && !TERMINAL_CHANNELS.has(msg.channelId)) return;
      if (!msg.content.startsWith("!")) return;

      const parts = msg.content.slice(1).trim().split(/\s+/);
      const cmd = lower(parts[0] ?? "");
      const actor = actorOf(msg);
      const tier = discordTier(msg.member);
      const context = { tier, source: sourceOf(msg) };
      const cmdChannel = msg.channelId ?? "dm";
      // broadcastpos deliberately doesn't count — a bare call still sees the
      // preceding !where as the "last command".
      if (cmd !== "broadcastpos") lastCmd.set(cmdChannel, cmd);

      if (cmd === "help") return void msg.reply(HELP);

      if (cmd === "online") return void msg.reply(fence(renderList("online", parts[1])));

      if (cmd === "list") {
        const kinds = ["users", "allies", "enemies", "focus", "online", "all"];
        const requested = lower(parts[1] ?? "all");
        const kind = kinds.includes(requested) ? requested : "all";
        const page = kinds.includes(requested) ? parts[2] : parts[1];
        return void msg.reply(fence(renderList(kind, page)));
      }

      if (cmd === "where") {
        const name = parts[1];
        if (!name) return void msg.reply("usage: `!where <player>`");
        lastWhere.delete(cmdChannel);
        const q = lower(name);
        // Latest intel of any kind: snitch trips outlive the 10s position window.
        let hit = lastSeen.get(q);
        if (!hit) {
          const p = visiblePositions(positions.values(), { role: tier }, userByName)
            .find(report => lower(report.name) === q);
          if (p) hit = { name: p.name, x: p.x, y: p.y, z: p.z, dim: p.dim,
                         t: p.t, kind: "position", reporter: p.reporter };
        }
        if (hit && !canReceiveIntel({ role: tier }, hit.reporter, hit.name, userByName)) hit = null;
        if (!hit) {
          // Relay memory is empty — check the snitch channels' history.
          hit = await historyWhere(name);
          if (hit && !canReceiveIntel({ role: tier }, hit.reporter, hit.name, userByName)) hit = null;
          if (hit) lastSeen.set(q, hit);   // cache it for follow-up lookups
        }
        if (!hit) return void msg.reply(fence(`${name}: no recent report`));
        lastWhere.set(cmdChannel, hit);
        // Drop a marker every connected client's war table can render.
        broadcast({ type: "where", player: hit.name, x: hit.x, y: hit.y, z: hit.z,
                    dim: hit.dim, t: hit.t, kind: hit.kind, from: hit.reporter });
        const age = fmtAge(Date.now() - hit.t);
        const kind = hit.kind === "snitch" ? "snitch hit" : "position";
        return void msg.reply(fence(
          `${hit.name}: ${Math.round(hit.x)}, ${Math.round(hit.y)}, ${Math.round(hit.z)} ` +
          `(${String(hit.dim).replace("minecraft:", "")}) — ${kind} ${age} ago, reported by ${hit.reporter}`
        ));
      }

      if (cmd === "gentoken") {
        const token = crypto.createHash("sha256").update(crypto.randomBytes(32)).digest("hex");
        try {
          await msg.author.send("Random token:\n`" + token + "`");
          return void msg.reply("token sent to your DMs");
        } catch {
          // Never fall back to the channel: anyone reading it could reuse the token.
          return void msg.reply("could not DM you the token. Enable DMs from this server and retry.");
        }
      }
      // Everything below mutates state.
      if (cmd === "broadcast") {
        const text = parts.slice(1).join(" ").trim();
        if (!(await requireTier(msg, tier, "operator", "broadcast", null))) return;
        if (!text || text.length > 1500) {
          commandAudit(msg, tier, "broadcast", null, { success: false, reason: "message must be 1-1500 characters" });
          return void msg.reply("usage: `!broadcast <message>` (maximum 1500 characters)");
        }
        broadcast({ type: "notice", msg: `[Broadcast] ${text}`, from: actor, t: Date.now() });
        commandAudit(msg, tier, "broadcast", null, { after: { message: text } });
        return void msg.reply("broadcast sent");
      }

      // Temporary 60s map marker. Two forms:
      //   !broadcastpos 1093 69 2704
      //   !broadcastpos ItzHigh: 1093, 69, 2704 (overworld) — snitch hit 86s ago, reported by Capri
      if (cmd === "broadcastpos") {
        if (!(await requireTier(msg, tier, "operator", "broadcastpos", null))) return;
        const rest = parts.slice(1).join(" ").trim();
        let player = null, x, y, z;
        let dim = "minecraft:overworld";
        if (!rest) {
          // Bare call: re-broadcast whatever the last !where found in this channel.
          const hit = lastCmd.get(cmdChannel) === "where" ? lastWhere.get(cmdChannel) : null;
          if (!hit) {
            commandAudit(msg, tier, "broadcastpos", null, { success: false, reason: "no args and no prior !where result" });
            return void msg.reply("usage: `!broadcastpos <x> <y> <z>`, paste a `!where` result line, or run it right after a successful `!where`");
          }
          ({ name: player, x, y, z, dim } = hit);
        } else {
          const paste = rest.match(
            /^(?<name>[A-Za-z0-9_]{3,16})\s*:\s*(?<x>-?\d+(?:\.\d+)?)\s*,\s*(?<y>-?\d+(?:\.\d+)?)\s*,\s*(?<z>-?\d+(?:\.\d+)?)\s*(?:\((?<dim>[^)]+)\))?/);
          const direct = rest.match(
            /^(?<x>-?\d+(?:\.\d+)?)[,\s]+(?<y>-?\d+(?:\.\d+)?)[,\s]+(?<z>-?\d+(?:\.\d+)?)(?:\s*\(?\s*(?<dim>[a-z_]+:[a-z_]+|[a-z_]+)\)?)?/i);
          const m = paste ?? direct;
          if (!m) {
            commandAudit(msg, tier, "broadcastpos", null, { success: false, reason: "could not parse coordinates" });
            return void msg.reply("usage: `!broadcastpos <x> <y> <z>` or paste a `!where` result line");
          }
          x = +m.groups.x; y = +m.groups.y; z = +m.groups.z;
          if (m.groups.dim) {
            dim = lower(m.groups.dim.trim());
            if (!dim.includes(":")) dim = "minecraft:" + dim;
          }
          player = paste ? m.groups.name : null;
        }
        const label = player ? `coord broadcast | ${player}` : "coord broadcast";
        broadcast({
          type: "ping",
          id: `bc-${Date.now().toString(36)}-${crypto.randomBytes(3).toString("hex")}`,
          label, x, y, z, dim, color: 0xFFFFAA33, from: "discord", t: Date.now(), ttl: 60_000,
        });
        commandAudit(msg, tier, "broadcastpos", player ?? `${x},${y},${z}`, { after: { x, y, z, dim } });
        return void msg.reply(fence(
          `${label} @ ${Math.round(x)}, ${Math.round(y)}, ${Math.round(z)} ` +
          `(${dim.replace("minecraft:", "")}) — on war tables for 60s`));
      }

      if (cmd === "panel") {
        if (!(await requireTier(msg, tier, "operator", "panel.open", null))) return;
        commandAudit(msg, tier, "panel.open", null);
        return void msg.reply({ embeds: [panelEmbed()], components: [panelRows()] });
      }

      if (cmd === "focus" || cmd === "unfocus") {
        if (!(await requireTier(msg, tier, "operator", `focus.${cmd === "unfocus" ? "remove" : "add"}`, parts[1]))) return;
        const arg = parts[1];
        if (!arg) return void msg.reply(cmd === "focus" ? "usage: `!focus <player>` or `!focus clear`" : "usage: `!unfocus <player>`");
        const action = cmd === "unfocus" ? "remove" : lower(arg) === "clear" ? "clear" : "add";
        const what = applyFocus(action, action === "clear" ? null : arg, actor, context);
        return void msg.reply(what ? fence(what.replace(/\*\*/g, "")) : "invalid Minecraft player name");
      }

      if (cmd === "ally" || cmd === "enemy") {
        if (!(await requireTier(msg, tier, "captain", `${cmd}.${parts[1]}`, parts[2]))) return;
        const action = lower(parts[1] ?? "");
        const name = parts[2];
        if (!["add", "remove"].includes(action) || !name) {
          return void msg.reply(`usage: \`!${cmd} add|remove <player>\``);
        }
        const result = applyAllegiance(cmd === "ally" ? "allies" : "enemies", action, name, actor, context);
        return void msg.reply(fence(result));
      }

      if (cmd === "kick") {
        const name = parts[1];
        if (!(await requireTier(msg, tier, "captain", "session.kick", name))) return;
        if (!name || !validName(name)) {
          commandAudit(msg, tier, "session.kick", name, { success: false, reason: "invalid player name" });
          return void msg.reply("usage: `!kick <user>`");
        }
        const user = userByName(name);
        if (!user) {
          commandAudit(msg, tier, "session.kick", name, { success: false, reason: "user not found" });
          return void msg.reply("user not found");
        }
        const count = revokeSessions(user.name, "kicked by relay captain");
        commandAudit(msg, tier, "session.kick", user.name, { before: { sessions: count }, after: { sessions: 0 } });
        return void msg.reply(`closed ${count} active session(s) for ${user.name}`);
      }

      if (cmd === "quarantine" || cmd === "unquarantine") {
        const name = parts[1];
        if (!(await requireTier(msg, tier, "captain", `user.${cmd}`, name))) return;
        const user = name ? userByName(name) : null;
        if (!user) {
          commandAudit(msg, tier, `user.${cmd}`, name, { success: false, reason: "user not found" });
          return void msg.reply("user not found");
        }
        const before = { quarantined: Boolean(user.quarantined) };
        user.quarantined = cmd === "quarantine";
        if (!user.quarantined) delete user.quarantined;
        if (cmd === "quarantine") removeReportedPositions(user.name);
        saveJson("users.json", USERS);
        commandAudit(msg, tier, `user.${cmd}`, user.name, { before, after: { quarantined: Boolean(user.quarantined) } });
        return void msg.reply(`${user.name} ${cmd === "quarantine" ? "quarantined; submissions are blocked" : "unquarantined"}`);
      }

      if (cmd === "user") {
        const action = lower(parts[1] ?? "");
        const name = parts[2];
        if (!(await requireTier(msg, tier, "admin", `user.${action || "unknown"}`, name))) return;
        if (!["add", "remove", "disable", "enable", "role", "rotate-token", "info", "token"].includes(action)) {
          return void msg.reply("usage: `!user add|remove|disable|enable|role|rotate-token|token|info ...`");
        }
        if (!name || !validName(name)) {
          commandAudit(msg, tier, `user.${action}`, name, { success: false, reason: "invalid Minecraft name" });
          return void msg.reply("invalid Minecraft name; expected 3-16 letters, numbers, or underscores");
        }
        if (action === "add") {
          const role = lower(parts[3] ?? "member");
          if (!validRole(role)) {
            commandAudit(msg, tier, "user.add", name, { success: false, reason: "invalid role" });
            return void msg.reply("role must be member, operator, captain, or admin");
          }
          if (userByName(name)) {
            commandAudit(msg, tier, "user.add", name, { success: false, reason: "user already exists" });
            return void msg.reply("user already exists");
          }
          const token = crypto.randomBytes(32).toString("base64url");
          const user = { name, tokenHash: hashToken(token), role };
          try {
            await msg.author.send(`OpenIntel token for **${name}** (${role}):\n\`${token}\`\nStore it securely; it will not be shown in the command channel.`);
          } catch {
            commandAudit(msg, tier, "user.add", name, { before: null, after: null, success: false, reason: "DM delivery failed; user not created" });
            return void msg.reply(`user was not created because DM delivery failed. Enable DMs and retry.`);
          }
          USERS.users.push(user);
          saveJson("users.json", USERS);
          broadcast(allegiancePayload());
          commandAudit(msg, tier, "user.add", name, { before: null, after: user });
          return void msg.reply(`user ${name} added; the token was sent to you by DM`);
        }
        const user = userByName(name);
        if (!user) {
          commandAudit(msg, tier, `user.${action}`, name, { success: false, reason: "user not found" });
          return void msg.reply("user not found");
        }
        const before = { ...user };
        if (action === "info") {
          commandAudit(msg, tier, "user.info", user.name, { before: null, after: null });
          return void msg.reply(fence([
            `name: ${user.name}`,
            `role: ${user.role ?? "member"}`,
            `disabled: ${Boolean(user.disabled)}`,
            `quarantined: ${Boolean(user.quarantined)}`,
            `token fingerprint: sha256:${hashFingerprint(user.tokenHash) ?? "none"}`,
            `active sessions: ${socketsFor(user.name).length}`,
          ].join("\n")));
        }
        if (action === "token") {
          // users.json keeps only digests, so the original token cannot be resent.
          commandAudit(msg, tier, "user.token", user.name, { success: false, reason: "tokens are stored hashed" });
          return void msg.reply(`tokens are stored only as hashes and cannot be resent. Use \`!user rotate-token ${user.name}\` to DM a new one.`);
        }
        if (action === "remove") USERS.users = USERS.users.filter((u) => lower(u.name) !== lower(user.name));
        if (action === "disable") user.disabled = true;
        if (action === "enable") delete user.disabled;
        if (action === "role") {
          const role = lower(parts[3] ?? "");
          if (!validRole(role)) {
            commandAudit(msg, tier, "user.role", user.name, { before, after: before, success: false, reason: "invalid role" });
            return void msg.reply("role must be member, operator, captain, or admin");
          }
          user.role = role;
        }
        let newToken = null;
        if (action === "rotate-token") {
          newToken = crypto.randomBytes(32).toString("base64url");
          try {
            await msg.author.send(`New OpenIntel token for **${user.name}**:\n\`${newToken}\`\nStore it securely; the prior token will be revoked now.`);
          } catch {
            commandAudit(msg, tier, `user.${action}`, user.name, { before, after: before, success: false, reason: "DM delivery failed; token unchanged" });
            return void msg.reply(`token was not rotated because DM delivery failed. Enable DMs and retry.`);
          }
          user.tokenHash = hashToken(newToken);
        }
        saveJson("users.json", USERS);
        broadcast(allegiancePayload());
        const revoked = ["remove", "disable", "rotate-token"].includes(action)
          ? revokeSessions(user.name, `${action} by relay admin`) : 0;
        const auditDetails = {
          before, after: action === "remove" ? null : user,
          reason: revoked ? `${revoked} session(s) revoked` : null,
        };
        if (newToken) {
          commandAudit(msg, tier, `user.${action}`, user.name, auditDetails);
          return void msg.reply(`token rotated for ${user.name}; the new token was sent to you by DM`);
        }
        commandAudit(msg, tier, `user.${action}`, user.name, auditDetails);
        return void msg.reply(`${user.name}: ${action} complete${revoked ? `; ${revoked} session(s) revoked` : ""}`);
      }
    } catch (e) {
      console.error("discord command failed:", e.message);
    }
  });

  bot.on("interactionCreate", async (interaction) => {
    if (!interaction.isButton() || !interaction.customId.startsWith("oi:") || !interaction.guild) return;
    try {
      if (TERMINAL_CHANNELS.size > 0 && !TERMINAL_CHANNELS.has(interaction.channelId)) {
        return void interaction.reply({ content: "This panel is not active in this channel.", ephemeral: true });
      }
      const tier = discordTier(interaction.member);
      const action = `panel.${interaction.customId.slice(3)}`;
      if (!hasTier(tier, "operator")) {
        audit({ actor: actorOf(interaction), tier, action, source: sourceOf(interaction), success: false, reason: "requires operator" });
        return void interaction.reply({ content: "Requires the operator role.", ephemeral: true });
      }
      audit({ actor: actorOf(interaction), tier, action, source: sourceOf(interaction) });
      if (interaction.customId === "oi:status") {
        return void interaction.update({ embeds: [panelEmbed()], components: [panelRows()] });
      }
      if (interaction.customId === "oi:online") {
        return void interaction.reply({ content: fence(renderList("online", 1)), ephemeral: true });
      }
      if (interaction.customId === "oi:lists") {
        return void interaction.reply({ content: fence(renderList("all", 1)), ephemeral: true });
      }
      const lines = recentAudit.slice(0, 8).map((entry) =>
        `${entry.timestamp} ${entry.actor}: ${entry.action}${entry.target ? ` ${entry.target}` : ""} [${entry.success ? "ok" : "failed"}]`);
      return void interaction.reply({ content: fence(pageText("recent activity", lines, 1, 8)), ephemeral: true });
    } catch (e) {
      console.error("discord interaction failed:", e.message);
    }
  });

  bot.once("clientReady", () => console.log(
    `Discord bridge ready as ${bot.user.tag} across ${bot.guilds.cache.size} guild(s), ` +
    `${TERMINAL_CHANNELS.size} terminal channel(s), ${SNITCH_CHANNELS.size} snitch channel(s)`
  ));
  bot.login(DISCORD.botToken).catch((e) => console.error("Discord login failed:", e.message));
}

server.listen(CONFIG.port ?? 8765, () => {
  console.log(`OpenIntel relay listening on :${CONFIG.port ?? 8765} for ${MINECRAFT_SERVER}`);
  adminLog("🟢 Relay server started");
});
