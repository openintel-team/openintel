package dev.openintel.config;

import java.util.UUID;

/**
 * Persisted connection settings and outbound-sharing policy for one relay.
 * The token is transient: it is stored in {@code openintel/relay-tokens.json}
 * (see {@link DataDir}), never in {@code config/}, so the config can be shared safely.
 */
public final class RelayProfile {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String url = "";
    public transient String token = "";
    public String minecraftServer = "";
    public boolean enabled = true;
    /** New connections send and receive until sharing is turned off. */
    public boolean share = true;
}
