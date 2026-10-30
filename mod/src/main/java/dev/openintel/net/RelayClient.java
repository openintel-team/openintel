package dev.openintel.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.openintel.OpenIntelClient;
import dev.openintel.api.Snapshots.ConnectionState;
import dev.openintel.api.internal.ApiBridge;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Minimal WebSocket client (JDK built-in, no extra deps) with auto-reconnect.
 * Sends JSON text frames; hands complete inbound messages to a consumer.
 */
public class RelayClient implements WebSocket.Listener {
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger("openintel-relay");
    // One shared client: each HttpClient owns a selector thread + worker pool,
    // so building one per connection attempt leaks threads on every reconnect.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final ConcurrentHashMap<WebSocket, StringBuilder> partials = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong inFlightGeneration = new AtomicLong(-1);
    private final Consumer<JsonObject> onMessage;
    private final Consumer<String> onStatus;

    private final java.util.function.BooleanSupplier serverAllowed;
    private final java.util.function.BooleanSupplier sendingAllowed;
    private final Runnable stateChanged;
    private final Consumer<String> onSecureEndpoint;
    private final boolean publishApi;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;

    private volatile String url;
    private volatile String token;
    private volatile String minecraftServer;
    private volatile boolean wantConnected = false;
    private volatile boolean authenticated;
    private volatile String role;
    private WebSocket identitySocket;
    private WebSocket provenSocket;
    private RelayIdentityProof.Lease identityProof;
    private volatile long lastAttempt = 0;
    private volatile int backoffMs = 1000;

    public RelayClient(Consumer<JsonObject> onMessage, Consumer<String> onStatus) {
        this(onMessage, onStatus, () -> true, () -> true, () -> {}, true, null);
    }

    public RelayClient(Consumer<JsonObject> onMessage, Consumer<String> onStatus,
                       java.util.function.BooleanSupplier serverAllowed,
                       java.util.function.BooleanSupplier sendingAllowed, Runnable stateChanged, boolean publishApi,
                       Consumer<String> onSecureEndpoint) {
        this.onMessage = onMessage;
        this.onStatus = onStatus;
        this.serverAllowed = serverAllowed;
        this.sendingAllowed = sendingAllowed;
        this.stateChanged = stateChanged;
        this.publishApi = publishApi;
        this.onSecureEndpoint = onSecureEndpoint == null ? ignored -> { } : onSecureEndpoint;
    }

    private void rememberSecureEndpoint(String previousUrl, String secureUrl) {
        if (secureUrl == null || secureUrl.equals(url)) return;
        url = secureUrl;
        if (publishApi) {
            var config = OpenIntelClient.config();
            if (config != null && previousUrl.equals(config.relayUrl)) {
                config.relayUrl = secureUrl;
                config.save();
            }
        }
        onSecureEndpoint.accept(secureUrl);
    }

    public ConnectionState state() { return state; }
    public String role() { return role; }

    public void connect(String url, String token, String minecraftServer) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            long expectedGeneration = generation.get();
            var expectedWorld = client.level;
            client.execute(() -> {
                if (generation.get() == expectedGeneration && client.level == expectedWorld) connect(url, token, minecraftServer);
            });
            return;
        }
        this.url = url;
        this.token = token;
        this.minecraftServer = minecraftServer;
        this.wantConnected = true;
        long connectionGeneration = generation.incrementAndGet();
        inFlightGeneration.set(-1);
        WebSocket previous = socket.getAndSet(null);
        if (previous != null) previous.abort();
        partials.clear();
        setState(ConnectionState.CONNECTING, null);
        if (isCurrentSession(connectionGeneration) && socket.get() == null) attempt();
    }

    public void disconnect() {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            long expectedGeneration = generation.get();
            client.execute(() -> { if (generation.get() == expectedGeneration) disconnect(); });
            return;
        }
        wantConnected = false;
        generation.incrementAndGet();
        inFlightGeneration.set(-1);
        WebSocket ws = socket.getAndSet(null);
        if (ws != null) ws.abort();
        partials.clear();
        minecraftServer = null;
        setState(ConnectionState.DISCONNECTED, null);
    }

    public boolean isConnected() {
        return socket.get() != null;
    }

    public boolean isAuthenticated() { return authenticated && socket.get() != null; }

    public long sessionGeneration() { return generation.get(); }

    /** Call from the client tick loop; reconnects with backoff if dropped. */
    public void tick() {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            client.execute(this::tick);
            return;
        }
        if (wantConnected && !selectedServerMatches()) {
            disconnect();
            return;
        }
        if (wantConnected && socket.get() == null
                && System.currentTimeMillis() - lastAttempt > backoffMs) {
            attempt();
        }
    }

    public void send(JsonObject message) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            long expected = generation.get();
            WebSocket expectedSocket = socket.get();
            JsonObject copy = message.deepCopy();
            client.execute(() -> {
                if (expected == generation.get() && expectedSocket == socket.get()) send(copy);
            });
            return;
        }
        long expectedGeneration = generation.get();
        WebSocket expectedSocket = socket.get();
        if (message.has("type") && "snitch".equals(message.get("type").getAsString())) ApiBridge.relaySnitch(message);
        if (generation.get() == expectedGeneration && socket.get() == expectedSocket) trySend(message);
    }

    public boolean trySend(JsonObject message) {
        if (!Minecraft.getInstance().isSameThread()) return false;
        WebSocket ws = socket.get();
        if (ws == null || !authenticated || !selectedServerMatches() || !sendingAllowed.getAsBoolean()) return false;
        try {
            long expected = generation.get();
            ws.sendText(GSON.toJson(message), true).whenComplete((ignored, error) -> {
                if (error != null) dispatch(ws, expected, () -> dropSocket(ws));
            });
            return true;
        } catch (Exception e) {
            dropSocket(ws);
            return false;
        }
    }

    private void attempt() {
        long attemptGeneration = generation.get();
        if (!isCurrentSession(attemptGeneration) || socket.get() != null) return;
        if (!inFlightGeneration.compareAndSet(-1, attemptGeneration)) return;
        lastAttempt = System.currentTimeMillis();
        String attemptUrl = url;
        String attemptToken = token;
        String attemptMinecraftServer = minecraftServer;
        setState(ConnectionState.CONNECTING, null);
        if (!isCurrentSession(attemptGeneration) || socket.get() != null) {
            inFlightGeneration.compareAndSet(attemptGeneration, -1);
            return;
        }
        RelayIdentityProof.Lease lease = RelayIdentityProof.reserve();
        identityProof = lease;
        lease.whenReady().whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
            if (!isCurrentSession(attemptGeneration) || socket.get() != null) {
                lease.close();
                inFlightGeneration.compareAndSet(attemptGeneration, -1);
                return;
            }
            if (error != null) {
                inFlightGeneration.compareAndSet(attemptGeneration, -1);
                setState(ConnectionState.DISCONNECTED, null);
                return;
            }
            openConnection(attemptGeneration, attemptUrl, attemptToken, attemptMinecraftServer);
        }));
    }

    private void openConnection(long attemptGeneration, String attemptUrl, String attemptToken, String attemptMinecraftServer) {
        try {
            RelayTransport.connect(HTTP, URI.create(attemptUrl), this,
                            upgraded -> Minecraft.getInstance().execute(() -> {
                                if (!isCurrentSession(attemptGeneration)) return;
                                rememberSecureEndpoint(url, upgraded.toString());
                            }))
                    .whenComplete((connection, err) -> Minecraft.getInstance().execute(() -> {
                        WebSocket ws = connection == null ? null : connection.socket();
                        inFlightGeneration.compareAndSet(attemptGeneration, -1);
                        if (!isCurrentSession(attemptGeneration) || socket.get() != null) {
                            if (ws != null) ws.abort();
                            return;
                        }
                        if (err != null || ws == null) {
                            backoffMs = Math.min(backoffMs * 2, 30_000);
                            setState(ConnectionState.DISCONNECTED, null);
                            if (isCurrentSession(attemptGeneration) && socket.get() == null) onStatus.accept("relay connect failed");
                            return;
                        }
                        if (!socket.compareAndSet(null, ws)) {
                            ws.abort();
                            return;
                        }
                        if ("ws".equalsIgnoreCase(URI.create(attemptUrl).getScheme())
                                && "wss".equalsIgnoreCase(connection.endpoint().getScheme())) {
                            rememberSecureEndpoint(attemptUrl, connection.endpoint().toString());
                        }
                        backoffMs = 1000;
                        setState(ConnectionState.AUTHENTICATING, null);
                        if (!isCurrentSession(attemptGeneration) || socket.get() != ws) {
                            if (socket.compareAndSet(ws, null)) setState(ConnectionState.DISCONNECTED, null);
                            ws.abort();
                            return;
                        }
                        JsonObject hello = new JsonObject();
                        hello.addProperty("type", "hello");
                        hello.addProperty("token", attemptToken);
                        hello.addProperty("minecraftServer", attemptMinecraftServer);
                        try {
                            ws.sendText(GSON.toJson(hello), true).whenComplete((ignored, error) -> {
                                if (error != null) dispatch(ws, attemptGeneration, () -> dropSocket(ws));
                            });
                            if (!isCurrentSession(attemptGeneration) || socket.get() != ws) return;
                            ws.request(1);
                        } catch (Exception error) {
                            dropSocket(ws);
                        }
                    }));
        } catch (Exception e) {
            inFlightGeneration.compareAndSet(attemptGeneration, -1);
            if (!isCurrentSession(attemptGeneration) || socket.get() != null) return;
            backoffMs = Math.min(backoffMs * 2, 30_000);
            setState(ConnectionState.DISCONNECTED, null);
            if (isCurrentSession(attemptGeneration) && socket.get() == null) onStatus.accept("relay connection error");
        }
    }

    private void dropSocket(WebSocket ws) {
        if (socket.compareAndSet(ws, null)) {
            ws.abort();
            setState(ConnectionState.DISCONNECTED, null);
        }
        partials.remove(ws);
    }

    private void setState(ConnectionState state, String suppliedRole) {
        if (state != ConnectionState.AUTHENTICATING && identityProof != null) {
            identityProof.close();
            identityProof = null;
        }
        this.state = state;
        authenticated = state == ConnectionState.AUTHENTICATED;
        role = authenticated ? suppliedRole : null;
        if (publishApi) ApiBridge.connectionChanged(state, minecraftServer, role);
        stateChanged.run();
    }

    private boolean isCurrentSession(long expectedGeneration) {
        return generation.get() == expectedGeneration && wantConnected && selectedServerMatches();
    }

    private boolean selectedServerMatches() {
        var client = Minecraft.getInstance();
        var config = OpenIntelClient.config();
        return config != null && client.player != null && client.level != null && !client.hasSingleplayerServer()
                && minecraftServer != null && minecraftServer.equals(OpenIntelClient.currentMinecraftServer(client))
                && (publishApi ? minecraftServer.equals(OpenIntelClient.normalizeMinecraftServer(config.minecraftServer))
                        : serverAllowed.getAsBoolean());
    }

    private void dispatch(WebSocket ws, long expectedGeneration, Runnable delivery) {
        Minecraft.getInstance().execute(() -> {
            if (socket.get() != ws || generation.get() != expectedGeneration || !wantConnected) return;
            if (!selectedServerMatches()) {
                disconnect();
                return;
            }
            delivery.run();
        });
    }

    // ---- WebSocket.Listener ----

    private void proveIdentity(WebSocket ws, long expectedGeneration, JsonObject message) {
        if (authenticated || identitySocket == ws) {
            identityFailed(ws);
            return;
        }
        identitySocket = ws;
        try {
            var user = Minecraft.getInstance().getUser();
            String clientNonce = RelayIdentityProof.clientNonce();
            String serverId = RelayIdentityProof.sessionServerId(message.get("challenge").getAsString(), clientNonce);
            identityProof.join(HTTP, user.getProfileId(), user.getAccessToken(), serverId).whenComplete((ignored, error) ->
                    dispatch(ws, expectedGeneration, () -> {
                        if (error != null) {
                            identityFailed(ws);
                            return;
                        }
                        provenSocket = ws;
                        JsonObject proof = new JsonObject();
                        proof.addProperty("type", "authenticate");
                        proof.addProperty("clientNonce", clientNonce);
                        ws.sendText(GSON.toJson(proof), true).whenComplete((sent, sendError) -> {
                            if (sendError != null) dispatch(ws, expectedGeneration, () -> identityFailed(ws));
                        });
                    }));
        } catch (Exception error) {
            identityFailed(ws);
        }
    }

    private void identityFailed(WebSocket ws) {
        wantConnected = false;
        generation.incrementAndGet();
        socket.compareAndSet(ws, null);
        partials.remove(ws);
        ws.abort();
        setState(ConnectionState.DENIED, null);
        onStatus.accept("relay Minecraft account verification failed");
    }

    @Override
    public void onOpen(WebSocket ws) { }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        long expectedGeneration = generation.get();
        if (socket.get() != ws) return null;
        StringBuilder partial = partials.computeIfAbsent(ws, ignored -> new StringBuilder());
        partial.append(data);
        if (partial.length() > 1_048_576) {
            partials.remove(ws);
            dispatch(ws, expectedGeneration, () -> dropSocket(ws));
            return null;
        }
        if (last) {
            String full = partial.toString();
            partial.setLength(0);
            dispatch(ws, expectedGeneration, () -> {
                try {
                    JsonObject message = GSON.fromJson(full, JsonObject.class);
                    if (message == null || !message.has("type")) return;
                    String type = message.get("type").getAsString();
                    if (type.equals("auth_challenge")) {
                        proveIdentity(ws, expectedGeneration, message);
                        return;
                    } else if (type.equals("welcome")) {
                        if (provenSocket != ws) {
                            identityFailed(ws);
                            return;
                        }
                        if (message.has("minecraftServer") && !minecraftServer.equals(
                                OpenIntelClient.normalizeMinecraftServer(message.get("minecraftServer").getAsString()))) {
                            disconnect();
                            return;
                        }
                        String suppliedRole = message.has("role") && !message.get("role").isJsonNull()
                                ? message.get("role").getAsString() : null;
                        if (suppliedRole != null && !suppliedRole.matches("[a-zA-Z0-9_-]{1,32}")) suppliedRole = null;
                        setState(ConnectionState.AUTHENTICATED, suppliedRole);
                    } else if (type.equals("deny")) {
                        wantConnected = false;
                        long deniedGeneration = generation.incrementAndGet();
                        socket.compareAndSet(ws, null);
                        partials.remove(ws);
                        ws.abort();
                        setState(ConnectionState.DENIED, null);
                        if (generation.get() == deniedGeneration && !wantConnected) onMessage.accept(message);
                        return;
                    } else if (!authenticated) {
                        return;
                    } else if (type.equals("allegiances") && message.has("role")) {
                        String suppliedRole = message.get("role").isJsonNull() ? null : message.get("role").getAsString();
                        if (suppliedRole != null && !suppliedRole.matches("[a-zA-Z0-9_-]{1,32}")) suppliedRole = null;
                        setState(ConnectionState.AUTHENTICATED, suppliedRole);
                    }
                    if (socket.get() != ws || generation.get() != expectedGeneration || !wantConnected
                            || !selectedServerMatches()) return;
                    onMessage.accept(message);
                } catch (Exception error) {
                    LOGGER.warn("Ignoring malformed relay message ({})", error.getClass().getSimpleName());
                }
            });
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        partials.remove(ws);
        long expectedGeneration = generation.get();
        dispatch(ws, expectedGeneration, () -> {
            boolean terminal = statusCode == 4001 || statusCode == 4003;
            if (terminal) {
                wantConnected = false;
                generation.incrementAndGet();
            }
            if (socket.compareAndSet(ws, null)) {
                long closedGeneration = generation.get();
                setState(terminal ? ConnectionState.DENIED : ConnectionState.DISCONNECTED, null);
                if (generation.get() == closedGeneration && socket.get() == null)
                    onStatus.accept(terminal ? "relay connection stopped (" + statusCode + ")"
                            : "relay disconnected (" + statusCode + ")");
            }
        });
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        partials.remove(ws);
        long expectedGeneration = generation.get();
        dispatch(ws, expectedGeneration, () -> {
            dropSocket(ws);
            if (generation.get() == expectedGeneration && socket.get() == null) onStatus.accept("relay connection error");
        });
    }
}
