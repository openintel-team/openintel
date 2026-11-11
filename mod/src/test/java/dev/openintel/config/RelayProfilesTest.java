package dev.openintel.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Headless migration/normalization assertions; never writes credentials to disk. */
public final class RelayProfilesTest {
    public static void main(String[] args) throws Exception {
        Gson gson = new Gson();
        String legacyJson = "{\"relayUrl\":\"wss://example.invalid\",\"token\":\"private\","
                + "\"minecraftServer\":\"play.example.invalid\"}";
        OIConfig legacy = gson.fromJson(legacyJson, OIConfig.class);
        // Tokens are never read from or written to openintel.json; an older config's
        // token is restored from the raw JSON and flagged for removal from that file.
        assert !"private".equals(legacy.token) : "token must not be deserialized from the main config";
        assert OIConfig.restoreTokens(legacy, gson.fromJson(legacyJson, JsonObject.class), new OIConfig.StoredTokens())
                : "plaintext token in the main config must be flagged";
        assert legacy.token.equals("private");
        RelayProfile migrated = legacy.relayProfiles().getFirst();
        assert migrated.url.equals(legacy.relayUrl);
        assert migrated.token.equals(legacy.token);
        assert migrated.minecraftServer.equals(legacy.minecraftServer);
        assert migrated.enabled && migrated.share;
        String id = migrated.id;
        assert UUID.fromString(id).toString().equals(id);
        assert legacy.relayProfiles().getFirst().id.equals(id);

        assert !gson.toJson(legacy).contains("private") : "main config JSON must not contain the token";
        assert legacy.tokensJson().contains("private");
        OIConfig restored = gson.fromJson(gson.toJson(legacy), OIConfig.class);
        assert restored.relayProfiles().size() == 1;
        assert restored.relayProfiles().getFirst().id.equals(id);
        restored.relays.clear();
        assert restored.relayProfiles().isEmpty() : "removed legacy profile resurrected";
        assert gson.fromJson(gson.toJson(restored), OIConfig.class).relayProfiles().isEmpty();

        RelayProfile added = new RelayProfile();
        assert added.enabled && added.share : "New relays send and receive by default";
        added.share = false;
        added.enabled = false;
        added.name = "Receive";
        restored.relays.add(added);
        RelayProfile reloaded = gson.fromJson(gson.toJson(restored), OIConfig.class).relayProfiles().getFirst();
        assert !reloaded.share && !reloaded.enabled;
        assert reloaded.name.equals("Receive") && reloaded.id.equals(added.id);

        RelayProfile damaged = new RelayProfile();
        damaged.id = id;
        damaged.name = damaged.url = damaged.token = damaged.minecraftServer = null;
        restored.relays = new ArrayList<>();
        restored.relays.add(null);
        restored.relays.add(migrated);
        restored.relays.add(damaged);
        List<RelayProfile> repaired = restored.relayProfiles();
        assert repaired.size() == 2;
        assert repaired.getFirst().id.equals(id);
        assert !damaged.id.equals(id);
        String repairedId = damaged.id;
        assert UUID.fromString(repairedId).toString().equals(repairedId);
        assert damaged.name.isEmpty() && damaged.url.isEmpty();
        assert damaged.token.isEmpty() && damaged.minecraftServer.isEmpty();
        restored.relayProfiles();
        assert damaged.id.equals(repairedId);
        damaged.id = "broken";
        restored.relayProfiles();
        assert !damaged.id.equals("broken");
        tokenStorage(gson);
        tokenFiles();
        System.out.println("RelayProfilesTest passed");
    }
    /** Tokens live in their own file, keyed by relay id; the main config stays shareable. */
    private static void tokenStorage(Gson gson) {
        OIConfig config = new OIConfig();
        RelayProfile a = profile("a", "alpha-secret");
        RelayProfile b = profile("b", "beta-secret");
        RelayProfile none = profile("c", "");
        config.relays = new ArrayList<>(List.of(a, b, none));
        config.token = "legacy-secret";

        String shared = gson.toJson(config);
        assert !shared.contains("secret") : "shared config must hold no token";
        String tokens = config.tokensJson();
        assert tokens.contains("alpha-secret") && tokens.contains("beta-secret") && tokens.contains("legacy-secret");
        assert !tokens.contains(none.id) : "blank tokens are not stored";

        // Someone else's shared config plus my token file: tokens come back by relay id.
        OIConfig loaded = gson.fromJson(shared, OIConfig.class);
        boolean leftover = OIConfig.restoreTokens(loaded, gson.fromJson(shared, JsonObject.class),
                OIConfig.parseTokens(tokens));
        assert !leftover : "nothing to strip from a config without tokens";
        assert loaded.token.equals("legacy-secret");
        assert loaded.relayProfiles().get(0).token.equals("alpha-secret");
        assert loaded.relayProfiles().get(1).token.equals("beta-secret");
        assert loaded.relayProfiles().get(2).token.isEmpty();

        // The shared config on a machine with no token file: relays load without tokens.
        OIConfig stranger = gson.fromJson(shared, OIConfig.class);
        OIConfig.restoreTokens(stranger, gson.fromJson(shared, JsonObject.class), new OIConfig.StoredTokens());
        assert stranger.relayProfiles().size() == 3;
        assert stranger.relayProfiles().stream().allMatch(p -> p.token.isEmpty());
        assert !stranger.token.equals("legacy-secret");

        // An older config with tokens inline migrates, but the token file wins on conflict.
        String old = "{\"relays\":[{\"id\":\"" + a.id + "\",\"url\":\"wss://x.invalid\",\"token\":\"old-a\"},"
                + "{\"id\":\"" + b.id + "\",\"url\":\"wss://y.invalid\",\"token\":\"old-b\"}]}";
        OIConfig migrated = gson.fromJson(old, OIConfig.class);
        OIConfig.StoredTokens stored = new OIConfig.StoredTokens();
        stored.relays.put(a.id, "file-a");
        assert OIConfig.restoreTokens(migrated, gson.fromJson(old, JsonObject.class), stored)
                : "inline relay tokens must be flagged for removal";
        assert migrated.relays.get(0).token.equals("file-a") : "token file wins";
        assert migrated.relays.get(1).token.equals("old-b") : "inline token is the fallback";
        assert !gson.toJson(migrated).contains("old-") && !gson.toJson(migrated).contains("file-a");

        // Damaged token files are ignored.
        assert OIConfig.parseTokens("not json").relays.isEmpty();
        assert OIConfig.parseTokens("").relays.isEmpty();
        assert OIConfig.parseTokens("{\"relays\":null}").relays.isEmpty();
    }

    private static RelayProfile profile(String name, String token) {
        RelayProfile profile = new RelayProfile();
        profile.name = name;
        profile.url = "wss://" + name + ".invalid";
        profile.token = token;
        return profile;
    }

    /** The real load/save path against a scratch config directory. */
    private static void tokenFiles() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("openintel-tokens");
        java.nio.file.Path config = java.nio.file.Files.createDirectory(dir.resolve("config"));
        try {
            OIConfig.useConfigDirForTest(config);
            DataDir.useForTest(dir.resolve("openintel"), config);
            java.nio.file.Path main = config.resolve("openintel.json");
            java.nio.file.Path tokens = DataDir.file(DataDir.TOKENS);

            // An older config with the token inline: load moves it to the token file.
            String id = UUID.randomUUID().toString();
            java.nio.file.Files.writeString(main, "{\"token\":\"legacy-secret\",\"relays\":[{\"id\":\"" + id
                    + "\",\"name\":\"Main\",\"url\":\"wss://r.invalid\",\"token\":\"relay-secret\"}]}");
            OIConfig loaded = OIConfig.load();
            assert loaded.relayProfiles().getFirst().token.equals("relay-secret");
            assert loaded.token.equals("legacy-secret");
            String mainText = java.nio.file.Files.readString(main);
            assert !mainText.contains("secret") && !mainText.contains("\"token\"") : "token left in openintel.json";
            assert mainText.contains(id) : "relay profile must stay in the main config";
            String tokenText = java.nio.file.Files.readString(tokens);
            assert tokenText.contains("relay-secret") && tokenText.contains("legacy-secret") && tokenText.contains(id);

            // A second load, and a save after editing a token, round-trips through the new file only.
            OIConfig again = OIConfig.load();
            assert again.relayProfiles().getFirst().token.equals("relay-secret");
            again.relayProfiles().getFirst().token = "rotated-secret";
            again.save();
            assert !java.nio.file.Files.readString(main).contains("secret");
            assert OIConfig.load().relayProfiles().getFirst().token.equals("rotated-secret");

            // Sharing openintel.json alone: a machine without the token file gets no token.
            java.nio.file.Files.delete(tokens);
            OIConfig shared = OIConfig.load();
            assert shared.relayProfiles().getFirst().token.isEmpty();
            assert shared.relayProfiles().getFirst().url.equals("wss://r.invalid");
            assert !shared.token.equals("legacy-secret");

            // Tokens are never written beside the settings, only in the data folder.
            try (var names = java.nio.file.Files.list(config)) {
                assert names.noneMatch(p -> p.getFileName().toString().contains("token"))
                        : "a token file appeared in the config folder";
            }
        } finally {
            OIConfig.useConfigDirForTest(null);
            DataDir.useForTest(null, null);
            try (var walk = java.nio.file.Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
