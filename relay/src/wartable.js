const { lower } = require("./common");
const { canReceiveIntel, isAdmin } = require("../visibility");
const { validStroke, WARTABLE_MAX_STROKES } = require("./intel-validation");
function createWarTable({ store, users, audit, broadcast, localBridge }) {
  let data = store.load("wartable.json", { strokes: [] });
  function save(strokes) { const next = { ...data, strokes }; store.save("wartable.json", next); data = next; }
  return {
    get data() { return data; }, save,
    sync(recipient) { return { type: "wartable_sync", strokes: data.strokes.filter(s => canReceiveIntel(recipient, s.from, null, users.userByName)) }; },
    handle(ws, user, msg) {
      const notice = text => ws.send(JSON.stringify({ type: "notice", msg: text }));
      if (user.quarantined) return;
      if (!isAdmin(user)) return notice("war table edits are admin-only");
      const action = String(msg.action ?? "");
      if (action === "add") {
        const s = msg.stroke;
        if (!validStroke(s)) return notice("invalid map stroke rejected");
        const bridge = localBridge(msg.bridge ?? s.bridge);
        if (!bridge) return;
        const clean = { bridge, id: s.id, tool: lower(s.tool), color: s.color, width: s.width,
          points: s.points.map(Number), from: ws.authedAs, t: Date.now() };
        if (s.label != null && s.label !== "") clean.label = s.label.slice(0, 96);
        const strokes = data.strokes.filter(v => v.id !== clean.id);
        if (strokes.length >= WARTABLE_MAX_STROKES) return notice(`war table is full (${WARTABLE_MAX_STROKES} strokes) — clear some first`);
        save([...strokes, clean]);
        broadcast({ type: "wartable", action, stroke: clean, bridge, t: clean.t, from: ws.authedAs });
      } else if (action === "delete") {
        const bridge = localBridge(msg.bridge);
        if (!bridge) return;
        const id = String(msg.id ?? ""), next = data.strokes.filter(s => s.id !== id);
        if (next.length !== data.strokes.length) {
          save(next); broadcast({ type: "wartable", action, id, bridge, from: ws.authedAs });
        }
      } else if (action === "clear") {
        const scope = msg.scope === "mine" ? "mine" : "all", before = data.strokes.length;
        const next = scope === "mine" ? data.strokes.filter(s => lower(s.from) !== lower(ws.authedAs)) : [];
        if (next.length !== before) {
          save(next); broadcast({ type: "wartable", action, scope, from: ws.authedAs });
          audit.audit({ actor: ws.authedAs, tier: ws.role, action: `wartable.clear.${scope}`, source: { channel: "websocket" },
            before: { strokes: before }, after: { strokes: next.length } });
        }
      }
    },
  };
}
module.exports = { createWarTable };
