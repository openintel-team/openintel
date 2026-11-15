const crypto = require("node:crypto");
const { lower, validName } = require("./common");
const { isAdmin } = require("../visibility");
const { validCoordinates, validDimension, validStroke, intelFields, pingTtl, WARTABLE_MAX_STROKES } = require("./intel-validation");
const { LAST_SEEN_MS, roundCoordinate, positionVelocity } = require("./positions");
const validBridge = (b, maxHops = 8) => b && typeof b.id === "string" && b.id.length > 0 && b.id.length <= 160
  && !/[\u0000-\u001f\u007f]/.test(b.id) && Number.isInteger(b.hops) && b.hops >= 0 && b.hops <= maxHops;
function bridgeOrigin(e) {
  const source = e.origin && typeof e.origin === "object" ? e.origin : e, origin = {};
  for (const key of ["from", "reporter"])
    if (typeof source[key] === "string" && source[key].length <= 64 && !/[\u0000-\u001f\u007f]/.test(source[key])) origin[key] = source[key];
  return origin;
}
function createBridge({ positions: p, getWarTable, broadcast }) {
  const bridgeSeen = new Map(), positionBridgeSeen = new Map();
  function claim(cache, b, ttl, max) {
    const now = Date.now();
    for (const [id, expiry] of cache) { if (expiry > now) break; cache.delete(id); }
    if (cache.has(b.id)) return false;
    if (cache.size >= max) cache.delete(cache.keys().next().value);
    cache.set(b.id, now + ttl); return true;
  }
  const claimBridge = b => claim(bridgeSeen, b, LAST_SEEN_MS, 10000);
  const claimPositionBridge = b => claim(positionBridgeSeen, b, p.staleMs, 100000);
  const newBridge = b => validBridge(b) ? { id: b.id, hops: b.hops } : { id: crypto.randomBytes(16).toString("hex"), hops: 0 };
  const localBridge = candidate => { const b = newBridge(candidate); return claimBridge(b) ? b : null; };
  function localPositionBridge(candidate) {
    const b = validBridge(candidate) ? { id: candidate.id, hops: candidate.hops } : { id: crypto.randomBytes(9).toString("base64url"), hops: 0 };
    return claimPositionBridge(b) ? b : null;
  }
  function handle(ws, user, event) {
    if (user.quarantined || !event || typeof event !== "object" || !validBridge(event.bridge, 7)) return;
    const type = event.type, now = Date.now(), table = getWarTable();
    if (!["snitch", "ping", "where", "position", "wartable"].includes(type)) return;
    if (type === "wartable" && (!isAdmin(user) || !["add", "delete"].includes(event.action))) return;
    const maxAge = type === "position" ? p.staleMs : type === "ping" ? pingTtl(event.ttl) : type === "snitch" ? 600000 : LAST_SEEN_MS;
    if (!Number.isFinite(event.t) || event.t > now + 5000 || now - event.t > maxAge) return;
    let fields;
    if (type !== "wartable") { fields = intelFields(type, event); if (!fields) return; }
    else {
      if (event.action === "add" ? !validStroke(event.stroke) : typeof event.id !== "string" || !/^[A-Za-z0-9_-]{4,64}$/.test(event.id)) return;
      for (const key of ["message", "label", "snitch", "action", "eventKind", "kind", "id"])
        if (event[key] != null && (typeof event[key] !== "string" || event[key].length > 1500)) return;
    }
    if (!(type === "position" ? claimPositionBridge : claimBridge)(event.bridge)) return;
    const bridge = { id: event.bridge.id, hops: event.bridge.hops + 1 };
    const clean = { type, t: event.t, bridge, origin: bridgeOrigin(event), from: user.name, reporter: user.name };
    if (type === "wartable") {
      clean.action = event.action;
      let strokes = table.data.strokes;
      if (event.action === "add") {
        const s = event.stroke;
        clean.stroke = { id: s.id, tool: lower(s.tool), points: [...s.points], color: s.color,
          width: s.width, from: user.name, t: event.t, bridge, origin: clean.origin };
        if (s.label != null) clean.stroke.label = s.label;
        const existing = strokes.find(v => v.id === s.id);
        if ((existing && existing.t > event.t) || (!existing && strokes.length >= WARTABLE_MAX_STROKES)) return;
        strokes = [...strokes.filter(v => v.id !== s.id), clean.stroke];
      } else {
        clean.id = event.id;
        if (strokes.some(s => s.id === event.id && s.t > event.t)) return;
        strokes = strokes.filter(s => s.id !== event.id);
      }
      table.save(strokes);
    } else {
      Object.assign(clean, fields);
      if (type === "position") {
        const key = `${lower(event.name)}@${lower(user.name)}`;
        if ((p.positions.get(key)?.t ?? -Infinity) > event.t) return;
        Object.assign(clean, positionVelocity(event));
        clean.x = roundCoordinate(clean.x); clean.y = roundCoordinate(clean.y); clean.z = roundCoordinate(clean.z);
        p.storePosition(key, clean); p.noteSeen(event.name, clean.x, clean.y, clean.z, clean.dim, user.name, "position", event.t);
        p.flush(now); return;
      }
      if (type === "where" || (type === "snitch" && validCoordinates(clean) && validName(clean.player)
        && validDimension(clean.dim) && clean.dim !== "openintel:unknown"))
        p.noteSeen(event.player, clean.x, clean.y, clean.z, clean.dim, user.name, type === "snitch" ? "snitch" : "position", event.t);
    }
    broadcast(clean);
  }
  return { bridgeSeen, positionBridgeSeen, claimBridge, claimPositionBridge, newBridge, localBridge, localPositionBridge, handle };
}
module.exports = { createBridge, validBridge };
