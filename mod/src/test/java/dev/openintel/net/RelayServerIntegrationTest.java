package dev.openintel.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.openintel.OpenIntelClient;
import dev.openintel.allegiance.AllegianceManager;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import dev.openintel.api.Snapshots.ConnectionState;
import dev.openintel.config.OIConfig;
import dev.openintel.config.RelayProfile;
import dev.openintel.ping.PingManager;
import dev.openintel.tracker.Tracker;
import dev.openintel.wartable.WarTable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Real {@code node server.js}, a loopback session stub, and the Java relay client.
 * Tracked relay JSON is left untouched; fixtures live in a temp relay home.
 */
public final class RelayServerIntegrationTest {
    private static final String SERVER = "play.example.test";
    private static final Account MEMBER_A = new Account("MemberA", "member-a-token",
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final Account MEMBER_B = new Account("MemberB", "member-b-token",
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final Account ADMIN = new Account("AdminOne", "admin-one-token",
            UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private static final List<Account> ACCOUNTS = List.of(MEMBER_A, MEMBER_B, ADMIN);
    private static final String USERS = """
            { "users": [
              { "name": "MemberA", "token": "member-a-token", "role": "member" },
              { "name": "MemberB", "token": "member-b-token", "role": "member" },
              { "name": "AdminOne", "token": "admin-one-token", "role": "admin" }
            ] }
            """;
    private static final String ALLEGIANCES = """
            { "allies": ["AllyScout"], "enemies": ["EnemyScout"], "focus": [] }
            """;

    public static void main(String[] args) throws Exception {
        Path relayDir = relayDirectory();
        if (!Files.isDirectory(relayDir.resolve("node_modules"))) {
            throw new AssertionError("relay/node_modules is missing (" + relayDir.resolve("node_modules")
                    + "). Run npm install in relay/ before this test.");
        }
        requireNode();
        String before = fingerprint(relayDir);
        Field instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object previousClient = instance.get(null);
        String previousSessionServer = System.getProperty("openintel.sessionServer");
        HttpServer sessions = null;
        RunningRelay primary = null;
        RunningRelay secondary = null;
        RelayCoordinator coordinator = null;
        RelayClient memberB = null;
        RelayClient admin = null;
        RelayClient bad = null;
        try {
            installClient(instance);
            sessions = startSessions();
            String sessionUrl = "http://127.0.0.1:" + sessions.getAddress().getPort();
            System.setProperty("openintel.sessionServer", sessionUrl);
            primary = startRelay(relayDir, sessionUrl);

            OIConfig config = new OIConfig();
            config.minecraftServer = SERVER;
            config.relayUrl = primary.url();
            config.token = MEMBER_A.token;
            config.relays = new ArrayList<>();
            RelayProfile first = profile("Primary", primary.url(), MEMBER_A.token);
            config.relays.add(first);
            setStatic("config", config);
            setStatic("tracker", new Tracker());
            setStatic("allegiances", new AllegianceManager());
            coordinator = new RelayCoordinator(status -> { });
            setStatic("relay", coordinator);
            final RelayCoordinator memberA = coordinator;
            check(memberA.reconnect(), "Member A connects through RelayCoordinator");
            await("member A welcome roster", () ->
                    OpenIntelClient.allegiances().of("MemberA") == Allegiance.FRIEND
                            && OpenIntelClient.allegiances().of("MemberB") == Allegiance.FRIEND
                            && OpenIntelClient.allegiances().of("AdminOne") == Allegiance.FRIEND
                            && OpenIntelClient.allegiances().of("AllyScout") == Allegiance.ALLY
                            && OpenIntelClient.allegiances().of("EnemyScout") == Allegiance.ENEMY
                            && OpenIntelClient.allegiances().isRelayUser("MemberA")
                            && !OpenIntelClient.allegiances().isRelayUser("EnemyScout")
                            && memberA.state() == ConnectionState.AUTHENTICATED, primary);

            CopyOnWriteArrayList<JsonObject> denied = new CopyOnWriteArrayList<>();
            bad = new RelayClient(denied::add, status -> { });
            bad.connect(primary.url(), "wrong-token", SERVER);
            RelayClient rejected = bad;
            await("bad token ends in deny", () -> rejected.state() == ConnectionState.DENIED
                    && denied.stream().anyMatch(message -> "deny".equals(type(message))
                    && message.has("reason") && "bad_token".equals(message.get("reason").getAsString())), primary);
            check(memberA.state() == ConnectionState.AUTHENTICATED, "A bad token does not drop member A");

            CopyOnWriteArrayList<JsonObject> seen = new CopyOnWriteArrayList<>();
            memberB = new RelayClient(message -> {
                seen.add(message.deepCopy());
                if ("ping".equals(type(message)))
                    OpenIntelClient.tracker().handleMessage("member-b", message, Minecraft.getInstance());
            }, status -> { });
            memberB.identity = MEMBER_B.user();
            memberB.connect(primary.url(), MEMBER_B.token, SERVER);
            RelayClient secondAccount = memberB;
            await("member B authenticates", secondAccount::isAuthenticated, primary);
            check(memberA.trySend(ping("rally-a", "rally", 12, 64, -8)), "Member A sends a ping");
            await("member B receives A's ping", () -> seen.stream().anyMatch(message ->
                    pingFrom(message, "rally-a", "MemberA", -1)), primary);
            check(pingKept("rally-a", "MemberA"), "PingManager keeps the relay ping");

            admin = new RelayClient(message -> { }, status -> { });
            admin.identity = ADMIN.user();
            admin.connect(primary.url(), ADMIN.token, SERVER);
            RelayClient adminClient = admin;
            await("admin authenticates", adminClient::isAuthenticated, primary);
            check(admin.trySend(stroke("border01")), "Admin sends a war table stroke");
            Path table = primary.home.resolve("wartable.json");
            await("war table stroke reaches the member", () -> fileContains(table, "border01")
                    && strokeStored("border01")
                    && seen.stream().anyMatch(message -> tableMessage(message, "border01")), primary);

            secondary = startRelay(relayDir, sessionUrl);
            RelayProfile second = profile("Secondary", secondary.url(), MEMBER_A.token);
            config.relays.add(second);
            check(memberA.reconnect(), "Member A also connects to the second relay");
            RelayCoordinator forwarding = memberA;
            RunningRelay other = secondary;
            await("both relays accept member A", () -> ready(forwarding, first.id) && ready(forwarding, second.id), primary, other);
            check(first.share && second.share, "Member A's profiles have sharing on");
            memberB.disconnect();
            seen.clear();
            memberB.connect(secondary.url(), MEMBER_B.token, SERVER);
            await("member B is connected only to the second relay", secondAccount::isAuthenticated, other);
            seen.clear();
            check(clientFor(memberA, primary.url()).trySend(ping("pingfwd1", "forward", 20, 70, 5)),
                    "Ping enters the first relay");
            await("second relay delivers the forwarded ping", () -> seen.stream().anyMatch(message ->
                    pingFrom(message, "pingfwd1", "MemberA", 1)), other);
            check(pingKept("pingfwd1", "MemberA"), "PingManager keeps the forwarded ping");
            check(before.equals(fingerprint(relayDir)), "Tracked relay JSON was not modified");
            System.out.println("RelayServerIntegrationTest passed: welcome roster, deny, ping, war table, and multi-relay forward");
        } finally {
            disconnect(coordinator);
            disconnect(memberB);
            disconnect(admin);
            disconnect(bad);
            if (primary != null) primary.close();
            if (secondary != null) secondary.close();
            if (sessions != null) sessions.stop(0);
            if (previousSessionServer == null) System.clearProperty("openintel.sessionServer");
            else System.setProperty("openintel.sessionServer", previousSessionServer);
            instance.set(null, previousClient);
        }
    }

    private static RelayProfile profile(String name, String url, String token) {
        RelayProfile profile = new RelayProfile();
        profile.name = name;
        profile.url = url;
        profile.token = token;
        profile.minecraftServer = SERVER;
        profile.enabled = true;
        profile.share = true;
        return profile;
    }

    private static boolean ready(RelayCoordinator coordinator, String id) {
        String status = coordinator.status(id);
        return status.startsWith("authenticated (member)") && !status.contains("forwarding unavailable");
    }

    private static JsonObject ping(String id, String label, double x, double y, double z) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "ping");
        message.addProperty("id", id);
        message.addProperty("label", label);
        message.addProperty("x", x);
        message.addProperty("y", y);
        message.addProperty("z", z);
        message.addProperty("dim", "minecraft:overworld");
        message.addProperty("color", 0xFFAA00);
        return message;
    }

    private static JsonObject stroke(String id) {
        JsonObject stroke = new JsonObject();
        stroke.addProperty("id", id);
        stroke.addProperty("tool", "line");
        stroke.addProperty("color", 0x3355AA);
        stroke.addProperty("width", 2);
        JsonArray points = new JsonArray();
        points.add(0);
        points.add(0);
        points.add(30);
        points.add(12);
        stroke.add("points", points);
        JsonObject message = new JsonObject();
        message.addProperty("type", "wartable");
        message.addProperty("action", "add");
        message.add("stroke", stroke);
        return message;
    }

    private static boolean pingFrom(JsonObject message, String id, String sender, int minHops) {
        if (!"ping".equals(type(message)) || !message.has("id") || !id.equals(message.get("id").getAsString())) return false;
        if (!message.has("from") || !sender.equals(message.get("from").getAsString())) return false;
        if (minHops < 0) return true;
        if (!message.has("bridge") || !message.get("bridge").isJsonObject()
                || !message.getAsJsonObject("bridge").has("hops")) return false;
        return message.getAsJsonObject("bridge").get("hops").getAsInt() >= minHops;
    }

    private static boolean tableMessage(JsonObject message, String id) {
        String type = type(message);
        return (type.equals("wartable") || type.equals("wartable_sync")) && message.toString().contains(id);
    }

    private static boolean pingKept(String id, String sender) {
        for (PingManager.Ping ping : PingManager.active()) {
            if (id.equals(ping.id) && sender.equalsIgnoreCase(ping.sender)) return true;
        }
        return false;
    }

    private static boolean strokeStored(String id) {
        try {
            for (var stroke : WarTable.strokes()) if (id.equals(stroke.id)) return true;
        } catch (java.util.ConcurrentModificationException ignored) { }
        return false;
    }

    private static boolean fileContains(Path file, String text) {
        try {
            return Files.readString(file).contains(text);
        } catch (IOException error) {
            return false;
        }
    }

    private static String type(JsonObject message) {
        return message.has("type") ? message.get("type").getAsString() : "";
    }

    /** The coordinator does not expose its sockets. This reaches the real session client. */
    private static RelayClient clientFor(RelayCoordinator coordinator, String url) throws Exception {
        Field sessionsField = RelayCoordinator.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        Map<?, ?> sessions = (Map<?, ?>) sessionsField.get(coordinator);
        for (Object session : sessions.values()) {
            Field profileField = session.getClass().getDeclaredField("profile");
            profileField.setAccessible(true);
            RelayProfile profile = (RelayProfile) profileField.get(session);
            if (url.equals(profile.url)) {
                Field clientField = session.getClass().getDeclaredField("client");
                clientField.setAccessible(true);
                return (RelayClient) clientField.get(session);
            }
        }
        throw new AssertionError("No relay session for " + url);
    }

    private static void await(String description, BooleanSupplier condition, RunningRelay... relays) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                StringBuilder detail = new StringBuilder(description);
                for (RunningRelay relay : relays) {
                    if (relay == null) continue;
                    detail.append("\n--- relay :").append(relay.port).append(" ---\n").append(relay.log);
                    if (!relay.process.isAlive()) detail.append("\n(relay exited ").append(relay.process.exitValue()).append(')');
                }
                throw new AssertionError(detail.toString());
            }
            Thread.sleep(20);
        }
    }

    private static HttpServer startSessions() throws IOException {
        Map<String, String> joined = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session/minecraft/join", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonObject json = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            joined.put(json.get("serverId").getAsString(), json.get("selectedProfile").getAsString());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/session/minecraft/hasJoined", exchange -> {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            Account account = ACCOUNTS.stream().filter(candidate -> candidate.name.equals(query.get("username"))).findFirst().orElse(null);
            String proof = joined.get(query.get("serverId"));
            if (account == null || proof == null || !account.profileId().equalsIgnoreCase(proof)) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            byte[] profile = ("{\"id\":\"" + account.profileId() + "\",\"name\":\"" + account.name + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, profile.length);
            exchange.getResponseBody().write(profile);
            exchange.close();
        });
        server.setExecutor(command -> {
            Thread thread = new Thread(command, "session-stub");
            thread.setDaemon(true);
            thread.start();
        });
        server.start();
        return server;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new java.util.HashMap<>();
        if (raw == null) return values;
        for (String part : raw.split("&")) {
            int separator = part.indexOf('=');
            if (separator < 0) continue;
            values.put(decode(part.substring(0, separator)), decode(part.substring(separator + 1)));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static RunningRelay startRelay(Path relayDir, String sessionUrl) throws Exception {
        int port = freePort();
        Path home = Files.createTempDirectory("openintel-relay-");
        if (home.startsWith(relayDir)) throw new AssertionError("Fixture home must not be inside relay/");
        Files.writeString(home.resolve("config.json"), """
                {
                  "port": %d,
                  "secureWebSocketUrl": "",
                  "minecraftServer": "%s",
                  "adminToken": "fixture-admin-token",
                  "broadcastIntervalMs": 250,
                  "discord": { "botToken": "" },
                  "webhooks": { "admin": "", "alerts": "", "alertRoleId": "" }
                }
                """.formatted(port, SERVER));
        Files.writeString(home.resolve("users.json"), USERS);
        Files.writeString(home.resolve("allegiances.json"), ALLEGIANCES);
        Files.writeString(home.resolve("wartable.json"), "{ \"strokes\": [] }\n");
        ProcessBuilder builder = new ProcessBuilder("node", "server.js");
        builder.directory(relayDir.toFile());
        builder.environment().put("OPENINTEL_RELAY_HOME", home.toString());
        builder.environment().put("OPENINTEL_SESSION_SERVER", sessionUrl);
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new AssertionError("node is not available on PATH. Install Node.js before running the relay integration test.", error);
        }
        StringBuffer log = new StringBuffer();
        Thread pump = new Thread(() -> {
            try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[1024];
                int count;
                while ((count = reader.read(buffer)) >= 0) log.append(buffer, 0, count);
            } catch (IOException ignored) { }
        }, "relay-log");
        pump.setDaemon(true);
        pump.start();
        RunningRelay relay = new RunningRelay(process, log, home, port);
        await("relay listening on " + port, () -> log.indexOf("OpenIntel relay listening on :" + port) >= 0, relay);
        return relay;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static void installClient(Field instance) throws Exception {
        // Player and level class init touch the registries. This is the client's own startup order.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        ServerData server = allocate(ServerData.class);
        server.name = "Fixture";
        server.ip = SERVER;
        SilentPlayer player = allocate(SilentPlayer.class);
        player.profile = new com.mojang.authlib.GameProfile(MEMBER_A.id, MEMBER_A.name);
        HeadlessClient client = allocate(HeadlessClient.class);
        client.user = MEMBER_A.user();
        client.server = server;
        client.player = player;
        client.level = allocate(ClientLevel.class);
        instance.set(null, client);
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = OpenIntelClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Path relayDirectory() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path relay = dir.resolve("relay");
            if (Files.isRegularFile(relay.resolve("server.js"))) return relay;
            if ("relay".equals(String.valueOf(dir.getFileName())) && Files.isRegularFile(dir.resolve("server.js"))) return dir;
            dir = dir.getParent();
        }
        throw new AssertionError("Cannot find relay/server.js from " + Path.of("").toAbsolutePath()
                + ". Run this test from the repository.");
    }

    private static void requireNode() {
        try {
            Process probe = new ProcessBuilder("node", "-e", "process.exit(0)").redirectErrorStream(true).start();
            if (!probe.waitFor(10, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                throw new AssertionError("node did not respond. Install Node.js before running the relay integration test.");
            }
            if (probe.exitValue() != 0)
                throw new AssertionError("node is not available on PATH. Install Node.js before running the relay integration test.");
        } catch (IOException error) {
            throw new AssertionError("node is not available on PATH. Install Node.js before running the relay integration test.", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while checking for node.", error);
        }
    }

    private static String fingerprint(Path dir) throws IOException {
        StringBuilder out = new StringBuilder();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                out.append(file.getFileName()).append(':').append(Files.size(file)).append(':')
                        .append(Files.getLastModifiedTime(file).toMillis()).append('\n');
            }
        }
        return out.toString();
    }

    private static void disconnect(RelayClient client) {
        if (client == null) return;
        try { client.disconnect(); } catch (Throwable ignored) { }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        var unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Account(String name, String token, UUID id) {
        User user() { return new User(name, id, "fixture-access-token", Optional.empty(), Optional.empty()); }
        String profileId() { return id.toString().replace("-", ""); }
    }

    private record RunningRelay(Process process, StringBuffer log, Path home, int port) implements AutoCloseable {
        String url() { return "ws://127.0.0.1:" + port; }
        @Override public void close() {
            process.destroyForcibly();
            try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            try (var walk = Files.walk(home)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
        }
    }

    /** Same-thread client with a multiplayer server, a silent player, and a non-null level. */
    public static final class HeadlessClient extends Minecraft {
        User user;
        ServerData server;
        private HeadlessClient() { super(null); }
        @Override public void execute(Runnable runnable) { if (runnable != null) runnable.run(); }
        @Override public boolean isSameThread() { return true; }
        @Override public boolean hasSingleplayerServer() { return false; }
        @Override public ServerData getCurrentServer() { return server; }
        @Override public User getUser() { return user; }
    }

    public static final class SilentPlayer extends LocalPlayer {
        com.mojang.authlib.GameProfile profile;
        private SilentPlayer() { super(null, null, null, null, null, null, false, null); }
        @Override public void sendSystemMessage(Component message) { }
        @Override public com.mojang.authlib.GameProfile getGameProfile() { return profile; }
    }

}
