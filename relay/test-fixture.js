// CommonJS loader for legacy behavior fixtures. All local production modules run
// under the same fake clock/files/network as the original source-level fixture.
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
function boot(context) {
  if (!vm.isContext(context)) context = vm.createContext(context);
  Object.assign(context, { URL, AbortController, structuredClone,
    setTimeout: context.setTimeout ?? setTimeout, clearTimeout: context.clearTimeout ?? clearTimeout,
    clearInterval: context.clearInterval ?? clearInterval });
  const cache = new Map(), external = context.require;
  function load(filename) {
    filename = path.resolve(filename);
    if (cache.has(filename)) return cache.get(filename).exports;
    const module = { exports: {} };
    cache.set(filename, module);
    const localRequire = name => {
      if (!name.startsWith(".")) {
        const value = external(name);
        if (name === "fs") return { ...value, readFileSync(...args) {
          try { return value.readFileSync(...args); }
          catch (error) { if (/missing/.test(error.message)) error.code = "ENOENT"; throw error; }
        } };
        if (name === "ws" && !value.WebSocketServer.prototype.close)
          value.WebSocketServer.prototype.close = function(callback) { callback?.(); };
        return value;
      }
      const resolved = path.resolve(path.dirname(filename), `${name}.js`);
      if (["authentication.js", "visibility.js"].includes(path.basename(resolved)))
        return external(`./${path.basename(resolved, ".js")}`);
      return load(resolved);
    };
    const fn = vm.runInContext(`(function(require,module,exports,__dirname){${fs.readFileSync(filename, "utf8")}\n})`, context, { filename });
    fn(localRequire, module, module.exports, path.dirname(filename));
    return module.exports;
  }
  const relay = load(path.join(__dirname, "src/app.js")).createRelay({ lock: false });
  relay.start().catch(error => { throw error; });
  const s = relay.services;
  // Existing assertions inspect these caches; the production modules own them.
  Object.assign(context, { relay, lastSeen: s.positions.lastSeen, positions: s.positions.positions,
    bridgeSeen: s.bridge.bridgeSeen, positionBridgeSeen: s.bridge.positionBridgeSeen, claimBridge: s.bridge.claimBridge });
  return relay;
}
module.exports = { boot };
