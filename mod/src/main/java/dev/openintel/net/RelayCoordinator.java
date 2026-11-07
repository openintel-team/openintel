package dev.openintel.net;

import com.google.gson.JsonObject;
import dev.openintel.OpenIntelClient;
import dev.openintel.api.Snapshots.ConnectionState;
import dev.openintel.api.internal.ApiBridge;
import dev.openintel.config.RelayProfile;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Owns independent sessions; the inherited single-relay interface remains the API compatibility facade. */
public final class RelayCoordinator extends RelayClient {
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Consumer<String> status;
    private volatile long generation;
    private boolean updating;

    private final class Session {
        final RelayProfile profile;
        final RelayClient client;
        boolean bridge;
        boolean hadIntel;
        /** The relay pushes compact position deltas and takes 20 Hz uploads. */
        boolean positionDeltas;
        final PositionUplink uplink = new PositionUplink();
        final java.util.LinkedHashMap<String, Boolean> forwarded = new java.util.LinkedHashMap<>();
        Session(RelayProfile profile) {
            this.profile = profile;
            client = new RelayClient(message -> receive(this, message),
                    text -> status.accept(profile.name + ": " + text),
                    () -> matches(profile), () -> profile.enabled && profile.share,
                    () -> changed(this), false, secure -> rememberSecure(this.profile, secure));
        }
    }

    public RelayCoordinator(Consumer<String> status) {
        super(message -> {}, status);
        this.status = status;
    }

    private void rememberSecure(RelayProfile sessionProfile, String secureUrl) {
        String previous = sessionProfile.url;
        if (secureUrl.equals(previous)) return;
        sessionProfile.url = secureUrl;
        var config = OpenIntelClient.config();
        if (config == null) return;
        if (previous.equals(config.relayUrl)) config.relayUrl = secureUrl;
        for (RelayProfile stored : config.relayProfiles()) {
            if (sessionProfile.id.equals(stored.id)) stored.url = secureUrl;
        }
        config.save();
    }

    private static boolean matches(RelayProfile profile) {
        if (!profile.enabled) return false;
        String actual = RelayClient.connectableServer(Minecraft.getInstance());
        return actual != null && OpenIntelClient.normalizeMinecraftServer(profile.minecraftServer).equals(actual);
    }

    public boolean reconnect() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            long expected = generation;
            var world = minecraft.level;
            minecraft.execute(() -> { if (generation == expected && minecraft.level == world) reconnect(); });
            return false;
        }
        if (updating) return false;
        updating = true;
        generation++;
        String actual = RelayClient.connectableServer(minecraft);
        var profiles = OpenIntelClient.config().relayProfiles();
        // Cached annotations for removed/disabled profiles must not remain visible.
        for (RelayProfile profile : profiles) {
            if (!matches(profile)) OpenIntelClient.tracker().clearRelay(profile.id);
        }
        // Preserve unaffected sessions when changing just one profile.
        for (var iterator = sessions.entrySet().iterator(); iterator.hasNext();) {
            Session old = iterator.next().getValue();
            RelayProfile next = profiles.stream().filter(p -> p.id.equals(old.profile.id)).findFirst().orElse(null);
            if (next == null || !matches(next) || !sameConnection(old.profile, next)) {
                old.client.disconnect();
                clear(old);
                iterator.remove();
            } else {
                old.profile.share = next.share;
                old.profile.name = next.name;
                if (old.client.state() == ConnectionState.DENIED || old.client.state() == ConnectionState.DISCONNECTED)
                    old.client.connect(next.url, next.token, actual);
            }
        }
        if (actual != null) {
            for (RelayProfile profile : profiles) {
                if (!matches(profile) || profile.url.isBlank() || sessions.containsKey(profile.id)) continue;
                Session session = new Session(copy(profile));
                sessions.put(profile.id, session);
                session.client.connect(profile.url, profile.token, actual);
            }
        }
        updating = false;
        publish();
        return !sessions.isEmpty();
    }

    private static RelayProfile copy(RelayProfile p) {
        RelayProfile result = new RelayProfile();
        result.id = p.id; result.name = p.name; result.url = p.url; result.token = p.token;
        result.minecraftServer = p.minecraftServer; result.enabled = p.enabled; result.share = p.share;
        return result;
    }

    private static boolean sameConnection(RelayProfile a, RelayProfile b) {
        return a.url.equals(b.url) && a.token.equals(b.token)
                && OpenIntelClient.normalizeMinecraftServer(a.minecraftServer)
                .equals(OpenIntelClient.normalizeMinecraftServer(b.minecraftServer));
    }

    private void changed(Session session) {
        if (!session.client.isAuthenticated()) {
            session.bridge = false;
            if (session.hadIntel) clear(session);
        }
        generation++;
        if (!updating) publish();
    }

    private void clear(Session session) {
        session.hadIntel = false;
        OpenIntelClient.tracker().clearRelay(session.profile.id);
    }

    private void receive(Session source, JsonObject message) {
        if (sessions.get(source.profile.id) != source) return;
        String type = message.get("type").getAsString();
        if (!type.equals("deny")) source.hadIntel = true;
        if (type.equals("intel_reset")) source.forwarded.clear();
        if (type.equals("welcome")) {
            source.bridge = message.has("bridgeVersion") && message.get("bridgeVersion").getAsInt() == 1;
            source.positionDeltas = message.has("positionVersion") && message.get("positionVersion").isJsonPrimitive()
                    && message.get("positionVersion").getAsInt() >= PositionUplink.POSITION_PROTOCOL;
            source.uplink.reset();
            source.forwarded.clear();
        }
        long expected = generation;
        OpenIntelClient.tracker().handleMessage(source.profile.id, message, Minecraft.getInstance());
        if (generation == expected && sessions.get(source.profile.id) == source) forward(source, message);
    }

    private void forward(Session source, JsonObject message) {
        // Capability-negotiated forwarding is implemented by RelayBridge, never by replaying raw control frames.
        if (!source.bridge) return;
        for (JsonObject event : RelayBridge.events(message)) {
            for (Session destination : List.copyOf(sessions.values())) {
                if (destination == source || !destination.bridge || !destination.profile.share) continue;
                JsonObject forwarded = RelayBridge.forward(event, destination.client.role());
                if (forwarded == null) continue;
                String id = event.getAsJsonObject("bridge").get("id").getAsString();
                if (destination.forwarded.containsKey(id)) continue;
                if (destination.client.trySend(forwarded)) {
                    destination.forwarded.put(id, true);
                    if (destination.forwarded.size() > 8192)
                        destination.forwarded.remove(destination.forwarded.keySet().iterator().next());
                }
            }
        }
    }

    @Override public void connect(String url, String token, String minecraftServer) { reconnect(); }
    @Override public void disconnect() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            long expected = generation;
            minecraft.execute(() -> { if (generation == expected) disconnect(); });
            return;
        }
        if (updating) return;
        updating = true;
        generation++;
        for (Session session : List.copyOf(sessions.values())) { session.client.disconnect(); clear(session); }
        sessions.clear();
        updating = false;
        publish();
    }
    @Override public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) { minecraft.execute(this::tick); return; }
        for (Session session : List.copyOf(sessions.values())) session.client.tick();
    }
    @Override public boolean isConnected() { return sessions.values().stream().anyMatch(s -> s.client.isConnected()); }
    @Override public boolean isAuthenticated() { return sessions.values().stream().anyMatch(s -> s.client.isAuthenticated()); }
    public boolean canShare() { return sessions.values().stream().anyMatch(s -> s.profile.share && s.client.isAuthenticated()); }
    @Override public long sessionGeneration() { return generation; }
    @Override public ConnectionState state() {
        if (isAuthenticated()) return ConnectionState.AUTHENTICATED;
        if (sessions.values().stream().anyMatch(s -> s.client.state() == ConnectionState.AUTHENTICATING)) return ConnectionState.AUTHENTICATING;
        if (sessions.values().stream().anyMatch(s -> s.client.state() == ConnectionState.CONNECTING)) return ConnectionState.CONNECTING;
        if (sessions.values().stream().anyMatch(s -> s.client.state() == ConnectionState.DENIED)) return ConnectionState.DENIED;
        return ConnectionState.DISCONNECTED;
    }
    @Override public String role() {
        return sessions.values().stream().filter(s -> s.profile.share && s.client.isAuthenticated())
                .map(s -> s.client.role()).filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.comparingInt(RelayCoordinator::rank)).orElse(null);
    }
    private static int rank(String role) {
        return switch (role.toLowerCase(java.util.Locale.ROOT)) { case "admin" -> 4; case "captain" -> 3; case "operator" -> 2; case "member" -> 1; default -> 0; };
    }
    @Override public void send(JsonObject message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            long expected = generation;
            JsonObject copy = message.deepCopy();
            minecraft.execute(() -> { if (generation == expected) send(copy); });
            return;
        }
        long expected = generation;
        if (message.has("type") && "snitch".equals(message.get("type").getAsString())) ApiBridge.relaySnitch(message);
        if (generation == expected) trySend(message);
    }
    @Override public boolean trySend(JsonObject message) {
        if (!Minecraft.getInstance().isSameThread() || updating) return false;
        String type = message.has("type") ? message.get("type").getAsString() : "";
        boolean sent = false;
        JsonObject outgoing = message.deepCopy();
        // One identity for the same local observation sent to several relays.
        JsonObject identity = new JsonObject();
        identity.addProperty("id", java.util.UUID.randomUUID().toString());
        identity.addProperty("hops", 0);
        outgoing.add("bridge", identity);
        outgoing.addProperty("t", System.currentTimeMillis());
        if (type.equals("ping")) outgoing.addProperty("ttl", OpenIntelClient.config().pingSeconds * 1000L);
        if (type.equals("positions") && outgoing.has("reports")) {
            for (var report : outgoing.getAsJsonArray("reports")) {
                if (!report.isJsonObject()) continue;
                JsonObject observation = new JsonObject();
                observation.addProperty("id", shortId());
                observation.addProperty("hops", 0);
                report.getAsJsonObject().add("bridge", observation);
                report.getAsJsonObject().addProperty("t", System.currentTimeMillis());
            }
        }
        long now = System.currentTimeMillis();
        for (Session session : List.copyOf(sessions.values())) {
            if (!session.profile.share || !session.client.isAuthenticated()) continue;
            if (!allowsSend(session.profile.enabled, session.profile.share, session.client.isAuthenticated(),
                    session.client.role(), type)) continue;
            JsonObject copy = outgoing.deepCopy();
            if (type.equals("positions") && copy.has("reports") && copy.get("reports").isJsonArray()) {
                // Per relay: changed players at 20 Hz for delta relays, the whole list at 4 Hz for older ones.
                var chosen = session.uplink.select(copy.getAsJsonArray("reports"), session.positionDeltas, now);
                if (chosen == null) continue;
                copy.add("reports", chosen);
            }
            sent |= session.client.trySend(copy);
        }
        return sent;
    }

    /** 72 random bits, URL-safe: enough to tell observations apart, a third of a UUID's size. */
    static String shortId() {
        byte[] bytes = new byte[9];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static boolean allowsSend(boolean enabled, boolean sharing, boolean authenticated, String role, String type) {
        if (!enabled || !sharing || !authenticated) return false;
        int rank = rank(role == null ? "" : role);
        if ((type.equals("wartable") || type.equals("cut")) && rank < 4) return false;
        if (type.equals("focus") && rank < 2) return false;
        return java.util.Set.of("positions", "snitch", "ping", "wartable", "cut", "focus").contains(type);
    }

    public String status(String id) {
        Session session = sessions.get(id);
        return session == null ? "Disconnected" : session.client.state().name().toLowerCase(java.util.Locale.ROOT)
                + (session.client.role() == null ? "" : " (" + session.client.role() + ")")
                + (session.client.isAuthenticated() && !session.bridge ? " — forwarding unavailable" : "");
    }
    private void publish() {
        ApiBridge.connectionChanged(state(), OpenIntelClient.currentMinecraftServer(Minecraft.getInstance()), role());
    }
}
