const { lower, validName } = require("./common");
const { visiblePositions, visibilityKey } = require("../visibility");
const clamp = (v, fallback, min, max) => Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : fallback;
const roundCoordinate = v => Math.round(v * 100) / 100;
// Preserve the live relay's optional horizontal-velocity extension.
function positionVelocity(report) {
  const valid = value => Number.isFinite(value) && Math.abs(value) <= 100;
  return valid(report?.vx) && valid(report?.vz)
    ? { vx: roundCoordinate(report.vx), vz: roundCoordinate(report.vz) } : {};
}
const reportAge = age => Number.isFinite(age) && age > 0 ? Math.min(age, 1000) : 0;
const POSITION_PROTOCOL = 2, LAST_SEEN_MS = 6 * 60 * 60 * 1000;
function encodePositions(reports, now) {
  const strings = [], index = new Map();
  const ref = s => { if (!index.has(s)) { index.set(s, strings.length); strings.push(s); } return index.get(s); };
  return { type: "pos", s: strings, p: reports.map(r => {
    const row = [r.name, r.x, r.y, r.z, ref(r.dim),
      Math.max(0, now - r.t), ref(r.reporter ?? "?"), r.bridge?.id ?? "", r.bridge?.hops ?? 0];
    if (r.vx != null && r.vz != null) row.push(r.vx, r.vz);
    return row;
  }) };
}
function createPositions({ config, users, clients, broadcast }) {
  const positions = new Map(), lastSeen = new Map(), dirty = new Set();
  let lastFlush = -Infinity, lastFull = -Infinity;
  const staleMs = config.staleMs ?? 10000;
  const broadcastMs = clamp(config.broadcastIntervalMs, 250, 50, 10000);
  const flushMs = clamp(config.positionFlushMs, 50, 10, 1000);
  const fullMs = clamp(config.fullSyncMs, 1000, 250, 5000);
  const farMs = clamp(config.farPositionMs, 500, 0, 5000);
  const nearRange = clamp(config.nearPositionRange, 1000, 0, 30000000);
  const reportMs = clamp(config.minReportIntervalMs, 25, 0, 1000);
  function noteSeen(name, x, y, z, dim, reporter, kind, t = Date.now()) {
    if (typeof name !== "string" || !validName(name) || ![x, y, z].every(Number.isFinite)) return;
    if ((lastSeen.get(lower(name))?.t ?? -Infinity) > t) return;
    lastSeen.set(lower(name), { name, x: +x, y: +y, z: +z, dim: typeof dim === "string" && dim ? dim : "minecraft:overworld", reporter, kind, t });
  }
  function storePosition(key, entry) {
    const old = positions.get(key);
    positions.set(key, entry);
    if (!old || old.dim !== entry.dim || old.reporter !== entry.reporter
      || ["x", "y", "z"].some(k => Math.abs(old[k] - entry[k]) > .01)
      || (old.vx ?? 0) !== (entry.vx ?? 0) || (old.vz ?? 0) !== (entry.vz ?? 0)) dirty.add(lower(entry.name));
  }
  function takePositionToken(ws, now) {
    if (reportMs <= 0) return true;
    const refill = Number.isFinite(ws.positionTokensAt) ? (now - ws.positionTokensAt) / reportMs : 8;
    ws.positionTokens = Math.min(8, (ws.positionTokens ?? 0) + Math.max(0, refill));
    ws.positionTokensAt = now;
    if (ws.positionTokens < 1) return false;
    ws.positionTokens--;
    return true;
  }
  function isFar(name, report) {
    if (farMs <= 0 || nearRange <= 0) return false;
    const self = positions.get(`${lower(name)}@${lower(name)}`);
    return Boolean(self && (self.dim !== report.dim || Math.hypot(self.x - report.x, self.z - report.z) > nearRange));
  }
  function flush(now = Date.now()) {
    if (now - lastFlush < flushMs) return;
    const recipients = [], wanted = new Set(dirty);
    for (const client of clients()) {
      if (client.readyState !== 1 || !client.authedAs || client.positionVersion !== POSITION_PROTOCOL) continue;
      const user = users.userByName(client.authedAs);
      if (!user || user.disabled || client.visibilityKey !== visibilityKey(user, users.data.users)) continue;
      recipients.push({ client, user });
      for (const subject of client.farPending?.keys() ?? []) wanted.add(subject);
    }
    if (!wanted.size) return;
    const changed = new Set(dirty); dirty.clear();
    if (!recipients.length) return;
    const bySubject = new Map([...wanted].map(s => [s, []]));
    for (const report of positions.values()) bySubject.get(lower(report.name))?.push(report);
    const lanes = new Map(), delivered = new Set();
    for (const { client, user } of recipients) {
      if (delivered.has(lower(client.authedAs))) continue;
      const lane = client.visibilityKey;
      if (!lanes.has(lane)) {
        const chosen = new Map();
        for (const [subject, reports] of bySubject) {
          const [best] = visiblePositions(reports, user, users.userByName);
          if (best) chosen.set(subject, best);
        }
        lanes.set(lane, chosen);
      }
      const chosen = lanes.get(lane), out = [];
      client.farPending ??= new Map(); client.posSentAt ??= new Map();
      for (const subject of new Set([...changed, ...client.farPending.keys()])) {
        const report = chosen.get(subject);
        if (!report) { client.farPending.delete(subject); continue; }
        if (lower(report.name) === lower(client.authedAs)) continue;
        if (isFar(client.authedAs, report) && now - (client.posSentAt.get(subject) ?? -Infinity) < farMs) {
          client.farPending.set(subject, true); continue;
        }
        client.farPending.delete(subject); client.posSentAt.set(subject, now); out.push(report);
      }
      if (client.posSentAt.size > 4096) client.posSentAt.clear();
      if (!out.length) continue;
      client.send(JSON.stringify(encodePositions(out, now))); delivered.add(lower(client.authedAs));
    }
    if (delivered.size) lastFlush = now;
  }
  const sendVisibleState = (deltaClients = true) => broadcast({ type: "state", players: [...positions.values()], deltaClients });
  return { positions, lastSeen, staleMs, broadcastMs, flushMs, storePosition, noteSeen, takePositionToken, flush, sendVisibleState,
    removeReported(name) { for (const [k, v] of positions) if (lower(v.reporter) === lower(name)) positions.delete(k); },
    fullTick() {
      const now = Date.now();
      for (const [k, v] of positions) if (now - v.t > staleMs) { positions.delete(k); dirty.add(lower(v.name)); }
      for (const [k, v] of lastSeen) if (now - v.t > LAST_SEEN_MS) lastSeen.delete(k);
      const delta = now - lastFull >= fullMs;
      if (delta) lastFull = now;
      sendVisibleState(delta);
    } };
}
module.exports = { createPositions, roundCoordinate, positionVelocity, reportAge, POSITION_PROTOCOL, LAST_SEEN_MS };
