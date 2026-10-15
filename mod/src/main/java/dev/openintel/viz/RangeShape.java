package dev.openintel.viz;

import java.util.UUID;

/** One manually placed coverage field persisted in openintel-ranges.json. */
public final class RangeShape {
    public String id = UUID.randomUUID().toString();
    public RangeType type = RangeType.SNITCH;
    public int x;
    public int y;
    public int z;
    public String dimension = "minecraft:overworld";
    public boolean enabled = true;
    /** Sanctuary-only radius in blocks; ignored for snitch/bastion. */
    public double sanctuaryRadius = 50;
    /**
     * Logging vs notifier. Missing JSON stays {@link SnitchKind#NOTIFIER}.
     * Ignored for bastion and sanctuary shapes.
     */
    public SnitchKind snitchKind = SnitchKind.NOTIFIER;

    public RangeShape() { }

    public RangeShape(RangeType type, int x, int y, int z, String dimension) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension == null ? "minecraft:overworld" : dimension;
    }

    public String label() {
        return switch (type == null ? RangeType.SNITCH : type) {
            case SNITCH -> (snitchKind == SnitchKind.LOGGING ? "Logging snitch " : "Notifier snitch ")
                    + x + "," + y + "," + z;
            case BASTION -> "Bastion " + x + "," + y + "," + z;
            case SANCTUARY -> "Sanctuary r=" + (int) Math.round(sanctuaryRadius)
                    + " @ " + x + "," + y + "," + z;
        };
    }
}
