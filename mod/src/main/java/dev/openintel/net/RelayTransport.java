package dev.openintel.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Credential-free WebSocket negotiation. Authentication belongs after this future succeeds. */
public final class RelayTransport {
    public static final String PREFER_TLS_HEADER = "X-OpenIntel-Prefer-TLS";
    private static final int MAX_REDIRECTS = 3;

    private RelayTransport() { }

    public record Connection(WebSocket socket, URI endpoint) { }

    public static CompletableFuture<Connection> connect(HttpClient client, URI endpoint,
                                                         WebSocket.Listener listener) {
        return connect(client, endpoint, listener, ignored -> { });
    }

    public static CompletableFuture<Connection> connect(HttpClient client, URI endpoint,
                                                         WebSocket.Listener listener, Consumer<URI> onUpgrade) {
        return connect(client, endpoint, endpoint, listener, onUpgrade, 0);
    }

    private static CompletableFuture<Connection> connect(HttpClient client, URI original, URI endpoint,
                                                          WebSocket.Listener listener, Consumer<URI> onUpgrade, int redirects) {
        try {
            validateEndpoint(endpoint);
            var builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
            if ("ws".equalsIgnoreCase(endpoint.getScheme())) builder.header(PREFER_TLS_HEADER, "1");
            return builder.buildAsync(endpoint, listener).handle((socket, error) -> {
                if (error == null) {
                    return CompletableFuture.completedFuture(new Connection(socket, endpoint));
                }
                Throwable cause = unwrap(error);
                if (cause instanceof WebSocketHandshakeException handshake) {
                    int status = handshake.getResponse().statusCode();
                    if (status == 301 || status == 302 || status == 307 || status == 308) {
                        try {
                            if (redirects >= MAX_REDIRECTS) throw new IllegalArgumentException("Too many relay redirects");
                            String location = handshake.getResponse().headers().firstValue("Location")
                                    .orElseThrow(() -> new IllegalArgumentException("Relay redirect has no destination"));
                            URI upgraded = secureRedirect(original, location);
                            onUpgrade.accept(upgraded);
                            // There is deliberately no retry using WS if this connection or its TLS check fails.
                            return connect(client, original, upgraded, listener, onUpgrade, redirects + 1);
                        } catch (IllegalArgumentException rejected) {
                            return CompletableFuture.<Connection>failedFuture(rejected);
                        }
                    }
                }
                return CompletableFuture.<Connection>failedFuture(cause);
            }).thenCompose(future -> future);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public static URI secureRedirect(URI original, String location) {
        if (location == null || location.length() > 2048) throw new IllegalArgumentException("Invalid relay redirect");
        URI target = URI.create(location);
        if ("https".equalsIgnoreCase(target.getScheme())) {
            target = URI.create("wss" + location.substring(location.indexOf(':')));
        }
        validateEndpoint(target);
        if (!"wss".equalsIgnoreCase(target.getScheme()) || original.getHost() == null
                || !original.getHost().equalsIgnoreCase(target.getHost())) {
            throw new IllegalArgumentException("Relay redirects must use WSS on the same host");
        }
        return target;
    }

    private static void validateEndpoint(URI endpoint) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null || endpoint.getPort() < -1 || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                || !("ws".equalsIgnoreCase(endpoint.getScheme()) || "wss".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("Invalid relay WebSocket endpoint");
        }
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }
}
