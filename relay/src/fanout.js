const { lower } = require("./common");
const { isAdmin, canReceiveIntel, visiblePositions, visibilityKey } = require("../visibility");
const { validBridge } = require("./bridge");
const { POSITION_PROTOCOL } = require("./positions");
function createFanout({ users, clients, allegiances, wartable, bridge }) {
  const recentSnitch = new Map();
  function dedupe(m, audience) {
    const id = m.x != null && m.y != null && m.z != null ? `${lower(m.player ?? "?")}@${m.x},${m.y},${m.z}` : String(m.message ?? "");
    const key = `${audience}:${id}`, now = Date.now(), last = recentSnitch.get(key) ?? 0;
    if (now - last < 10000) return false;
    recentSnitch.set(key, now);
    if (recentSnitch.size > 200) for (const [k, v] of recentSnitch) if (now - v > 60000) recentSnitch.delete(k);
    return true;
  }
  return function broadcast(obj) {
    if (["snitch", "ping", "where", "wartable"].includes(obj.type) && !(obj.type === "wartable" && obj.action === "clear")) {
      if (!validBridge(obj.bridge)) { obj.bridge = bridge.newBridge(null); bridge.claimBridge(obj.bridge); }
      if (obj.t == null) obj.t = obj.stroke?.t ?? Date.now();
      if (obj.type === "wartable" && obj.action === "add") obj.stroke.bridge = obj.bridge;
    }
    const delivered = new Set(), decisions = new Map(), lanes = new Map(), now = Date.now();
    for (const client of clients()) {
      const identity = client.authedAs ? lower(client.authedAs) : null, recipient = identity ? users.userByName(identity) : null;
      if (client.readyState !== 1 || !recipient || recipient.disabled || delivered.has(identity)) continue;
      const key = visibilityKey(recipient, users.data.users), reset = client.visibilityKey !== key;
      if (reset) {
        client.send(JSON.stringify({ type: "intel_reset" }));
        client.send(JSON.stringify(allegiances.payload(recipient)));
        client.send(JSON.stringify(wartable.sync(recipient))); client.visibilityKey = key;
      }
      let payload = obj;
      if (obj.type === "state") {
        if (obj.deltaClients === false && client.positionVersion === POSITION_PROTOCOL && !reset) continue;
        if (!lanes.has(key)) lanes.set(key, JSON.stringify({ type: "state", replace: true,
          players: visiblePositions(obj.players, recipient, users.userByName).map(p => ({ ...p, age: Math.max(0, now - p.t) })) }));
        client.send(lanes.get(key)); client.farPending?.clear(); delivered.add(identity); continue;
      } else if (obj.type === "allegiances") payload = allegiances.payload(recipient);
      else if (["snitch", "ping", "where"].includes(obj.type)) {
        if (!canReceiveIntel(recipient, obj.from, obj.type === "ping" ? null : obj.player, users.userByName)) continue;
        if (obj.type === "snitch") {
          const lane = isAdmin(recipient) ? "admin" : "public";
          if (!decisions.has(lane)) decisions.set(lane, dedupe(obj, lane));
          if (!decisions.get(lane)) continue;
        }
      } else if (obj.type === "wartable" && !canReceiveIntel(recipient, obj.from, null, users.userByName)) continue;
      client.send(JSON.stringify(payload)); delivered.add(identity);
    }
    return delivered.size > 0;
  };
}
module.exports = { createFanout };
