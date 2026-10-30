package dev.openintel.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

/** Exercises credential routing and protocol failures without real account credentials. */
public final class RelayIdentityProofTest {
    public static void main(String[] args) throws Exception {
        UUID profile = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        String challenge = "a".repeat(40), nonce = "b".repeat(40);
        String serverId = RelayIdentityProof.sessionServerId(challenge, nonce);
        // Shared with the relay's Node implementation.
        check(serverId.equals("08d5243bfa543aafa1458bb8c609bd4b6c80aa67"), "Cross-language hash agreement");
        check(!serverId.equals(challenge), "Never register a raw relay-provided server hash");
        check(!serverId.equals(RelayIdentityProof.sessionServerId(challenge, RelayIdentityProof.clientNonce())),
                "Fresh client nonce changes proof");
        for (String bad : new String[]{"", "a".repeat(39), "A".repeat(40), "https://attacker.test/", null}) {
            try {
                RelayIdentityProof.sessionServerId(bad, nonce);
                throw new AssertionError("Malformed challenge accepted");
            } catch (IllegalArgumentException expected) { }
        }
        FakeHttp http = new FakeHttp();
        RelayIdentityProof.join(http, profile, "minecraft-fixture-token", serverId).join();
        check(http.request.uri().equals(URI.create("https://sessionserver.mojang.com/session/minecraft/join")),
                "Credentials only go to Mojang's fixed HTTPS endpoint");
        check(http.request.method().equals("POST") && http.request.timeout().orElseThrow().equals(Duration.ofSeconds(15)),
                "Bounded session request");
        JsonObject body = JsonParser.parseString(readBody(http.request)).getAsJsonObject();
        check(body.get("selectedProfile").getAsString().equals("12345678123412341234123456789abc"), "Active account UUID");
        check(body.get("accessToken").getAsString().equals("minecraft-fixture-token"), "Account token is sent only to Mojang");
        check(body.get("serverId").getAsString().equals(serverId), "Register derived challenge");
        for (int status : new int[]{200, 301, 403, 500}) {
            http.status = status;
            try {
                RelayIdentityProof.join(http, profile, "fixture", serverId).join();
                throw new AssertionError("Accepted failed join: " + status);
            } catch (CompletionException expected) { }
        }
        http.redirect = HttpClient.Redirect.ALWAYS;
        try {
            RelayIdentityProof.join(http, profile, "fixture", serverId);
            throw new AssertionError("Credential redirects enabled");
        } catch (IllegalArgumentException expected) { }
        http.redirect = HttpClient.Redirect.NEVER;
        http.status = 204;

        // Parallel relay profiles cannot overwrite each other's Minecraft join.
        var first = RelayIdentityProof.reserve();
        var second = RelayIdentityProof.reserve();
        first.join(http, profile, "fixture", serverId).join();
        int before = http.requests;
        var waiting = second.join(http, profile, "fixture", serverId);
        check(!waiting.isDone() && http.requests == before, "Second proof waits through first relay's welcome");
        first.close();
        waiting.join();
        check(http.requests == before + 1, "Next relay proceeds after acknowledgement");
        second.close();
        var holder = RelayIdentityProof.reserve();
        var cancelled = RelayIdentityProof.reserve();
        var after = RelayIdentityProof.reserve();
        var abandoned = cancelled.join(http, profile, "fixture", serverId);
        cancelled.close();
        var next = after.join(http, profile, "fixture", serverId);
        check(!next.isDone(), "Cancelling queued proof does not bypass active proof");
        holder.close();
        try { abandoned.join(); throw new AssertionError("Cancelled proof registered"); }
        catch (CompletionException expected) { }
        next.join(); after.close();

        // A queued connection gets its full budget only once its turn starts.
        var predecessor = new CompletableFuture<Void>();
        var queued = new RelayIdentityProof.Lease(predecessor, 100);
        Thread.sleep(150);
        check(!queued.whenReady().isDone(), "Queue wait has no authentication deadline");
        predecessor.complete(null);
        queued.join(http, profile, "fixture", serverId).join(); queued.close();

        // Closing or expiring a proof never overlaps its outstanding registration
        // with the next relay's registration, even if the old response arrives late.
        for (boolean expire : new boolean[]{false, true}) {
            var slowHttp = new FakeHttp();
            slowHttp.gate = new CompletableFuture<>();
            var active = new RelayIdentityProof.Lease(CompletableFuture.completedFuture(null), expire ? 10 : 1000);
            var following = new RelayIdentityProof.Lease(active.finished(), 1000);
            var registration = active.join(slowHttp, profile, "fixture", serverId);
            if (expire) Thread.sleep(30); else active.close();
            int requests = http.requests;
            var replacement = following.join(http, profile, "fixture", serverId);
            check(!replacement.isDone() && http.requests == requests, "In-flight registration still owns serialization");
            slowHttp.gate.complete(null);
            try { registration.join(); throw new AssertionError("Abandoned proof succeeded"); }
            catch (CompletionException expected) { }
            replacement.join(); following.close();
        }
        System.out.println("RelayIdentityProofTest passed: credential destination, nonce binding, failures and parallel relay sessions");
    }

    private static String readBody(HttpRequest request) {
        var result = new CompletableFuture<String>();
        var bytes = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part); }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.join();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class FakeHttp extends HttpClient {
        int status = 204, requests;
        HttpRequest request;
        Redirect redirect = Redirect.NEVER;
        CompletableFuture<Void> gate = CompletableFuture.completedFuture(null);
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            this.request = request; requests++;
            return gate.thenApply(ignored -> new Response<>(status, request));
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> push) { return sendAsync(request, handler); }
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new AssertionError("Blocking HTTP"); }
        public Redirect followRedirects() { return redirect; }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
    }
    private record Response<T>(int statusCode, HttpRequest request) implements HttpResponse<T> {
        public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        public T body() { return null; }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
