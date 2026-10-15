package dev.openintel.xaero;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans which chest slots to exchange so located relic maps end up closest-first.
 * The slot list itself is not reordered: those indices are the physical slots,
 * and only the distances move when a swap is recorded.
 */
public final class RelicMapOrder {
    private RelicMapOrder() { }

    /**
     * Selection-sort swaps. {@code positions[i]} is the container slot currently
     * holding the map whose distance squared is {@code distanceSq[i]}. Equal
     * distances stay put. Each returned pair is the two slots to exchange.
     */
    public static List<int[]> swaps(int[] positions, double[] distanceSq) {
        if (positions.length != distanceSq.length) {
            throw new IllegalArgumentException("slot and distance counts differ");
        }
        double[] dist = distanceSq.clone();
        List<int[]> swaps = new ArrayList<>();
        for (int i = 0; i < positions.length; i++) {
            int best = i;
            for (int j = i + 1; j < positions.length; j++) {
                if (dist[j] < dist[best]) best = j;
            }
            if (best == i) continue;
            swaps.add(new int[]{positions[i], positions[best]});
            double tmp = dist[i];
            dist[i] = dist[best];
            dist[best] = tmp;
        }
        return swaps;
    }
}
