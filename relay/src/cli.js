const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { resolvePaths, loadConfig, validateConfig } = require("./config");
const { createStore } = require("./storage");
const { createUsers } = require("./users");
const { validName, validRole, hashToken, sealUsers } = require("./common");

function parseArgs(argv) {
  const result = { command: argv[0] || "help" };
  const flags = new Set(["noninteractive", "enable-admin-api", "network", "dry-run"]);
  const values = new Set(["config", "data-dir", "input", "minecraft-server", "admin-name", "host", "port", "secure-url", "token-file", "from", "name", "role"]);
  for (let i = 1; i < argv.length; i++) {
    const key = argv[i].replace(/^--/, "");
    if (argv[i] !== `--${key}` || (!flags.has(key) && !values.has(key))) throw new Error(`Unknown option: ${argv[i]}`);
    if (flags.has(key)) result[key] = true;
    else {
      if (argv[i + 1] == null || argv[i + 1].startsWith("--")) throw new Error(`Missing value for --${key}`);
      result[key] = argv[++i];
    }
  }
  return result;
}
function pathsFor(args) { return resolvePaths({ configPath: args.config, dataDir: args["data-dir"] }); }
function writePrivate(filename, data) {
  fs.mkdirSync(path.dirname(filename), { recursive: true, mode: 0o700 });
  const fd = fs.openSync(filename, "wx", 0o600);
  try { fs.writeFileSync(fd, data); }
  catch (error) { fs.unlinkSync(filename); throw error; }
  finally { fs.closeSync(fd); }
}
async function prompt(label, fallback, args) {
  if (args.noninteractive || !process.stdin.isTTY) {
    if (fallback != null && fallback !== "") return fallback;
    throw new Error(`${label} is required in noninteractive mode`);
  }
  const rl = require("node:readline/promises").createInterface({ input: process.stdin, output: process.stdout });
  try { return (await rl.question(`${label}${fallback ? ` [${fallback}]` : ""}: `)).trim() || fallback; }
  finally { rl.close(); }
}
function check(paths) {
  const config = loadConfig(paths), store = createStore(paths.data);
  store.load("users.json", { users: [] });
  store.load("allegiances.json", { allies: [], enemies: [] });
  store.load("wartable.json", { strokes: [] });
  fs.accessSync(paths.data, fs.constants.R_OK | fs.constants.W_OK);
  return config;
}
async function init(args) {
  const paths = pathsFor(args);
  let config;
  if (fs.existsSync(paths.config)) config = loadConfig(paths);
  else {
    const input = args.input ? JSON.parse(fs.readFileSync(args.input, "utf8")) : {};
    config = validateConfig({ ...input,
      minecraftServer: args["minecraft-server"] || input.minecraftServer || await prompt("Minecraft server", "", args),
      host: args.host ?? input.host ?? "127.0.0.1",
      port: Number(args.port ?? input.port ?? 8765),
      secureWebSocketUrl: args["secure-url"] ?? input.secureWebSocketUrl ?? "",
      discord: input.discord ?? {}, webhooks: input.webhooks ?? {},
    });
    if (config.port === 0) throw new Error("Setup requires a nonzero port");
    if (args["enable-admin-api"]) config.adminToken = crypto.randomBytes(32).toString("base64url");
  }
  fs.mkdirSync(paths.data, { recursive: true, mode: 0o700 });
  const store = createStore(paths.data), unlock = store.lock();
  try {
    const users = createUsers(store);
    // Validate all existing files before writing anything.
    const lists = store.load("allegiances.json", { allies: [], enemies: [], focus: [] });
    const table = store.load("wartable.json", { strokes: [] });
    let first;
    if (!users.data.users.length) {
      first = args["admin-name"] || await prompt("First admin's Minecraft username", "", args);
      if (!validName(first)) throw new Error("Admin name must be a Minecraft username (3–16 letters, digits, underscores)");
    }
    if (!fs.existsSync(paths.config)) writePrivate(paths.config, JSON.stringify(config, null, 2) + "\n");
    if (first) {
      const token = crypto.randomBytes(32).toString("base64url");
      const tokenFile = path.resolve(args["token-file"] || path.join(path.dirname(paths.config), "initial-token.txt"));
      // If writing or saving fails the invitation is not silently lost/rotated.
      writePrivate(tokenFile, `Minecraft account: ${first}\nRelay token: ${token}\n`);
      try { users.add(first, "admin", token); }
      catch (error) { fs.unlinkSync(tokenFile); throw error; }
      console.log(`Initial invitation saved to ${tokenFile} (private file; deliver once, then remove).`);
    } else { users.migrate(); console.log("Existing users and tokens preserved."); }
    for (const [name, data] of [["allegiances.json", lists], ["wartable.json", table]])
      if (!fs.existsSync(store.filePath(name))) store.save(name, data);
    console.log(`Configuration: ${paths.config}\nData: ${paths.data}`);
    console.log(`Relay URL: ${config.secureWebSocketUrl || `ws://${config.host}:${config.port}`}`);
  } finally { unlock(); }
}
function invite(args) {
  const paths = pathsFor(args); loadConfig(paths);
  if (!validName(args.name) || !validRole(args.role ?? "member") || !args["token-file"])
    throw new Error("invite requires --name NAME [--role ROLE] --token-file PRIVATE_PATH");
  const store = createStore(paths.data), unlock = store.lock();
  try {
    const users = createUsers(store);
    if (users.userByName(args.name)) throw new Error("User exists; use administration to rotate its token");
    const token = crypto.randomBytes(32).toString("base64url"), tokenFile = path.resolve(args["token-file"]);
    writePrivate(tokenFile, `Minecraft account: ${args.name}\nRelay token: ${token}\n`);
    try { users.add(args.name, args.role ?? "member", token); }
    catch (error) { fs.unlinkSync(tokenFile); throw error; }
    console.log(`Invitation written to ${tokenFile}`);
  } finally { unlock(); }
}
function unlockStale(args) {
  const file = path.join(pathsFor(args).data, "relay.lock");
  let value;
  try { value = fs.readFileSync(file, "utf8"); } catch (e) { if (e.code === "ENOENT") return; throw e; }
  if (!/^[1-9]\d*$/.test(value)) throw new Error("Malformed relay lock; inspect it manually");
  try { process.kill(Number(value), 0); }
  catch (e) {
    if (e.code !== "ESRCH") throw new Error("Cannot verify lock owner; leaving lock intact");
    if (fs.readFileSync(file, "utf8") !== value) throw new Error("Lock changed; retry after stopping all writers");
    fs.unlinkSync(file);
    console.log("Removed lock from a process that is no longer running");
    return;
  }
  throw new Error("Relay lock belongs to a live process; refusing to remove it");
}
function configureProxy(args) {
  const paths = pathsFor(args), config = loadConfig(paths);
  if (!args["secure-url"]) throw new Error("proxy requires --secure-url wss://HOST/openintel-relay");
  config.host = "127.0.0.1";
  config.secureWebSocketUrl = args["secure-url"];
  validateConfig(config);
  const stat = fs.statSync(paths.config), temporary = `${paths.config}.${process.pid}.tmp`;
  try {
    fs.writeFileSync(temporary, JSON.stringify(config, null, 2) + "\n", { flag: "wx", mode: stat.mode & 0o777 });
    if (process.platform !== "win32") fs.chownSync(temporary, stat.uid, stat.gid);
    fs.renameSync(temporary, paths.config);
  } finally { if (fs.existsSync(temporary)) fs.unlinkSync(temporary); }
  console.log("Proxy endpoint configured; restart the relay to apply");
}
function migrate(args) {
  if (!args.from) throw new Error("migrate requires --from LEGACY_DIRECTORY");
  const source = path.resolve(args.from), paths = pathsFor(args);
  if (source === paths.data || path.resolve(source, "config.json") === paths.config) throw new Error("Migration destination must be separate");
  if (fs.existsSync(path.join(source, "relay.lock"))) throw new Error("Source is locked; stop its relay first");
  const config = loadConfig({ config: path.join(source, "config.json") });
  const old = createStore(source);
  const data = { "users.json": sealUsers(old.load("users.json", { users: [] })).data,
    "allegiances.json": old.load("allegiances.json", { allies: [], enemies: [] }),
    "wartable.json": old.load("wartable.json", { strokes: [] }) };
  if (fs.existsSync(paths.config) || ["users.json", "allegiances.json", "wartable.json", "audit.jsonl"].some(n => fs.existsSync(path.join(paths.data, n))))
    throw new Error("Migration requires an empty destination; existing files will not be overwritten");
  if (args["dry-run"]) { console.log("Migration validated; no files changed. Stop any legacy writer before the real copy."); return; }
  fs.mkdirSync(paths.data, { recursive: true, mode: 0o700 });
  const next = createStore(paths.data), unlock = next.lock();
  const created = [];
  try {
    writePrivate(paths.config, JSON.stringify(config, null, 2) + "\n");
    created.push(paths.config);
    for (const [name, value] of Object.entries(data)) {
      next.save(name, value); created.push(next.filePath(name));
    }
    const audit = path.join(source, "audit.jsonl");
    if (fs.existsSync(audit)) {
      writePrivate(path.join(paths.data, "audit.jsonl"), fs.readFileSync(audit));
      created.push(path.join(paths.data, "audit.jsonl"));
    }
    console.log("Migrated state. Source files retained for rollback.");
  } catch (error) {
    for (const file of created.reverse()) fs.unlinkSync(file);
    throw error;
  } finally { unlock(); }
}
async function doctor(args) {
  const paths = pathsFor(args), config = check(paths);
  console.log(`Node ${process.versions.node}; configuration and state valid; data directory writable`);
  const host = ["0.0.0.0", "::"].includes(config.host) ? "127.0.0.1" : config.host;
  const url = `http://${host.includes(":") ? `[${host}]` : host}:${config.port}/readyz`;
  const response = await fetch(url, { signal: AbortSignal.timeout(5000) });
  if (!response.ok) throw new Error(`Relay not ready (HTTP ${response.status})`);
  console.log("Local relay ready");
  if (args.network) {
    const mojang = await fetch("https://sessionserver.mojang.com", { signal: AbortSignal.timeout(10000) });
    console.log(`Mojang HTTPS reachable (HTTP ${mojang.status}; this does not verify a player's session)`);
    if (config.secureWebSocketUrl) {
      const { WebSocket } = require("ws");
      await new Promise((resolve, reject) => {
        const socket = new WebSocket(config.secureWebSocketUrl, { handshakeTimeout: 10000 });
        socket.once("open", () => { socket.close(); resolve(); });
        socket.once("error", () => reject(new Error("Public WSS connection failed")));
      });
      console.log("Public WSS upgrade and certificate verified");
    }
  }
}
async function main(argv = process.argv.slice(2)) {
  const args = parseArgs(argv);
  if (args.command === "init") return init(args);
  if (args.command === "invite") return invite(args);
  if (args.command === "migrate") return migrate(args);
  if (args.command === "check-config") { check(pathsFor(args)); console.log("Configuration and state valid"); return; }
  if (args.command === "doctor") return doctor(args);
  if (args.command === "unlock-stale") return unlockStale(args);
  if (args.command === "proxy") return configureProxy(args);
  if (args.command !== "help") throw new Error(`Unknown command: ${args.command}`);
  console.log("OpenIntel relay: init | check-config | doctor [--network] | invite | migrate | unlock-stale | proxy\n"
    + "Paths: --config FILE --data-dir DIRECTORY (then OPENINTEL_CONFIG/OPENINTEL_DATA_DIR, OPENINTEL_RELAY_HOME, relay/)\n"
    + "init: [--input JSON] [--minecraft-server HOST] [--admin-name NAME] [--host ADDRESS] [--port PORT]\n"
    + "      [--secure-url WSS_URL] [--enable-admin-api] [--token-file FILE] [--noninteractive]\n"
    + "invite: --name NAME [--role member] --token-file FILE (service must be stopped)\n"
    + "migrate: --from OLD_DIRECTORY [--dry-run] (stop legacy service first)");
}
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
module.exports = { main, init, check, parseArgs, migrate };
