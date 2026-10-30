package dev.xantha.vss.compat.ftbchunks;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** Latest request per owner/column, with a single budget shared by all dimensions. */
final class ForceLoadQueue<L> {
    record Key<L>(L level, String modId, UUID owner, int x, int z) {
        @Override public boolean equals(Object value) {
            return value instanceof Key<?> other && level == other.level && x == other.x && z == other.z
                    && Objects.equals(modId, other.modId) && Objects.equals(owner, other.owner);
        }
        @Override public int hashCode() {
            return Objects.hash(System.identityHashCode(level), modId, owner, x, z);
        }
    }

    private final Map<Key<L>, Runnable> pending = new LinkedHashMap<>();

    synchronized void put(Key<L> key, Runnable action) { pending.put(key, action); }
    synchronized void remove(Key<L> key) { pending.remove(key); }
    synchronized void removeColumn(L level, String modId, int x, int z) {
        // Fabric's upstream bridge maps owners to the same vanilla forced ticket.
        // A later release must cancel earlier adds even across a team ownership change.
        pending.keySet().removeIf(key -> key.level() == level && Objects.equals(key.modId(), modId)
                && key.x() == x && key.z() == z);
    }
    synchronized int size() { return pending.size(); }

    void drain(Predicate<L> belongsToServer, int budget) {
        for (int i = 0; i < Math.max(1, budget); i++) {
            Runnable action = poll(belongsToServer);
            if (action == null) return;
            action.run();
        }
    }

    private synchronized Runnable poll(Predicate<L> belongsToServer) {
        Iterator<Map.Entry<Key<L>, Runnable>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var next = it.next();
            if (!belongsToServer.test(next.getKey().level())) continue;
            it.remove();
            return next.getValue();
        }
        return null;
    }

    synchronized void clear(Predicate<L> belongsToServer) {
        pending.keySet().removeIf(key -> belongsToServer.test(key.level()));
    }
}
