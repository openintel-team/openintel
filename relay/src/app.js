const http = require("http");
const express = require("express");
const { WebSocketServer } = require("ws");
const { resolvePaths, loadConfig, validateConfig } = require("./config");
const { createStore } = require("./storage");
const { createUsers } = require("./users");
const { createAudit } = require("./audit");
const { createPositions } = require("./positions");
const { createAllegiances } = require("./allegiances");
const { createWarTable } = require("./wartable");
const { createBridge } = require("./bridge");
const { createFanout } = require("./fanout");
const { createSessions } = require("./sessions");
const { attachConnections } = require("./websocket");
const { attachRoutes } = require("./http");
const { attachRelayTransport } = require("../transport");
const { createDiscord } = require("./discord");

// No reads, listeners, timers or network connections until start(). One factory
// instance owns one complete relay and all its resources.
function createRelay(options = {}) {
  let status = "new", server, wss, services, discord, releaseLock, stopping;
  const intervals = [];
  const logger = options.logger ?? console;
  async function start() {
    if (status !== "new") throw new Error(`Cannot start relay in state ${status}`);
    status = "starting";
    try {
      const paths = resolvePaths(options);
      const config = options.config ? validateConfig(options.config) : loadConfig(paths);
      const store = options.store ?? createStore(paths.data);
      if (options.lock !== false) releaseLock = store.lock();
      const users = createUsers(store);
      const app = express();
      app.use(express.json({ limit: "256kb" }));
      server = http.createServer(app);
      wss = new WebSocketServer({ noServer: true, maxPayload: config.maxMessageBytes > 0 ? config.maxMessageBytes : 256 * 1024 });
      const clients = () => wss.clients;
      const audit = createAudit(config, store, { fetch: options.fetch, logger });
      let deliver;
      const broadcast = message => deliver(message);
      const positions = createPositions({ config, users, clients, broadcast });
      const allegiances = createAllegiances({ config, store, users, audit, broadcast });
      let wartable;
      const bridge = createBridge({ positions, getWarTable: () => wartable, broadcast });
      wartable = createWarTable({ store, users, audit, broadcast, localBridge: bridge.localBridge });
      deliver = createFanout({ users, clients, allegiances, wartable, bridge });
      const sessions = createSessions({ clients, users });
      services = { config, store, users, clients, audit, positions, allegiances, bridge, wartable, sessions, broadcast };
      attachConnections({ ...services, wss, verify: options.verify });
      attachRelayTransport(server, wss, config.secureWebSocketUrl);
      server.on("upgrade", (req, socket) => { if (status === "stopping") socket.destroy(); });
      attachRoutes(app, { ...services, ready: () => status === "running" });
      users.migrate();
      // Keep startup synchronous until listen so deterministic fixture timers can
      // exercise handlers without waiting for a network event.
      intervals.push(setInterval(positions.fullTick, positions.broadcastMs));
      intervals.push(setInterval(() => positions.flush(Date.now()), positions.flushMs));
      discord = createDiscord(services, { logger, factory: options.discordFactory });
      discord.start();
      await new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen({ port: config.port, host: config.host }, () => { server.removeListener("error", reject); resolve(); });
      });
      if (status !== "starting") throw new Error("Startup cancelled");
      status = "running";
      logger.log(`OpenIntel relay listening on ${config.host}:${server.address?.()?.port ?? config.port} for ${config.minecraftServer}`);
      audit.adminLog("🟢 Relay server started");
      return api;
    } catch (error) {
      try { await stop(); } catch { logger.error("Cleanup after failed startup failed"); }
      throw error;
    }
  }
  function stop() {
    if (stopping) return stopping;
    status = "stopping";
    stopping = (async () => {
      for (const timer of intervals) clearInterval(timer);
      discord?.stop(); services?.audit.stop();
      for (const ws of wss?.clients ?? []) ws.close(1001, "relay shutdown");
      if (wss) {
        await new Promise(resolve => {
          const timer = setTimeout(() => { for (const ws of wss.clients) ws.terminate(); resolve(); }, 2000);
          wss.close(() => { clearTimeout(timer); resolve(); });
        });
      }
      if (server?.listening) await new Promise(resolve => {
        const timer = setTimeout(() => server.closeAllConnections?.(), 2500);
        server.close(() => { clearTimeout(timer); resolve(); });
        server.closeIdleConnections?.();
      });
      releaseLock?.(); releaseLock = null; status = "stopped";
    })();
    return stopping;
  }
  const api = { start, stop, get status() { return status; }, get services() { return services; },
    address: () => server?.address(), get discordStatus() { return discord?.status ?? "disabled"; } };
  return api;
}
module.exports = { createRelay };
