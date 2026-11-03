package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Coalesce repeated capture refreshes while keeping resident coverage visible. Manager lock owns this state. */
final class PredictionCaptureRefresh {
    static final long QUIET_NANOS = 100_000_000L, MAX_NANOS = 500_000_000L;
    private record Burst(long first, long latest, boolean repeated) { }
    private final Map<PredictionTileKey, Burst> bursts = new HashMap<>();
    void changed(PredictionTileKey key, long now) {
        Burst old = bursts.get(key);
        bursts.put(key, old == null ? new Burst(now, now, false) : new Burst(old.first(), now, true));
    }
    boolean defer(PredictionTileKey key, long now) {
        Burst burst = bursts.get(key);
        return burst != null && burst.repeated() && now - burst.latest() < QUIET_NANOS
                && now - burst.first() < MAX_NANOS;
    }
    void attempted(PredictionTileKey key, long now) {
        Burst burst = bursts.get(key);
        // The maximum delay grants one attempt, not an unlimited bypass for
        // every subsequent capture if that attempt is superseded again.
        if (burst != null && now - burst.first() >= MAX_NANOS)
            bursts.put(key, new Burst(now, burst.latest(), burst.repeated()));
    }
    void completed(PredictionTileKey key) { bursts.remove(key); }
    void retain(Set<PredictionTileKey> keys) { bursts.keySet().retainAll(keys); }
    void clear() { bursts.clear(); }
}
