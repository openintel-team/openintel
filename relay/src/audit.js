const crypto = require("node:crypto");
const { lower, safeAuditValue, consumeRateLimit } = require("./common");
function createAudit(config, store, { fetch: request = globalThis.fetch, logger = console } = {}) {
  const recent = [], rejections = new Map(), alerts = new Map(), pending = new Set();
  const key = crypto.randomBytes(32);
  let closed = false;
  async function webhook(url, payload) {
    if (!url || closed) return;
    const controller = new AbortController();
    pending.add(controller);
    const timeout = setTimeout(() => controller.abort(), 10_000);
    timeout.unref?.();
    try {
      const response = await request(url, { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload), signal: controller.signal });
      if (response?.ok === false) logger.error(`Webhook delivery failed (HTTP ${response.status})`);
    } catch { if (!closed) logger.error("Webhook delivery failed"); }
    finally { clearTimeout(timeout); pending.delete(controller); }
  }
  const adminLog = message => webhook(config.webhooks?.admin,
    { content: `🛠️ ${message}`, username: "OpenIntel Admin", allowed_mentions: { parse: [] } });
  return {
    recent, webhook, adminLog,
    stop() { closed = true; for (const controller of pending) controller.abort(); pending.clear(); },
    clientTag: address => crypto.createHmac("sha256", key).update(address).digest("hex").slice(0, 8),
    audit({ actor, tier, action, target = null, source = {}, before = null, after = null, success = true, reason = null }) {
      const entry = safeAuditValue({ timestamp: new Date().toISOString(), actor, tier, action, target,
        source: { guild: source.guild ?? null, channel: source.channel ?? null }, before, after, success: Boolean(success), reason });
      try { store.appendAudit(entry); } catch { logger.error("audit write failed"); }
      recent.unshift(entry); if (recent.length > 20) recent.length = 20;
      adminLog(`**${actor}** (${tier}) — \`${action}\`${target ? ` on **${target}**` : ""} ${success ? "succeeded" : `failed${reason ? `: ${reason}` : ""}`}`);
      return entry;
    },
    rejection(id, message, address = null) {
      const now = Date.now(), cooldown = config.authRejectionLogCooldownMs ?? 300000;
      const result = consumeRateLimit(rejections, id, now, cooldown);
      if (!result.allowed) return;
      adminLog(message + (result.suppressed ? ` (${result.suppressed} similar attempts suppressed)` : ""));
      if (config.logRejectedIps === true && address != null) logger.log(`Auth rejection from ${address}`);
      if (rejections.size > 1000) for (const [k, v] of rejections) if (v.last < now - cooldown * 2) rejections.delete(k);
    },
    enemyAlert(enemy, x, z, dim, reporter) {
      const now = Date.now();
      if (now - (alerts.get(lower(enemy)) ?? 0) < (config.alertCooldownMs ?? 300000)) return;
      alerts.set(lower(enemy), now);
      webhook(config.webhooks?.alerts, { username: "OpenIntel",
        content: `${config.webhooks?.alertRoleId ? `<@&${config.webhooks.alertRoleId}> ` : ""}🔴 **${enemy}** spotted at **${Math.round(x)}, ${Math.round(z)}** (${dim.replace("minecraft:", "")}) — reported by ${reporter}`,
        allowed_mentions: { parse: ["roles"] } });
    },
  };
}
module.exports = { createAudit };
