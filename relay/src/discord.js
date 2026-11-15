const crypto = require("node:crypto");
const { lower, hasTier, validRole, validName, hashToken, hashFingerprint, pageText, fmtAge } = require("./common");
const { visiblePositions, canReceiveIntel } = require("../visibility");
const { parseDiscordSnitch, forwardDiscordSnitch } = require("./discord-snitch");
const fence = s => "```\n" + String(s).slice(0, 1900) + "\n```";
const ids = (many, one) => new Set([...(Array.isArray(many) ? many : []), ...(one ? [one] : [])].map(String).filter(Boolean));
function createDiscord(services, { logger = console, factory } = {}) {
  const { config, users, sessions, allegiances, positions, broadcast, audit, clients } = services;
  const dc = config.discord ?? {};
  const terminal = ids(dc.terminalChannelIds, dc.terminalChannelId), snitch = ids(dc.snitchChannelIds, dc.snitchChannelId);
  const operator = ids(dc.operatorRoleIds, dc.operatorRoleId), captain = ids(dc.captainRoleIds, dc.captainRoleId), admins = ids(dc.adminRoleIds, dc.adminRoleId);
  const lastCmd = new Map(), lastWhere = new Map();
  let bot, status = dc.botToken ? "idle" : "disabled", stopped = false;
  const sourceOf = v => ({ guild: v.guildId ?? null, channel: v.channelId ?? null });
  const actorOf = v => `${v.member?.displayName ?? v.user?.username ?? v.author?.username ?? "unknown"} (${v.user?.id ?? v.author?.id ?? "unknown"})`;
  function start() {
    if (!dc.botToken || bot) return;
    const sdk = require("discord.js");
    const { ActionRowBuilder, ButtonBuilder, ButtonStyle, EmbedBuilder, PermissionsBitField } = sdk;
    bot = factory ? factory() : new sdk.Client({ intents: [sdk.GatewayIntentBits.Guilds, sdk.GatewayIntentBits.GuildMessages, sdk.GatewayIntentBits.MessageContent] });
    status = "connecting";
    const tierOf = member => {
      if (!member) return "member";
      if (member.permissions.has(PermissionsBitField.Flags.Administrator) || [...admins].some(id => member.roles.cache.has(id))) return "admin";
      if ([...captain].some(id => member.roles.cache.has(id))) return "captain";
      if ([...operator].some(id => member.roles.cache.has(id))) return "operator";
      return "member";
    };
    const onlineRows = () => [...clients()].filter(c => c.authedAs).map(c => `${c.authedAs} (${c.role}${users.userByName(c.authedAs)?.quarantined ? ", quarantined" : ""})`);
    const listRows = kind => {
      if (kind === "users") return users.data.users.map(u => `${u.name} (${u.role ?? "member"}${u.disabled ? ", disabled" : ""}${u.quarantined ? ", quarantined" : ""})`);
      if (["allies", "enemies", "focus"].includes(kind)) return (allegiances.data[kind] ?? []).map(String);
      if (kind === "online") return onlineRows();
      return [...users.data.users.map(u => `user: ${u.name} (${u.role ?? "member"}${u.disabled ? ", disabled" : ""}${u.quarantined ? ", quarantined" : ""})`),
        ...["allies", "enemies", "focus"].flatMap(k => (allegiances.data[k] ?? []).map(n => `${{ allies: "ally", enemies: "enemy", focus: "focus" }[k]}: ${n}`))];
    };
    const renderList = (kind = "all", page = 1) => pageText(kind, listRows(kind), page);
    const commandAudit = (msg, tier, action, target, details = {}) => audit.audit({ actor: actorOf(msg), tier, action, target, source: sourceOf(msg), ...details });
    const requireTier = async (msg, tier, required, action, target = null) => {
      if (hasTier(tier, required)) return true;
      commandAudit(msg, tier, action, target, { success: false, reason: `requires ${required}` });
      await msg.reply(`⛔ requires the ${required} role`); return false;
    };
    const panelRows = () => new ActionRowBuilder().addComponents(
      ...[["status", "Refresh Status"], ["online", "Online"], ["lists", "Lists"], ["audit", "Recent Activity"]]
        .map(([id, label]) => new ButtonBuilder().setCustomId(`oi:${id}`).setLabel(label).setStyle(id === "status" ? ButtonStyle.Primary : ButtonStyle.Secondary)));
    const panelEmbed = () => new EmbedBuilder().setTitle("OpenIntel Relay").setDescription("Relay administration and live status")
      .addFields({ name: "Online", value: String(onlineRows().length), inline: true }, { name: "Users", value: String(users.data.users.length), inline: true },
        { name: "Focus", value: String((allegiances.data.focus ?? []).length), inline: true }, { name: "Minecraft server", value: config.minecraftServer, inline: false }).setTimestamp();
    async function historyWhere(name) {
      let best = null;
      for (const channelId of snitch) {
        try {
          const channel = bot.channels.cache.get(channelId) ?? await bot.channels.fetch(channelId).catch(() => null);
          if (!channel?.isTextBased()) continue;
          const messages = await channel.messages.fetch({ limit: 100 }).catch(() => null);
          if (!messages) continue;
          for (const m of messages.values()) {
            const hit = parseDiscordSnitch(m.content ?? "");
            if (!hit || lower(hit.player) !== lower(name)) continue;
            if (!best || m.createdTimestamp > best.t) best = { name: hit.player, x: hit.x, y: hit.y, z: hit.z,
              dim: hit.world ?? "minecraft:overworld", t: m.createdTimestamp, kind: "snitch", reporter: "discord" };
          }
        } catch { /* channel inaccessible */ }
      }
      return best;
    }
    bot.on("messageCreate", async msg => {
      try {
        if (stopped) return;
        if (snitch.has(msg.channelId)) { forwardDiscordSnitch(msg.content, services); return; }
        if (msg.author.bot || !msg.guild || (terminal.size && !terminal.has(msg.channelId)) || !msg.content.startsWith("!")) return;
        const parts = msg.content.slice(1).trim().split(/\s+/), cmd = lower(parts[0] ?? "");
        const actor = actorOf(msg), tier = tierOf(msg.member), context = { tier, source: sourceOf(msg) }, channel = msg.channelId ?? "dm";
        if (cmd !== "broadcastpos") lastCmd.set(channel, cmd);
        if (cmd === "help") return void msg.reply(fence([
          "!online [page]", "!list [users|allies|enemies|focus|online|all] [page]", "!where <player>",
          "!broadcastpos [x y z | !where line]", "!gentoken (DM)", "!broadcast <message> (operator)",
          "!focus <player>|clear / !unfocus (operator)", "!panel (operator)", "!ally|enemy add|remove <player> (captain)",
          "!kick <user> (captain)", "!quarantine|unquarantine <user> (captain)",
          "!user add <name> [role] (admin)", "!user remove|disable|enable|rotate-token|info|token <name> (admin)", "!user role <name> <role> (admin)",
        ].join("\n")));
        if (cmd === "online") return void msg.reply(fence(renderList("online", parts[1])));
        if (cmd === "list") {
          const known = ["users", "allies", "enemies", "focus", "online", "all"].includes(lower(parts[1] ?? "all"));
          return void msg.reply(fence(renderList(known ? lower(parts[1] ?? "all") : "all", known ? parts[2] : parts[1])));
        }
        if (cmd === "where") {
          const name = parts[1]; if (!name) return void msg.reply("usage: `!where <player>`");
          lastWhere.delete(channel);
          let hit = positions.lastSeen.get(lower(name));
          if (!hit) {
            const p = visiblePositions(positions.positions.values(), { role: tier }, users.userByName).find(r => lower(r.name) === lower(name));
            if (p) hit = { ...p, kind: "position" };
          }
          if (hit && !canReceiveIntel({ role: tier }, hit.reporter, hit.name, users.userByName)) hit = null;
          if (!hit) {
            hit = await historyWhere(name);
            if (stopped) return;
            if (hit && !canReceiveIntel({ role: tier }, hit.reporter, hit.name, users.userByName)) hit = null;
            if (hit) positions.lastSeen.set(lower(name), hit);
          }
          if (!hit) return void msg.reply(fence(`${name}: no recent report`));
          lastWhere.set(channel, hit);
          broadcast({ type: "where", player: hit.name, x: hit.x, y: hit.y, z: hit.z, dim: hit.dim, t: hit.t, kind: hit.kind, from: hit.reporter });
          return void msg.reply(fence(`${hit.name}: ${Math.round(hit.x)}, ${Math.round(hit.y)}, ${Math.round(hit.z)} (${String(hit.dim).replace("minecraft:", "")}) — ${hit.kind === "snitch" ? "snitch hit" : "position"} ${fmtAge(Date.now() - hit.t)} ago, reported by ${hit.reporter}`));
        }
        if (cmd === "gentoken") {
          try { await msg.author.send("Random token:\n`" + crypto.createHash("sha256").update(crypto.randomBytes(32)).digest("hex") + "`"); await msg.reply("token sent to your DMs"); }
          catch { await msg.reply("could not DM you the token. Enable DMs from this server and retry."); } return;
        }
        if (cmd === "broadcast") {
          if (!await requireTier(msg, tier, "operator", "broadcast")) return;
          const text = parts.slice(1).join(" ").trim();
          if (!text || text.length > 1500) {
            commandAudit(msg, tier, "broadcast", null, { success: false, reason: "message must be 1-1500 characters" });
            return void msg.reply("usage: `!broadcast <message>` (maximum 1500 characters)");
          }
          broadcast({ type: "notice", msg: `[Broadcast] ${text}`, from: actor, t: Date.now() });
          commandAudit(msg, tier, "broadcast", null, { after: { message: text } }); return void msg.reply("broadcast sent");
        }
        if (cmd === "broadcastpos") {
          if (!await requireTier(msg, tier, "operator", "broadcastpos")) return;
          const rest = parts.slice(1).join(" ").trim();
          let player = null, x, y, z, dim = "minecraft:overworld";
          if (!rest) {
            const hit = lastCmd.get(channel) === "where" ? lastWhere.get(channel) : null;
            if (!hit) return void msg.reply("usage: `!broadcastpos <x> <y> <z>` or run after `!where`");
            ({ name: player, x, y, z, dim } = hit);
          } else {
            const paste = rest.match(/^(?<name>[A-Za-z0-9_]{3,16})\s*:\s*(?<x>-?\d+(?:\.\d+)?)\s*,\s*(?<y>-?\d+(?:\.\d+)?)\s*,\s*(?<z>-?\d+(?:\.\d+)?)\s*(?:\((?<dim>[^)]+)\))?/);
            const direct = rest.match(/^(?<x>-?\d+(?:\.\d+)?)[,\s]+(?<y>-?\d+(?:\.\d+)?)[,\s]+(?<z>-?\d+(?:\.\d+)?)(?:\s*\(?\s*(?<dim>[a-z_]+:[a-z_]+|[a-z_]+)\)?)?/i);
            const m = paste ?? direct;
            if (!m) return void msg.reply("usage: `!broadcastpos <x> <y> <z>` or paste a `!where` result line");
            x = +m.groups.x; y = +m.groups.y; z = +m.groups.z;
            if (m.groups.dim) { dim = lower(m.groups.dim.trim()); if (!dim.includes(":")) dim = `minecraft:${dim}`; }
            player = paste ? m.groups.name : null;
          }
          const label = player ? `coord broadcast | ${player}` : "coord broadcast";
          broadcast({ type: "ping", id: `bc-${Date.now().toString(36)}-${crypto.randomBytes(3).toString("hex")}`,
            label, x, y, z, dim, color: 0xFFFFAA33, from: "discord", t: Date.now(), ttl: 60000 });
          commandAudit(msg, tier, "broadcastpos", player ?? `${x},${y},${z}`, { after: { x, y, z, dim } });
          return void msg.reply(fence(`${label} @ ${Math.round(x)}, ${Math.round(y)}, ${Math.round(z)} (${dim.replace("minecraft:", "")}) — on war tables for 60s`));
        }
        if (cmd === "panel") {
          if (!await requireTier(msg, tier, "operator", "panel.open")) return;
          commandAudit(msg, tier, "panel.open", null); return void msg.reply({ embeds: [panelEmbed()], components: [panelRows()] });
        }
        if (cmd === "focus" || cmd === "unfocus") {
          if (!await requireTier(msg, tier, "operator", `focus.${cmd === "unfocus" ? "remove" : "add"}`, parts[1])) return;
          const arg = parts[1]; if (!arg) return void msg.reply("usage: `!focus <player>|clear` or `!unfocus <player>`");
          const action = cmd === "unfocus" ? "remove" : lower(arg) === "clear" ? "clear" : "add";
          const what = allegiances.focus(action, action === "clear" ? null : arg, actor, context);
          return void msg.reply(what ? fence(what.replace(/\*\*/g, "")) : "invalid Minecraft player name");
        }
        if (cmd === "ally" || cmd === "enemy") {
          if (!await requireTier(msg, tier, "captain", `${cmd}.${parts[1]}`, parts[2])) return;
          const action = lower(parts[1] ?? "");
          if (!["add", "remove"].includes(action) || !parts[2]) return void msg.reply(`usage: \`!${cmd} add|remove <player>\``);
          return void msg.reply(fence(allegiances.change(cmd === "ally" ? "allies" : "enemies", action, parts[2], actor, context)));
        }
        if (cmd === "kick" || cmd === "quarantine" || cmd === "unquarantine") {
          const name = parts[1], action = cmd === "kick" ? "session.kick" : `user.${cmd}`;
          if (!await requireTier(msg, tier, "captain", action, name)) return;
          const user = name ? users.userByName(name) : null;
          if (!user) { commandAudit(msg, tier, action, name, { success: false, reason: "user not found" }); return void msg.reply("user not found"); }
          if (cmd === "kick") {
            const count = sessions.revoke(user.name, "kicked by relay captain");
            commandAudit(msg, tier, action, name, { before: { sessions: count }, after: { sessions: 0 } });
            return void msg.reply(`closed ${count} active session(s) for ${user.name}`);
          }
          users.update(name, { quarantined: cmd === "quarantine" });
          if (cmd === "quarantine") positions.removeReported(name);
          commandAudit(msg, tier, action, name, { before: { quarantined: Boolean(user.quarantined) }, after: { quarantined: cmd === "quarantine" } });
          return void msg.reply(`${user.name} ${cmd === "quarantine" ? "quarantined; submissions are blocked" : "unquarantined"}`);
        }
        if (cmd !== "user") return;
        const action = lower(parts[1] ?? ""), name = parts[2];
        if (!await requireTier(msg, tier, "admin", `user.${action || "unknown"}`, name)) return;
        if (!["add", "remove", "disable", "enable", "role", "rotate-token", "info", "token"].includes(action))
          return void msg.reply("usage: `!user add|remove|disable|enable|role|rotate-token|token|info ...`");
        if (!validName(name)) {
          commandAudit(msg, tier, `user.${action}`, name, { success: false, reason: "invalid Minecraft name" });
          return void msg.reply("invalid Minecraft name; expected 3-16 letters, numbers, or underscores");
        }
        const user = users.userByName(name);
        if (action === "add") {
          const role = lower(parts[3] ?? "member");
          if (!validRole(role) || user) {
            const reason = user ? "user already exists" : "invalid role";
            commandAudit(msg, tier, "user.add", name, { success: false, reason });
            return void msg.reply(reason);
          }
          const token = crypto.randomBytes(32).toString("base64url");
          try { await msg.author.send(`OpenIntel token for **${name}** (${role}):\n\`${token}\`\nStore it securely; it will not be shown in the command channel.`); }
          catch {
            commandAudit(msg, tier, "user.add", name, { success: false, reason: "DM delivery failed; user not created" });
            return void msg.reply("user was not created because DM delivery failed. Enable DMs and retry.");
          }
          if (stopped) return;
          const added = users.add(name, role, token); broadcast(allegiances.payload());
          commandAudit(msg, tier, "user.add", name, { after: added }); return void msg.reply(`user ${name} added; the token was sent to you by DM`);
        }
        if (!user) { commandAudit(msg, tier, `user.${action}`, name, { success: false, reason: "user not found" }); return void msg.reply("user not found"); }
        const before = { ...user };
        if (action === "info") {
          commandAudit(msg, tier, "user.info", name);
          return void msg.reply(fence(`name: ${user.name}\nrole: ${user.role ?? "member"}\ndisabled: ${Boolean(user.disabled)}\nquarantined: ${Boolean(user.quarantined)}\ntoken fingerprint: sha256:${hashFingerprint(user.tokenHash) ?? "none"}\nactive sessions: ${sessions.socketsFor(name).length}`));
        }
        if (action === "token") {
          commandAudit(msg, tier, "user.token", name, { success: false, reason: "tokens are stored hashed" });
          return void msg.reply(`tokens are stored only as hashes and cannot be resent. Use \`!user rotate-token ${name}\` to DM a new one.`);
        }
        if (action === "role" && !validRole(parts[3])) {
          commandAudit(msg, tier, "user.role", name, { before, after: before, success: false, reason: "invalid role" });
          return void msg.reply("invalid role");
        }
        let token;
        if (action === "rotate-token") {
          token = crypto.randomBytes(32).toString("base64url");
          try { await msg.author.send(`New OpenIntel token for **${name}**:\n\`${token}\`\nStore it securely; the prior token will be revoked now.`); }
          catch {
            commandAudit(msg, tier, `user.${action}`, name, { before, after: before, success: false, reason: "DM delivery failed; token unchanged" });
            return void msg.reply("token was not rotated because DM delivery failed. Enable DMs and retry.");
          }
        }
        if (stopped) return;
        if (action === "remove") users.remove(name);
        else users.update(name, action === "rotate-token" ? { tokenHash: hashToken(token) }
          : action === "role" ? { role: lower(parts[3]) } : { disabled: action === "disable" });
        broadcast(allegiances.payload());
        const revoked = ["remove", "disable", "rotate-token"].includes(action) ? sessions.revoke(name, `${action} by relay admin`) : 0;
        commandAudit(msg, tier, `user.${action}`, name, { before, after: action === "remove" ? null : users.userByName(name), reason: revoked ? `${revoked} session(s) revoked` : null });
        return void msg.reply(token ? `token rotated for ${name}; the new token was sent to you by DM` : `${name}: ${action} complete${revoked ? `; ${revoked} session(s) revoked` : ""}`);
      } catch { logger.error("Discord command failed"); }
    });
    bot.on("interactionCreate", async interaction => {
      if (stopped || !interaction.isButton() || !interaction.customId.startsWith("oi:") || !interaction.guild) return;
      try {
        if (terminal.size && !terminal.has(interaction.channelId)) return void interaction.reply({ content: "This panel is not active in this channel.", ephemeral: true });
        const tier = tierOf(interaction.member), action = `panel.${interaction.customId.slice(3)}`;
        if (!hasTier(tier, "operator")) {
          audit.audit({ actor: actorOf(interaction), tier, action, source: sourceOf(interaction), success: false, reason: "requires operator" });
          return void interaction.reply({ content: "Requires the operator role.", ephemeral: true });
        }
        audit.audit({ actor: actorOf(interaction), tier, action, source: sourceOf(interaction) });
        if (interaction.customId === "oi:status") return void interaction.update({ embeds: [panelEmbed()], components: [panelRows()] });
        if (interaction.customId === "oi:online" || interaction.customId === "oi:lists")
          return void interaction.reply({ content: fence(renderList(interaction.customId === "oi:online" ? "online" : "all", 1)), ephemeral: true });
        return void interaction.reply({ content: fence(pageText("recent activity", audit.recent.slice(0, 8)
          .map(e => `${e.timestamp} ${e.actor}: ${e.action}${e.target ? ` ${e.target}` : ""} [${e.success ? "ok" : "failed"}]`))), ephemeral: true });
      } catch { logger.error("Discord interaction failed"); }
    });
    bot.once("clientReady", () => { if (!stopped) { status = "ready"; logger.log("Discord bridge ready"); } });
    bot.on("error", () => { status = "degraded"; logger.error("Discord connection failed"); });
    bot.login(dc.botToken).catch(() => { if (!stopped) { status = "degraded"; logger.error("Discord login failed"); } });
  }
  return { start, stop() { stopped = true; bot?.destroy?.(); status = "stopped"; }, get status() { return status; } };
}
module.exports = { createDiscord };
