package dev.openintel.smoke;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.openintel.OpenIntelClient;
import dev.openintel.api.OpenIntelApi;
import dev.openintel.api.Snapshots.ConnectionState;
import dev.openintel.config.RelayProfile;
import dev.openintel.gui.ClickGuiScreen;
import dev.openintel.net.RelayClient;
import dev.openintel.net.RelayCoordinator;
import dev.openintel.ping.PingManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Property-gated client driver. Normal launches never load this class.
 * It joins a singleplayer world, then checks settings, a local snitch, and a fixture ping.
 */
public final class LiveClientSmoke {
    private static final Logger LOGGER = LoggerFactory.getLogger("OpenIntel");
    /** Folder id passed to {@code --quickPlaySingleplayer}. */
    private static final String WORLD_ID = "OpenIntelSmoke";
    private static final String SERVER = "smoke.local";
    private static final String PLAYER_TOKEN = "smoke-player-token";
    private static final String PING_NAME = "SmokePing";
    private static final String PING_TOKEN = "smoke-ping-token";
    private static final UUID PING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final long DEADLINE_NANOS = TimeUnit.MINUTES.toNanos(8);

    private static final AtomicBoolean stopped = new AtomicBoolean();
    private static volatile boolean passed;
    private static volatile boolean cleaned;

    private static Phase phase = Phase.LEVEL;
    private static boolean worldCreateStarted;
    private static boolean openRequested;
    private static boolean reportedScreen;
    private static int worldWaitTicks;
    private static boolean relayArmed;
    private static boolean pingSent;
    private static int settingsTicks;
    private static String relayUrl;
    private static RelayClient pinger;
    private static HttpServer sessions;
    private static RunningRelay relay;
    private static final long started = System.nanoTime();

    private enum Phase { LEVEL, READY, SETTINGS, PING }

    private LiveClientSmoke() { }

    public static void register() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cleanup();
            if (!passed) Runtime.getRuntime().halt(1);
        }, "live-smoke-status"));
        try {
            start();
            ClientTickEvents.END_CLIENT_TICK.register(LiveClientSmoke::tick);
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(TimeUnit.MINUTES.toMillis(8));
                } catch (InterruptedException error) {
                    return;
                }
                if (!passed) fail("timed out before SMOKE_OK");
            }, "live-smoke-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        } catch (Exception error) {
            fail(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private static void start() throws Exception {
        User user = Minecraft.getInstance().getUser();
        if (user == null || user.getName() == null || user.getProfileId() == null)
            throw new IllegalStateException("live smoke needs the logged-in Minecraft profile");
        if (!user.getName().matches("[A-Za-z0-9_]{3,16}"))
            throw new IllegalStateException("logged-in name cannot be written into the fixture user list");
        if (PING_NAME.equalsIgnoreCase(user.getName()))
            throw new IllegalStateException("logged-in name collides with the fixture ping account");
        Path relayDir = relayDirectory();
        if (!Files.isDirectory(relayDir.resolve("node_modules")))
            throw new IllegalStateException("relay/node_modules is missing. Run npm install in relay/ before liveClientSmoke.");
        sessions = startSessions(user);
        String sessionUrl = "http://127.0.0.1:" + sessions.getAddress().getPort();
        System.setProperty("openintel.sessionServer", sessionUrl);
        relay = startRelay(relayDir, sessionUrl, user);
        relayUrl = relay.url();
        var config = OpenIntelClient.config();
        config.relayUrl = relayUrl;
        config.token = PLAYER_TOKEN;
        config.minecraftServer = SERVER;
        RelayProfile profile = new RelayProfile();
        profile.name = "Smoke";
        profile.url = relayUrl;
        profile.token = PLAYER_TOKEN;
        profile.minecraftServer = SERVER;
        profile.enabled = true;
        profile.share = true;
        config.relays = new java.util.ArrayList<>();
        config.relays.add(profile);
        LOGGER.info("live smoke relay ready");
    }

    private static void tick(Minecraft client) {
        if (stopped.get()) return;
        try {
            if (System.nanoTime() - started > DEADLINE_NANOS) {
                fail("timed out before SMOKE_OK during " + phase);
                return;
            }
            if (client.level != null && client.player != null && !relayArmed) {
                relayArmed = true;
                OpenIntelClient.reconnectRelay();
            }
            if (relayArmed && OpenIntelClient.relay().state() == ConnectionState.DENIED)
                fail("relay denied the logged-in session" + statusDetail());
            switch (phase) {
                case LEVEL -> waitForLevel(client);
                case READY -> checkReady(client);
                case SETTINGS -> checkSettings(client);
                case PING -> checkPing(client);
            }
            if (relayArmed) maintainPinger(client);
        } catch (Exception error) {
            fail(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private static void waitForLevel(Minecraft client) {
        // Quick play runs when the loading overlay finishes. Wait for that before creating a missing world.
        if (!client.isGameLoadFinished()) return;
        if (client.level != null && client.player != null) {
            phase = Phase.READY;
            return;
        }
        // 1.21.11 keeps the loading overlay separate from the current screen.
        var screen = client.screen;
        var overlay = client.getOverlay();
        String screenName = overlay != null ? overlay.getClass().getSimpleName()
                : screen == null ? "none" : screen.getClass().getSimpleName();
        if (!reportedScreen) {
            reportedScreen = true;
            LOGGER.info("live smoke waiting for world, screen {}", screenName);
        }
        boolean busy = screenName.equals("LoadingOverlay") || screenName.equals("ProgressScreen")
                || screenName.equals("GenericMessageScreen") || screenName.equals("GenericWaitingScreen");
        if (!client.getLevelSource().levelExists(WORLD_ID)) {
            if (!worldCreateStarted) {
                worldCreateStarted = true;
                LOGGER.info("live smoke creating quick-play world {}", WORLD_ID);
                client.createWorldOpenFlows().createFreshLevel(WORLD_ID,
                        new LevelSettings("OpenIntel Smoke", GameType.CREATIVE, false, Difficulty.EASY, true,
                                new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT),
                        WorldOptions.defaultWithRandomSeed(),
                        WorldPresets::createNormalWorldDimensions,
                        screen);
            }
        } else if (!worldCreateStarted && !openRequested && !busy) {
            // createFreshLevel enters the new world. Opening it again overlaps the session lock.
            openRequested = true;
            LOGGER.info("live smoke opening quick-play world {}", WORLD_ID);
            client.createWorldOpenFlows().openWorld(WORLD_ID, () -> fail("could not open quick-play world"));
        }
        if (++worldWaitTicks > 20 * 90)
            fail("quick-play world did not load (screen " + screenName + ")");
    }

    private static void checkReady(Minecraft client) {
        if (client.getConnection() == null || !OpenIntelApi.isReady()) return;
        client.getConnection().sendCommand("oi settings");
        phase = Phase.SETTINGS;
    }

    private static void checkSettings(Minecraft client) {
        if (client.screen instanceof ClickGuiScreen) {
            client.setScreen(null);
            client.getConnection().sendCommand("oi snitchtest");
            boolean placed = OpenIntelApi.snitches().list().stream()
                    .anyMatch(snitch -> "Test Vault".equals(snitch.snitch())
                            && "ExamplePlayer".equalsIgnoreCase(snitch.player()));
            if (!placed) fail("snitchtest did not add a snitch snapshot");
            phase = Phase.PING;
            return;
        }
        if (++settingsTicks > 100)
            fail("/oi settings did not open ClickGuiScreen");
    }

    private static void maintainPinger(Minecraft client) {
        if (pinger != null || !OpenIntelClient.relay().isAuthenticated()) return;
        pinger = new RelayClient(message -> { }, status -> { }, () -> true, () -> true, () -> { }, false, null);
        useIdentity(pinger, new User(PING_NAME, PING_ID, "fixture-access-token", Optional.empty(), Optional.empty()));
        pinger.connect(relayUrl, PING_TOKEN, SERVER);
    }

    private static void checkPing(Minecraft client) {
        if (pinger == null) return;
        if (pinger.state() == ConnectionState.DENIED) fail("relay denied the fixture ping account");
        if (!pinger.isAuthenticated()) return;
        if (!pingSent) {
            pingSent = pinger.trySend(ping());
            if (!pingSent) return;
        }
        for (PingManager.Ping ping : PingManager.active()) {
            if ("smoke-ping".equals(ping.id) && PING_NAME.equalsIgnoreCase(ping.sender)) {
                succeed(client);
                return;
            }
        }
    }

    private static JsonObject ping() {
        JsonObject message = new JsonObject();
        message.addProperty("type", "ping");
        message.addProperty("id", "smoke-ping");
        message.addProperty("label", "smoke");
        message.addProperty("x", 8);
        message.addProperty("y", 64);
        message.addProperty("z", 8);
        message.addProperty("dim", "minecraft:overworld");
        message.addProperty("color", 0xFFAA00);
        return message;
    }

    private static void useIdentity(RelayClient client, User user) {
        try {
            Field identity = RelayClient.class.getDeclaredField("identity");
            identity.setAccessible(true);
            identity.set(client, user);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("could not set the fixture relay identity", error);
        }
    }

    private static String statusDetail() {
        if (!(OpenIntelClient.relay() instanceof RelayCoordinator coordinator)) return "";
        StringBuilder detail = new StringBuilder();
        for (RelayProfile profile : OpenIntelClient.config().relayProfiles())
            detail.append(' ').append(profile.name).append('=').append(coordinator.status(profile.id));
        if (relay != null && relay.log.length() > 0) {
            String text = relay.log.toString();
            detail.append("\n--- relay ---\n").append(text.substring(Math.max(0, text.length() - 2000)));
        }
        return detail.toString();
    }

    private static void succeed(Minecraft client) {
        if (!stopped.compareAndSet(false, true)) return;
        passed = true;
        LOGGER.info("SMOKE_OK");
        System.out.println("SMOKE_OK");
        cleanup();
        Thread bumper = new Thread(() -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ignored) { }
            System.exit(0);
        }, "live-smoke-exit");
        bumper.setDaemon(true);
        bumper.start();
        client.stop();
    }

    private static void fail(String message) {
        if (!stopped.compareAndSet(false, true)) return;
        LOGGER.error("SMOKE_FAIL {}", message);
        System.err.println("SMOKE_FAIL " + message);
        cleanup();
        System.exit(1);
    }

    private static HttpServer startSessions(User player) throws IOException {
        Account loggedIn = new Account(player.getName(), player.getProfileId().toString().replace("-", ""));
        Account pingerAccount = new Account(PING_NAME, PING_ID.toString().replace("-", ""));
        List<Account> accounts = List.of(loggedIn, pingerAccount);
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
            Account account = accounts.stream().filter(candidate -> candidate.name.equalsIgnoreCase(query.get("username")))
                    .findFirst().orElse(null);
            String proof = joined.get(query.get("serverId"));
            if (account == null || proof == null || !account.id.equalsIgnoreCase(proof)) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            byte[] profile = ("{\"id\":\"" + account.id + "\",\"name\":\"" + account.name + "\"}")
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

    private static RunningRelay startRelay(Path relayDir, String sessionUrl, User player) throws Exception {
        int port = freePort();
        Path home = Files.createTempDirectory("openintel-smoke-");
        if (home.startsWith(relayDir)) throw new IllegalStateException("fixture home must not be inside relay/");
        String playerId = player.getProfileId().toString().replace("-", "");
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
        Files.writeString(home.resolve("users.json"), """
                { "users": [
                  { "name": "%s", "token": "%s", "role": "member", "minecraftUuid": "%s" },
                  { "name": "%s", "token": "%s", "role": "member", "minecraftUuid": "%s" }
                ] }
                """.formatted(player.getName(), PLAYER_TOKEN, playerId,
                PING_NAME, PING_TOKEN, PING_ID.toString().replace("-", "")));
        Files.writeString(home.resolve("allegiances.json"), "{ \"allies\": [], \"enemies\": [], \"focus\": [] }\n");
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
            throw new IllegalStateException("node is not available on PATH. Install Node.js before running liveClientSmoke.", error);
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
        RunningRelay running = new RunningRelay(process, log, home, port);
        relay = running;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (log.indexOf("OpenIntel relay listening on :" + port) < 0) {
            if (!process.isAlive())
                throw new IllegalStateException("relay exited " + process.exitValue() + "\n" + log);
            if (System.nanoTime() >= deadline)
                throw new IllegalStateException("relay did not listen on " + port + "\n" + log);
            Thread.sleep(20);
        }
        return running;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static Path relayDirectory() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path relay = dir.resolve("relay");
            if (Files.isRegularFile(relay.resolve("server.js"))) return relay;
            if ("relay".equals(String.valueOf(dir.getFileName())) && Files.isRegularFile(dir.resolve("server.js"))) return dir;
            dir = dir.getParent();
        }
        throw new IllegalStateException("Cannot find relay/server.js. Run liveClientSmoke from the repository.");
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

    private static synchronized void cleanup() {
        if (cleaned) return;
        cleaned = true;
        try {
            if (pinger != null) pinger.disconnect();
        } catch (Throwable ignored) { }
        if (relay != null) relay.close();
        if (sessions != null) sessions.stop(0);
    }

    private record Account(String name, String id) { }

    private record RunningRelay(Process process, StringBuffer log, Path home, int port) {
        String url() { return "ws://127.0.0.1:" + port; }
        void close() {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            try (var walk = Files.walk(home)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
        }
    }
}
