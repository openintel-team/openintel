package dev.openintel.update;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Asks GitHub once per launch, then speaks on the first multiplayer join. */
public final class UpdateChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger("OpenIntel");
    private static final UpdateSession SESSION = new UpdateSession();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private UpdateChecker() { }

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        String installed = FabricLoader.getInstance().getModContainer("openintel")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
        String minecraft = FabricLoader.getInstance().getModContainer("minecraft")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
        HttpRequest request = HttpRequest.newBuilder(URI.create(ReleaseUpdate.RELEASES_API))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OpenIntel")
                .GET()
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    String newer = null;
                    if (error == null && response != null && response.statusCode() == 200) {
                        newer = ReleaseUpdate.newerVersion(installed, minecraft, response.body());
                    } else {
                        LOGGER.debug("OpenIntel update check did not complete");
                    }
                    String found = newer;
                    Minecraft.getInstance().execute(() -> {
                        SESSION.onChecked(found);
                        say();
                    });
                });
    }

    public static void onMultiplayerJoin() {
        SESSION.onJoin();
        say();
    }

    private static void say() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        String version = SESSION.claim();
        if (version == null) return;
        Component link = Component.literal("GitHub releases").withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(ReleaseUpdate.RELEASES_PAGE)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(ReleaseUpdate.RELEASES_PAGE))));
        client.player.sendSystemMessage(Component.literal("[OpenIntel] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("A new version of OpenIntel is available (" + version + "). ").withStyle(ChatFormatting.GRAY))
                .append(link));
    }
}
