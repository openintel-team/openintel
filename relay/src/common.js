const crypto = require("node:crypto");
const USER_ROLES = ["trial", "member", "operator", "captain", "admin"];
const lower = value => String(value).toLowerCase();
const validName = name => /^[A-Za-z0-9_]{3,16}$/.test(String(name ?? ""));
const validRole = role => USER_ROLES.includes(lower(role));
const hasTier = (role, tier) => Math.max(0, USER_ROLES.indexOf(lower(role ?? "member"))) >= USER_ROLES.indexOf(tier);
function normalizeMinecraftServer(value) {
  let name = lower(value ?? "").trim().replace(/^[a-z]+:\/\//, "").split("/")[0];
  while (name.endsWith(".")) name = name.slice(0, -1);
  return name.endsWith(":25565") ? name.slice(0, -6) : name;
}
const hashToken = token => crypto.createHash("sha256").update(String(token)).digest("hex");
const hashFingerprint = hash => typeof hash === "string" && /^[a-f0-9]{64}$/i.test(hash) ? lower(hash).slice(0, 12) : null;
const tokenFingerprint = token => token ? hashToken(token).slice(0, 12) : null;
function sealUsers(data) {
  let changed = false;
  const users = (Array.isArray(data?.users) ? data.users : []).filter(user => {
    const keep = user && typeof user === "object" && !Array.isArray(user);
    if (!keep) changed = true;
    return keep;
  }).map(user => {
    if (!Object.hasOwn(user, "token")) return user;
    changed = true;
    const plain = typeof user.token === "string" && user.token.length ? user.token : null;
    const sealed = {};
    for (const [key, value] of Object.entries(user)) {
      if (key === "tokenHash" && plain) continue;
      if (key === "token") { if (plain) sealed.tokenHash = hashToken(plain); continue; }
      sealed[key] = value;
    }
    return sealed;
  });
  return { data: { ...(data && typeof data === "object" ? data : {}), users }, changed };
}
function safeAuditValue(value, key = "") {
  if (value == null || typeof value !== "object") {
    if (key === "tokenHash" && value != null) return `sha256:${hashFingerprint(value)}`;
    return /token|secret/i.test(key) && value != null ? `sha256:${tokenFingerprint(value)}` : value;
  }
  if (Array.isArray(value)) return value.map(item => safeAuditValue(item));
  return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, safeAuditValue(v, k)]));
}
function adminTokenDigest(token) {
  if (typeof token !== "string" || token.length < 32 || ["CHANGE_ME", "CHANGE_ME_LONG_RANDOM_STRING"].includes(token)) return null;
  return crypto.createHash("sha256").update(token).digest();
}
const adminTokenMatches = (digest, token) => Boolean(digest && typeof token === "string" && token
  && crypto.timingSafeEqual(digest, crypto.createHash("sha256").update(token).digest()));
function consumeRateLimit(entries, key, now, cooldown) {
  const prev = entries.get(key);
  if (prev && now - prev.last < cooldown) { prev.suppressed++; return { allowed: false, suppressed: prev.suppressed }; }
  const suppressed = prev?.suppressed ?? 0;
  entries.set(key, { last: now, suppressed: 0 });
  return { allowed: true, suppressed };
}
function pageText(label, entries, requestedPage = 1, pageSize = 20) {
  const pages = Math.max(1, Math.ceil(entries.length / pageSize));
  const page = Math.min(pages, Math.max(1, Number.parseInt(requestedPage, 10) || 1));
  const rows = entries.slice((page - 1) * pageSize, page * pageSize);
  return `${label} — page ${page}/${pages} (${entries.length})\n${rows.length ? rows.join("\n") : "(none)"}`;
}
function fmtAge(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(s / 86400)}:${String(Math.floor(s / 3600) % 24).padStart(2, "0")}:${String(Math.floor(s / 60) % 60).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}
module.exports = { lower, validName, validRole, hasTier, normalizeMinecraftServer, hashToken, hashFingerprint,
  tokenFingerprint, sealUsers, safeAuditValue, adminTokenDigest, adminTokenMatches, consumeRateLimit, pageText, fmtAge };
