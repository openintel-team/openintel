package dev.openintel.net;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real loopback HTTP redirects and verified TLS upgrades; no Minecraft, Discord, or live relay. */
public final class RelayTransportTest {
    private static final WebSocket.Listener LISTENER = new WebSocket.Listener() { };

    public static void main(String[] args) throws Exception {
        URI original = URI.create("ws://relay.example.test:8765/");
        check(RelayTransport.secureRedirect(original, "wss://relay.example.test/relay").getPort() == -1,
                "TLS can move to the standard HTTPS port and a different path");
        check(RelayTransport.secureRedirect(original, "https://RELAY.example.test/relay").getScheme().equals("wss"),
                "Standard HTTPS redirects are accepted on the same host");
        for (String bad : new String[]{"ws://relay.example.test/", "http://relay.example.test/", "/relative",
                "wss://other.example.test/", "wss://relay.example.test.attacker.test/",
                "wss://relay.example.test@attacker.test/", "wss://user@relay.example.test/",
                "wss://relay.example.test/#fragment", "wss://relay.example.test:0/",
                "wss://relay.example.test:65536/", "wss://relay.example.test/\r\nInjected: value"}) {
            try { RelayTransport.secureRedirect(original, bad); throw new AssertionError("Unsafe redirect accepted"); }
            catch (IllegalArgumentException expected) { }
        }

        Path directory = Files.createTempDirectory("openintel-tls-test-");
        try {
            SSLContext trusted = fixtureContext(directory);
            try (HttpClient client = HttpClient.newBuilder().sslContext(trusted).build();
                 HttpClient untrusted = HttpClient.newHttpClient();
                 Fixture legacy = new Fixture(null);
                 Fixture secure = new Fixture(trusted);
                 Fixture redirect = new Fixture(null)) {
                var oldConnection = RelayTransport.connect(client, legacy.uri(), LISTENER).get(10, TimeUnit.SECONDS);
                oldConnection.socket().sendText("legacy-fixture", true).join();
                check("legacy-fixture".equals(legacy.messages.poll(5, TimeUnit.SECONDS)), "Legacy relays remain usable");
                check(oldConnection.endpoint().equals(legacy.uri()), "Legacy endpoint is unchanged");
                oldConnection.socket().abort();

                redirect.redirect = true;
                redirect.location = secure.uri().toString();
                AtomicReference<URI> remembered = new AtomicReference<>();
                var upgraded = RelayTransport.connect(client, redirect.uri(), LISTENER, remembered::set)
                        .get(15, TimeUnit.SECONDS);
                check(upgraded.endpoint().equals(secure.uri()), "Redirect selects the secure endpoint");
                check(remembered.get().equals(secure.uri()), "Secure endpoint is available for persistent pinning");
                check(redirect.upgrades.get() == 0 && redirect.messages.isEmpty(), "Plaintext leg never receives credentials");
                upgraded.socket().sendText("secure-fixture-token", true).join();
                check("secure-fixture-token".equals(secure.messages.poll(5, TimeUnit.SECONDS)), "Payload arrives over TLS");
                upgraded.socket().abort();

                int plaintextBeforeFailure = redirect.requests.get();
                remembered.set(null);
                try {
                    RelayTransport.connect(untrusted, redirect.uri(), LISTENER, remembered::set).get(15, TimeUnit.SECONDS);
                    throw new AssertionError("Untrusted certificate accepted");
                } catch (ExecutionException expected) {
                    check(hasCause(expected, SSLHandshakeException.class), "Certificate validation remains enabled");
                }
                check(secure.uri().equals(remembered.get()), "TLS destination is pinned even if certificate validation fails");
                check(redirect.requests.get() == plaintextBeforeFailure + 1 && redirect.upgrades.get() == 0,
                        "TLS failure never retries the plaintext WebSocket");
                check(secure.messages.isEmpty() && redirect.messages.isEmpty(), "Certificate failure leaks no token");

                secure.redirect = true;
                secure.location = secure.uri().toString();
                try {
                    RelayTransport.connect(client, redirect.uri(), LISTENER).get(15, TimeUnit.SECONDS);
                    throw new AssertionError("Redirect loop accepted");
                } catch (ExecutionException expected) {
                    check(hasCause(expected, IllegalArgumentException.class), "Redirect loops are bounded");
                }
                secure.redirect = false;
                redirect.location = "wss://other.example.test/";
                try {
                    RelayTransport.connect(client, redirect.uri(), LISTENER).get(10, TimeUnit.SECONDS);
                    throw new AssertionError("Cross-host redirect accepted");
                } catch (ExecutionException expected) {
                    check(hasCause(expected, IllegalArgumentException.class), "Cross-host redirect rejected before connecting");
                }
                redirect.location = null;
                try {
                    RelayTransport.connect(client, redirect.uri(), LISTENER).get(10, TimeUnit.SECONDS);
                    throw new AssertionError("Redirect without Location accepted");
                } catch (ExecutionException expected) {
                    check(hasCause(expected, IllegalArgumentException.class), "Missing redirect destination rejected");
                }
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("Relay transport tests passed: real TLS redirects, token isolation, certificate rejection, pinning, loops and legacy relays");
    }

    private static SSLContext fixtureContext(Path directory) throws Exception {
        Path store = directory.resolve("fixture.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "fixture-password", "-noprompt")
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("keytool timeout"); }
        check(process.exitValue() == 0, "Ephemeral TLS fixture created");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(store)) { keys.load(input, "fixture-password".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "fixture-password".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) if (type.isInstance(current)) return true;
        return false;
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class Fixture implements AutoCloseable {
        final ServerSocket server;
        final boolean tls;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger upgrades = new AtomicInteger();
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        volatile boolean redirect;
        volatile String location;

        Fixture(SSLContext context) throws Exception {
            tls = context != null;
            server = context == null ? new ServerSocket() : context.getServerSocketFactory().createServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            workers.submit(() -> {
                while (!server.isClosed()) {
                    try { Socket socket = server.accept(); workers.submit(() -> serve(socket)); }
                    catch (Exception ignored) { break; }
                }
            });
        }

        URI uri() { return URI.create((tls ? "wss" : "ws") + "://localhost:" + server.getLocalPort() + "/relay"); }

        private void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(5000);
                InputStream input = socket.getInputStream();
                StringBuilder headers = new StringBuilder();
                while (!headers.toString().endsWith("\r\n\r\n")) {
                    int value = input.read();
                    if (value < 0 || headers.length() > 16384) return;
                    headers.append((char) value);
                }
                requests.incrementAndGet();
                if (redirect) {
                    if (!tls) check(headers.toString().toLowerCase(Locale.ROOT).contains("x-openintel-prefer-tls: 1"),
                            "Upgrade request is credential-free and explicitly opts in");
                    String response = "HTTP/1.1 307 Temporary Redirect\r\n"
                            + (location == null ? "" : "Location: " + location + "\r\n")
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                String key = headers.toString().lines().filter(line -> line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))
                        .findFirst().orElseThrow().split(":", 2)[1].trim();
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                        + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                upgrades.incrementAndGet();
                int opcode = input.read();
                int flags = input.read();
                if (opcode < 0 || flags < 0 || (opcode & 15) != 1) return;
                int length = flags & 127;
                if (length >= 126) throw new AssertionError("Fixture payload too long");
                byte[] mask = input.readNBytes(4);
                byte[] payload = input.readNBytes(length);
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                messages.add(new String(payload, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // The untrusted-certificate test intentionally aborts TLS before sending HTTP or credentials.
            }
        }

        @Override public void close() throws Exception { server.close(); workers.shutdownNow(); workers.awaitTermination(10, TimeUnit.SECONDS); }
    }
}
