package dev.openintel.viz;

import java.util.UUID;

/** A named visibility layer; entries retain their individual visibility flags. */
public final class RangeGroup {
    public String id = UUID.randomUUID().toString();
    public String name = "Group";
    public boolean enabled = true;

    public RangeGroup() { }
    public RangeGroup(String name) { this.name = name; }
}
