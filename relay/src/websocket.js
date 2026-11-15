const { lower, validName, validRole, hasTier, normalizeMinecraftServer } = require("./common");
const { isAdmin, isTrial, canReceiveIntel, visibilityKey } = require("../visibility");
const { createIdentityVerification } = require("../authentication");
const { validCoordinates, validDimension, intelFields } = require("./intel-validation");
const { POSITION_PROTOCOL, reportAge, roundCoordinate, positionVelocity } = require("./positions");
function attachConnections({ wss, config, users, sessions, audit, positions: p, bridge, wartable, allegiances, broadcast, verify }) {
  const maxPending = Number.isInteger(config.maxPendingPerAddress) ? config.maxPendingPerAddress : 16;
  wss.on("connection", (ws, req) => {
    ws.authedAs = null;
    const address = String(req.socket.remoteAddress ?? "unknown"), tag = audit.clientTag(address);
    ws.on("error", () => { ws.close(1011, "socket error"); });
    const settle = sessions.pending(address, maxPending);
    if (!settle) {
      audit.rejection(`pending:${tag}`, `⚠️ Too many pending logins from client \`${tag}\``, address);
      ws.close(1013, "too many pending connections"); return;
    }
    ws.on("close", settle);
    const identity = createIdentityVerification(ws, { userByToken: users.userByToken, ...(verify ? { verify } : {}),
      onRejected(reason, invitationName) {
        audit.rejection(`identity:${tag}`, invitationName
          ? `❌ Token for **${invitationName}** used from client \`${tag}\`; Minecraft account verification failed (${reason})`
          : `❌ Minecraft account verification failed from client \`${tag}\` (${reason})`, address);
      },
      onVerified(user, minecraftUuid) {
        settle();
        if (user.minecraftUuid == null) user = users.update(user.name, { minecraftUuid });
        ws.authedAs = user.name;
        ws.role = validRole(user.role) ? lower(user.role) : "member";
        ws.visibilityKey = visibilityKey(user, users.data.users);
        ws.send(JSON.stringify({ ...allegiances.payload(user), type: "welcome", minecraftServer: config.minecraftServer,
          role: ws.role, relayCut: user.relayCut === true, bridgeVersion: 1, positionVersion: POSITION_PROTOCOL }));
        if (!isTrial(user)) ws.send(JSON.stringify(wartable.sync(user)));
        audit.adminLog(`✅ **${user.name}** connected (${ws.role})`);
      },
    });
    ws.on("message", raw => {
      try {
        let msg;
        try { msg = JSON.parse(raw); } catch { return; }
        if (!msg || typeof msg !== "object" || Array.isArray(msg)) return;
        if (msg.type === "authenticate") { void identity.complete(msg.clientNonce); return; }
        if (msg.type === "hello") {
          const user = users.userByToken(msg.token);
          if (!user) {
            ws.send(JSON.stringify({ type: "deny", reason: "bad_token" }));
            audit.rejection(`token:${tag}`, `❌ Auth failure from client \`${tag}\``, address);
            ws.close(4001, "bad token"); return;
          }
          const server = normalizeMinecraftServer(msg.minecraftServer);
          if (server !== config.minecraftServer) {
            ws.send(JSON.stringify({ type: "deny", reason: "wrong_server", expectedServer: config.minecraftServer }));
            audit.rejection(`server:${lower(user.name)}:${server || "missing"}`,
              `❌ **${user.name}** rejected from Minecraft server \`${server || "missing"}\``);
            ws.close(4003, "wrong Minecraft server"); return;
          }
          ws.minecraftServer = server;
          ws.positionVersion = Number.isInteger(msg.positionVersion) && msg.positionVersion >= POSITION_PROTOCOL ? POSITION_PROTOCOL : 1;
          identity.begin(msg.token); return;
        }
        if (!ws.authedAs) return;
        const user = sessions.currentUser(ws);
        if (!user || user.disabled || !user.tokenHash) { ws.close(4001, "session revoked"); return; }
        ws.role = validRole(user.role) ? lower(user.role) : "member";
        const notice = text => ws.send(JSON.stringify({ type: "notice", msg: text }));
        if (msg.type === "bridge") return bridge.handle(ws, user, msg.event);
        if (msg.type === "cut") {
          if (!isAdmin(user)) return notice("/oi cut is admin-only");
          if (msg.action === "status") return notice(`Relay cut ${user.relayCut ? "ON: admins only" : "OFF: all ranks"}`);
          if (![undefined, "toggle", "on", "off"].includes(msg.action)) return;
          const before = user.relayCut === true;
          const cut = msg.action === "on" || ((msg.action === undefined || msg.action === "toggle") && !before);
          try { users.update(user.name, { relayCut: cut }); } catch { return notice("Could not save relay cut; unchanged"); }
          p.sendVisibleState();
          notice(`Relay cut ${cut ? "ON: lower ranks no longer receive your relay" : "OFF: sharing with all ranks"}. Admin-to-admin relay is unchanged.`);
          audit.audit({ actor: user.name, tier: "admin", action: "relay.cut", target: user.name,
            source: { channel: "websocket" }, before: { relayCut: before }, after: { relayCut: cut } }); return;
        }
        if (msg.type === "focus") {
          if (user.quarantined) return notice("quarantined sessions cannot change focus targets");
          if (!hasTier(ws.role, "operator")) return notice("/oi focus requires the Operator role");
          return allegiances.focus(msg.action, msg.subject ? String(msg.subject) : null, ws.authedAs,
            { tier: ws.role, adminOnly: false, source: { guild: null, channel: "websocket" } });
        }
        if (msg.type === "wartable") return wartable.handle(ws, user, msg);
        if (user.quarantined) return;
        if (msg.type === "ping" || msg.type === "snitch") {
          const fields = intelFields(msg.type, msg); if (!fields) return;
          const envelope = bridge.localBridge(msg.bridge); if (!envelope) return;
          const clean = { type: msg.type, ...fields, bridge: envelope, from: ws.authedAs, reporter: ws.authedAs, t: Date.now() };
          broadcast(clean);
          if (clean.type === "snitch" && clean.player) p.noteSeen(clean.player, clean.x, clean.y, clean.z, clean.dim ?? clean.world, ws.authedAs, "snitch");
        } else if (msg.type === "positions" && Array.isArray(msg.reports)) {
          const now = Date.now(); if (!p.takePositionToken(ws, now)) return;
          for (const r of msg.reports.slice(0, 100)) {
            if (!r || typeof r !== "object" || typeof r.subject !== "string" || !validName(r.subject)
              || !validCoordinates(r) || !validDimension(r.dim)) continue;
            const envelope = bridge.localPositionBridge(r.bridge); if (!envelope) continue;
            const t = now - reportAge(r.age);
            p.storePosition(`${lower(r.subject)}@${lower(ws.authedAs)}`, { name: r.subject,
              x: roundCoordinate(r.x), y: roundCoordinate(r.y), z: roundCoordinate(r.z), bridge: envelope,
              dim: r.dim, t, reporter: ws.authedAs, ...positionVelocity(r) });
            p.noteSeen(r.subject, r.x, r.y, r.z, r.dim, ws.authedAs, "position", t);
            if (allegiances.isEnemy(r.subject) && canReceiveIntel({ role: "member" }, ws.authedAs, r.subject, users.userByName))
              audit.enemyAlert(r.subject, r.x, r.z, r.dim, ws.authedAs);
          }
          p.flush(now);
        }
      } catch {
        audit.adminLog("Relay operation failed; check local storage permissions.");
        ws.close(1011, "relay operation failed");
      }
    });
    ws.on("close", () => { if (ws.authedAs) audit.adminLog(`👋 **${ws.authedAs}** disconnected`); });
  });
}
module.exports = { attachConnections };
