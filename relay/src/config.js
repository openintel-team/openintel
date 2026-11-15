const fs = require("fs");
const path = require("path");
const { normalizeMinecraftServer } = require("./common");
function resolvePaths(options = {}, env = process.env) {
  const legacy = env.OPENINTEL_RELAY_HOME || path.resolve(__dirname, "..");
  return {
    config: path.resolve(options.configPath || env.OPENINTEL_CONFIG || path.join(legacy, "config.json")),
    data: path.resolve(options.dataDir || env.OPENINTEL_DATA_DIR || legacy),
  };
}
function validateConfig(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) throw new Error("config must be an object");
  const config = { ...input, discord: input.discord ?? {}, webhooks: input.webhooks ?? {} };
  config.minecraftServer = normalizeMinecraftServer(config.minecraftServer);
  if (!config.minecraftServer || /[\s\x00-\x1f]/.test(config.minecraftServer)) throw new Error("config.minecraftServer is required and must be a server address");
  // Legacy configs bound all interfaces. New init/proxy installs explicitly
  // select loopback without silently breaking existing direct-WS deployments.
  config.host ??= "0.0.0.0";
  config.port ??= 8765;
  if (typeof config.host !== "string" || !config.host || /[\s\x00-\x1f]/.test(config.host)) throw new Error("invalid config.host");
  if (!Number.isInteger(config.port) || config.port < 0 || config.port > 65535) throw new Error("invalid config.port");
  for (const key of ["broadcastIntervalMs", "positionFlushMs", "fullSyncMs", "farPositionMs", "nearPositionRange",
    "minReportIntervalMs", "staleMs", "alertCooldownMs", "authRejectionLogCooldownMs", "maxMessageBytes", "maxPendingPerAddress"]) {
    if (config[key] != null && (!Number.isFinite(config[key]) || config[key] < 0)) throw new Error(`invalid config.${key}`);
  }
  for (const key of ["discord", "webhooks"]) {
    if (typeof config[key] !== "object" || Array.isArray(config[key])) throw new Error(`invalid config.${key}`);
  }
  for (const key of ["terminalChannelIds", "snitchChannelIds", "operatorRoleIds", "captainRoleIds", "adminRoleIds"]) {
    if (config.discord[key] != null && (!Array.isArray(config.discord[key])
      || !config.discord[key].every(id => typeof id === "string" && /^\d+$/.test(id)))) throw new Error(`invalid discord.${key}`);
  }
  for (const key of ["admin", "alerts"]) {
    if (config.webhooks[key]) {
      let url;
      try { url = new URL(config.webhooks[key]); } catch { throw new Error(`invalid webhooks.${key}`); }
      if (url.protocol !== "https:" || url.username || url.password) throw new Error(`webhooks.${key} must be HTTPS`);
    }
  }
  if (config.secureWebSocketUrl) {
    let url;
    try { url = new URL(config.secureWebSocketUrl); } catch { throw new Error("invalid secureWebSocketUrl"); }
    if (url.protocol !== "wss:" || url.username || url.password || url.hash
      || /[\x00-\x20\x7f]/.test(config.secureWebSocketUrl)) throw new Error("invalid secureWebSocketUrl");
  }
  return config;
}
function loadConfig(paths) {
  let parsed;
  try { parsed = JSON.parse(fs.readFileSync(paths.config, "utf8")); }
  catch (error) { throw new Error(`Cannot read configuration ${paths.config}: ${error.code || "invalid JSON"}`); }
  return validateConfig(parsed);
}
module.exports = { resolvePaths, validateConfig, loadConfig };
