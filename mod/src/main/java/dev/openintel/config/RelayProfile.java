package dev.openintel.config;

import java.util.UUID;

/** Persisted credentials and outbound-sharing policy for one relay connection. */
public final class RelayProfile {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String url = "";
    public String token = "";
    public String minecraftServer = "";
    public boolean enabled = true;
    /** New connections send and receive until sharing is turned off. */
    public boolean share = true;
}
