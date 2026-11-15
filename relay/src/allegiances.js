const { lower, validName, validRole } = require("./common");
const { isAdmin, isTrial } = require("../visibility");
function createAllegiances({ store, users, audit, broadcast, config }) {
  let data = store.load("allegiances.json", { allies: [], enemies: [], focus: [] });
  const save = next => { store.save("allegiances.json", next); data = next; };
  function payload(recipient = null) {
    const role = validRole(recipient?.role) ? lower(recipient.role) : "member";
    if (isTrial(recipient)) return { type: "allegiances", role, users: [], allies: [], enemies: [], focus: [] };
    return { type: "allegiances", role, users: users.names(), allies: data.allies, enemies: data.enemies,
      focus: [...new Set([...(data.focus ?? []), ...(isAdmin(recipient) ? data.adminFocus ?? [] : [])])] };
  }
  return { get data() { return data; }, replace: save, payload,
    isEnemy: name => [...data.enemies, ...(data.focus ?? [])].map(lower).includes(lower(name)),
    focus(action, subject, actor, context = {}) {
      const field = context.adminOnly ? "adminFocus" : "focus", before = [...(data[field] ?? [])];
      let after = [...before];
      if (action === "add" && subject && validName(subject)) {
        if (!after.map(lower).includes(lower(subject))) after.push(subject);
      } else if (action === "remove" && subject && validName(subject)) after = after.filter(n => lower(n) !== lower(subject));
      else if (action === "clear") after = [];
      else { audit.audit({ ...context, actor, action: `focus.${action}`, target: subject, success: false, reason: "invalid action or player name" }); return null; }
      save({ ...data, [field]: after }); broadcast(payload());
      const what = action === "clear" ? "cleared all focus targets" : `${action === "add" ? "focused" : "unfocused"} **${subject}**`;
      audit.audit({ ...context, actor, action: `focus.${action}`, target: subject, before, after });
      if (!context.adminOnly) audit.webhook(config.webhooks?.alerts, { username: "OpenIntel", content: `🎯 **${actor}** ${what}` });
      return what;
    },
    change(kind, action, name, actor, context = {}) {
      const before = [...(data[kind] ?? [])], present = before.map(lower).includes(lower(name));
      const reason = !validName(name) ? "invalid player name" : action === "add" && present ? "already present"
        : action === "remove" && !present ? "not present" : null;
      if (reason) {
        audit.audit({ ...context, actor, action: `${kind}.${action}`, target: name, before, after: before, success: false, reason });
        return reason === "invalid player name" ? "invalid Minecraft player name"
          : `${name} is ${present ? "already" : "not"} on the ${kind} list`;
      }
      if (!["allies", "enemies"].includes(kind) || !["add", "remove"].includes(action)) throw new Error("invalid allegiance operation");
      const after = action === "add" ? [...before, name] : before.filter(n => lower(n) !== lower(name));
      save({ ...data, [kind]: after }); broadcast(payload());
      audit.audit({ ...context, actor, action: `${kind}.${action}`, target: name, before, after });
      return `${action === "add" ? "added" : "removed"} ${name} ${action === "add" ? "to" : "from"} ${kind}`;
    },
  };
}
module.exports = { createAllegiances };
