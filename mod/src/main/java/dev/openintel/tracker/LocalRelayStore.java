package dev.openintel.tracker;

import dev.openintel.net.RelaySource;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;

public final class LocalRelayStore<K, V> {
    private final Map<K, V> local = new ConcurrentHashMap<>();
    private final Map<String, Map<K, V>> relay = new ConcurrentHashMap<>();
    private final BinaryOperator<V> preferred;
    private final BinaryOperator<V> relayPreferred;

    public LocalRelayStore(BinaryOperator<V> preferred) { this(preferred, preferred); }
    public LocalRelayStore(BinaryOperator<V> preferred, BinaryOperator<V> relayPreferred) {
        this.preferred = preferred;
        this.relayPreferred = relayPreferred;
    }
    public void putLocal(K key, V value) { local.put(key, value); }
    public void putRelay(K key, V value) { putRelay(RelaySource.current(), key, value); }
    public void putRelay(String source, K key, V value) {
        relay.computeIfAbsent(RelaySource.normalize(source), ignored -> new ConcurrentHashMap<>()).put(key, value);
    }
    public boolean containsKey(K key) {
        return local.containsKey(key) || relay.values().stream().anyMatch(map -> map.containsKey(key));
    }
    public Collection<V> values() {
        var visible = new HashMap<K, V>();
        // Stable source ordering resolves equal-priority conflicts deterministically.
        relay.keySet().stream().sorted().forEach(source -> relay.get(source).forEach(
                (key, value) -> visible.merge(key, value, relayPreferred)));
        local.forEach((key, value) -> visible.merge(key, value, (remote, own) -> preferred.apply(own, remote)));
        return List.copyOf(visible.values());
    }
    public boolean removeIf(Predicate<V> expired) {
        boolean changed = local.values().removeIf(expired);
        for (var map : relay.values()) changed |= map.values().removeIf(expired);
        return changed;
    }
    public boolean removeRelayIf(String source, Predicate<V> predicate) {
        var map = relay.get(RelaySource.normalize(source));
        return map != null && map.values().removeIf(predicate);
    }
    public void clearRelay(String source) { relay.remove(RelaySource.normalize(source)); }
    public void clearRelay() { relay.clear(); }
    public void clear() { local.clear(); relay.clear(); }
}
