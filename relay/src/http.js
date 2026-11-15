const { adminTokenDigest, adminTokenMatches } = require("./common");
function attachRoutes(app, { config, users, allegiances, sessions, clients, broadcast, audit, ready }) {
  const digest = adminTokenDigest(config.adminToken);
  if (!digest) console.log("Admin HTTP API disabled: set adminToken to a random string of at least 32 characters");
  function requireAdmin(req, res, next) {
    if (!digest) return res.status(403).json({ error: "admin API disabled" });
    if (!adminTokenMatches(digest, req.headers["x-admin-token"])) return res.status(403).json({ error: "bad admin token" });
    next();
  }
  app.get("/healthz", (req, res) => res.json({ status: "alive" }));
  app.get("/readyz", (req, res) => res.status(ready() ? 200 : 503).json({ status: ready() ? "ready" : "stopping" }));
  app.get("/allegiances", requireAdmin, (req, res) => res.json(allegiances.data));
  app.put("/allegiances", requireAdmin, (req, res) => {
    const before = allegiances.data;
    allegiances.replace({ allies: req.body.allies ?? [], enemies: req.body.enemies ?? [], focus: req.body.focus ?? before.focus ?? [] });
    broadcast(allegiances.payload());
    audit.audit({ actor: "REST admin", tier: "admin", action: "allegiances.replace", source: { channel: "REST" }, before, after: allegiances.data });
    res.json({ ok: true });
  });
  app.get("/users", requireAdmin, (req, res) => res.json({ users: users.data.users.map(u => u.name) }));
  app.put("/users", requireAdmin, (req, res) => {
    const before = users.data;
    users.replace({ users: req.body?.users ?? [] }); broadcast(allegiances.payload());
    for (const client of clients()) {
      const user = sessions.currentUser(client), old = before.users.find(u => u.name === client.authedAs);
      if (client.authedAs && (!user || user.disabled || (old && old.tokenHash !== user.tokenHash))) client.close(4001, "session revoked");
    }
    audit.audit({ actor: "REST admin", tier: "admin", action: "users.replace", source: { channel: "REST" }, before, after: users.data });
    res.json({ ok: true });
  });
  app.get("/online", requireAdmin, (req, res) => res.json({ online: [...clients()].filter(c => c.authedAs).map(c => c.authedAs) }));
}
module.exports = { attachRoutes };
