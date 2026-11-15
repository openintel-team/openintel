const { lower, validName } = require("./common");
const WARTABLE_MAX_STROKES = 400;
const validDimension = dim => typeof dim === "string" && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(dim) && dim.length <= 128;
const validCoordinates = e => [e.x, e.y, e.z].every(v => Number.isFinite(v) && Math.abs(v) <= 30000000);
const pingTtl = ttl => Math.min(Number.isFinite(ttl) ? Math.max(0, ttl) : 60000, 300000);
function validStroke(s) {
  return Boolean(s && typeof s === "object" && typeof s.id === "string" && /^[A-Za-z0-9_-]{4,64}$/.test(s.id)
    && ["pen", "line", "arrow", "area", "label", "marker"].includes(lower(s.tool ?? ""))
    && Array.isArray(s.points) && s.points.length >= 2 && s.points.length <= 1024 && s.points.length % 2 === 0
    && s.points.every(v => Number.isFinite(v) && Math.abs(v) <= 1e6)
    && Number.isInteger(s.color) && typeof s.width === "number" && s.width >= 1 && s.width <= 64
    && (s.label == null || (typeof s.label === "string" && s.label.length <= 96))
    && (lower(s.tool) !== "label" || (s.label != null && s.label !== "")));
}
function snitchDimension(value) {
  if (typeof value !== "string" || !value.length || value.length > 128 || /[\u0000-\u001f\u007f]/.test(value)) return null;
  const aliases = { world: "minecraft:overworld", overworld: "minecraft:overworld", world_nether: "minecraft:the_nether",
    nether: "minecraft:the_nether", the_nether: "minecraft:the_nether", world_the_end: "minecraft:the_end",
    the_end: "minecraft:the_end", end: "minecraft:the_end" };
  return Object.hasOwn(aliases, lower(value)) ? aliases[lower(value)] : value;
}
const TEXT_FIELDS = ["player", "name", "message", "label", "snitch", "action", "eventKind", "kind", "id"];
function intelFields(type, event) {
  if (type === "snitch") {
    const n = ["x", "y", "z"].filter(key => Object.hasOwn(event, key)).length;
    if (n !== 0 && (n !== 3 || !validCoordinates(event))) return null;
    if (!n && (typeof event.message !== "string" || !event.message.trim() || event.message.length > 1500
      || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(event.message))) return null;
    for (const key of ["dim", "world"]) if (event[key] != null && snitchDimension(event[key]) == null) return null;
  } else if (!validCoordinates(event) || !validDimension(event.dim ?? event.world)) return null;
  const name = v => typeof v === "string" && validName(v);
  if ((type === "position" && !name(event.name)) || (type === "where" && !name(event.player))
    || (event.player != null && !name(event.player)) || (event.name != null && !name(event.name))) return null;
  for (const key of TEXT_FIELDS) if (event[key] != null && (typeof event[key] !== "string" || event[key].length > 1500)) return null;
  const clean = {};
  if (type === "snitch") {
    if (validCoordinates(event)) Object.assign(clean, { x: event.x, y: event.y, z: event.z });
    for (const key of ["dim", "world"]) if (event[key] != null) clean[key] = snitchDimension(event[key]);
    const dim = clean.dim ?? clean.world;
    if (validDimension(dim) && dim !== "openintel:unknown") clean.dim = dim;
  } else Object.assign(clean, { x: event.x, y: event.y, z: event.z, dim: event.dim ?? event.world });
  for (const key of TEXT_FIELDS) if (event[key] != null) clean[key] = event[key];
  if (type === "ping") { clean.ttl = pingTtl(event.ttl); if (Number.isInteger(event.color)) clean.color = event.color; }
  return clean;
}
module.exports = { validDimension, validCoordinates, validStroke, snitchDimension, intelFields, pingTtl, WARTABLE_MAX_STROKES };
