package dev.openintel.net;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Proves the active Minecraft account to Mojang without disclosing its token to a relay. */
final class RelayIdentityProof {
    private static CompletableFuture<Void> previousProof = CompletableFuture.completedFuture(null);
    private RelayIdentityProof() { }

    // Minecraft session registration can replace the previous join for an account.
    // Keep each relay's join/hasJoined exchange together, through welcome or failure.
    static synchronized Lease reserve() {
        Lease lease = new Lease(previousProof, 45_000);
        previousProof = lease.finished();
        return lease;
    }

    static final class Lease implements AutoCloseable {
        private final CompletableFuture<Void> ready;
        private final CompletableFuture<Void> released = new CompletableFuture<>();
        private CompletableFuture<Void> registrationFinished = CompletableFuture.completedFuture(null);
        private boolean started;

        Lease(CompletableFuture<Void> ready, long timeoutMs) {
            this.ready = ready;
            // Waiting for another relay must not consume this connection's budget.
            ready.thenRun(() -> released.completeOnTimeout(null, timeoutMs, TimeUnit.MILLISECONDS));
        }

        CompletableFuture<Void> whenReady() {
            return ready.thenApply(ignored -> {
                if (released.isDone()) throw new IllegalStateException("Identity proof expired");
                return null;
            });
        }

        synchronized CompletableFuture<Void> join(HttpClient http, UUID profile, String accessToken, String serverId) {
            if (started) return CompletableFuture.failedFuture(new IllegalStateException("Identity proof already started"));
            started = true;
            var finished = new CompletableFuture<Void>();
            registrationFinished = finished;
            return whenReady().thenCompose(ignored -> {
                if (released.isDone()) return CompletableFuture.failedFuture(new IllegalStateException("Identity proof expired"));
                return RelayIdentityProof.join(http, profile, accessToken, serverId);
            }).whenComplete((ignored, error) -> finished.complete(null)).thenApply(ignored -> {
                if (released.isDone()) throw new IllegalStateException("Identity proof expired");
                return null;
            });
        }

        CompletableFuture<Void> finished() {
            return ready.thenCompose(ignored -> released).thenCompose(ignored -> registrationFinished());
        }

        private synchronized CompletableFuture<Void> registrationFinished() { return registrationFinished; }

        @Override public void close() { released.complete(null); }
    }

    static String clientNonce() {
        byte[] nonce = new byte[20];
        new SecureRandom().nextBytes(nonce);
        return HexFormat.of().formatHex(nonce);
    }

    static String sessionServerId(String challenge, String clientNonce) {
        if (challenge == null || !challenge.matches("[a-f0-9]{40}")
                || clientNonce == null || !clientNonce.matches("[a-f0-9]{40}")) {
            throw new IllegalArgumentException("Invalid Minecraft identity challenge");
        }
        // Never register a raw, relay-selected game-server hash. The domain and
        // fresh client nonce prevent a hostile relay forwarding another server's challenge.
        try {
            byte[] input = ("openintel-relay-auth-v1\0" + challenge + "\0" + clientNonce)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)).substring(0, 40);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static HttpRequest joinRequest(UUID profile, String accessToken, String challenge) {
        if (profile == null || accessToken == null || accessToken.isBlank()
                || challenge == null || !challenge.matches("[a-f0-9]{40}")) {
            throw new IllegalArgumentException("Invalid Minecraft identity challenge");
        }
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", accessToken);
        body.addProperty("selectedProfile", profile.toString().replace("-", ""));
        body.addProperty("serverId", challenge);
        // This endpoint is fixed; a relay cannot choose where account credentials go.
        return HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/join"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }

    static CompletableFuture<Void> join(HttpClient http, UUID profile, String accessToken, String challenge) {
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Minecraft credentials require redirects disabled");
        }
        return http.sendAsync(joinRequest(profile, accessToken, challenge), HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> {
                    if (response.statusCode() != 204) throw new IllegalStateException("Minecraft session proof failed");
                    return null;
                });
    }
}
