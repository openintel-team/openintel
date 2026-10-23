package dev.openintel.config;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Headless migration/normalization assertions; never writes credentials to disk. */
public final class RelayProfilesTest {
    public static void main(String[] args) {
        Gson gson = new Gson();
        OIConfig legacy = gson.fromJson("{\"relayUrl\":\"wss://example.invalid\",\"token\":\"private\","
                + "\"minecraftServer\":\"play.example.invalid\"}", OIConfig.class);
        RelayProfile migrated = legacy.relayProfiles().getFirst();
        assert migrated.url.equals(legacy.relayUrl);
        assert migrated.token.equals(legacy.token);
        assert migrated.minecraftServer.equals(legacy.minecraftServer);
        assert migrated.enabled && migrated.share;
        String id = migrated.id;
        assert UUID.fromString(id).toString().equals(id);
        assert legacy.relayProfiles().getFirst().id.equals(id);

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
        System.out.println("RelayProfilesTest passed");
    }
}
