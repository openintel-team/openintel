const EVENT = /^\s*(?<snitch>.+?):\s*(?<player>\w{3,16})\s+(?<action>entered snitch|logged (?:in|out))\s+at\s*\((?<x>-?\d+)[,\s]+(?<y>-?\d+)[,\s]+(?<z>-?\d+)\s*\)/i;
const INTERACTION = /^\s*(?<snitch>.+?):\s*(?<player>\w{3,16})\s+(?<action>(?!entered snitch\b|logged (?:in|out)\b).{2,96}?)\s+at\s*\((?<x>-?\d+)[,\s]+(?<y>-?\d+)[,\s]+(?<z>-?\d+)\s*\)/i;
const INGAME = /(?<player>\w{3,16})\s+(?<action>entered snitch)\s+at\s+(?<snitch>\S+)[^\[(]*\[\s*(?<world>\S+)\s+(?<x>-?\d+)\s+(?<y>-?\d+)\s+(?<z>-?\d+)\s*\]/i;
function parseDiscordSnitch(text) {
  let m = text.match(EVENT) ?? text.match(INGAME), eventKind = "event";
  if (!m) { m = text.match(INTERACTION); eventKind = "interaction"; }
  if (!m) return null;
  const { player, x, y, z, action } = m.groups, snitch = m.groups.snitch.replace(/^[+\s*]+|[+\s*]+$/g, "");
  return player && snitch ? { player, snitch, world: m.groups.world ?? null, x: +x, y: +y, z: +z, action: action.trim(), eventKind } : null;
}
function forwardDiscordSnitch(text, { broadcast, audit, positions }) {
  const hit = parseDiscordSnitch(text); if (!hit) return;
  const msg = { type: "snitch", message: text, reporter: "discord", player: hit.player, snitch: hit.snitch,
    action: hit.action, eventKind: hit.eventKind, x: hit.x, y: hit.y, z: hit.z, from: "discord", t: Date.now() };
  if (hit.world) msg.world = hit.world;
  if (broadcast(msg)) audit.adminLog(`📡 Snitch hit via Discord relay: ${hit.snitch ?? "?"} by ${hit.player ?? "?"}`);
  positions.noteSeen(hit.player, hit.x, hit.y, hit.z, msg.world, "discord", "snitch");
}
module.exports = { parseDiscordSnitch, forwardDiscordSnitch };
