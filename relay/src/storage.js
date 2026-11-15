const fs = require("fs");
const path = require("path");
function createStore(directory) {
  const filePath = name => {
    if (!/^[a-z][a-z.-]+$/.test(name)) throw new Error("Invalid state filename");
    return path.join(directory, name);
  };
  function load(name, fallback) {
    let text;
    try { text = fs.readFileSync(filePath(name), "utf8"); }
    catch (error) {
      if (error.code === "ENOENT") return structuredClone(fallback);
      throw new Error(`Cannot read ${name}: ${error.code || "read failed"}`);
    }
    let data;
    try { data = JSON.parse(text); } catch { throw new Error(`Invalid JSON in ${name}; existing data was not replaced`); }
    const field = { "users.json": "users", "wartable.json": "strokes", "allegiances.json": "allies" }[name];
    if (!data || typeof data !== "object" || Array.isArray(data) || (field && !Array.isArray(data[field])))
      throw new Error(`Invalid data shape in ${name}`);
    if (name === "allegiances.json" && !Array.isArray(data.enemies)) throw new Error("Invalid enemies in allegiances.json");
    return data;
  }
  function save(name, data) {
    const destination = filePath(name);
    const temporary = `${destination}.${process.pid}.tmp`;
    const mode = name === "users.json" ? 0o600 : 0o640;
    try {
      fs.writeFileSync(temporary, JSON.stringify(data, null, 2), { mode });
      fs.chmodSync(temporary, mode);
      fs.renameSync(temporary, destination);
    } catch (error) {
      try { fs.unlinkSync(temporary); } catch {}
      throw error;
    }
  }
  function lock() {
    const filename = filePath("relay.lock");
    let fd;
    try { fd = fs.openSync(filename, "wx", 0o600); }
    catch (error) {
      if (error.code !== "EEXIST") throw error;
      throw new Error(`State is locked (${filename}). Stop the writer; after a crash verify its PID is dead before removing this lock.`);
    }
    try { fs.writeFileSync(fd, String(process.pid)); } finally { fs.closeSync(fd); }
    let released = false;
    return () => { if (!released) { released = true; fs.unlinkSync(filename); } };
  }
  return { load, save, lock, filePath,
    appendAudit: entry => fs.appendFileSync(filePath("audit.jsonl"), `${JSON.stringify(entry)}\n`, { mode: 0o600 }) };
}
module.exports = { createStore };
