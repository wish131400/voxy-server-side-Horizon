package dev.xantha.vss.compat;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Per-player ownership of the optional tkettela stream. Native-only clients keep its transport. */
public final class VoxyServerLodServerCompat {
    private VoxyServerLodServerCompat() { }

    /** Implemented on native PlayerState so late worker completions also see retirement. */
    public interface RetirableState { void vss$retire(); }

    public static boolean owns(ServerPlayer player) {
        return VSSServerConfig.CONFIG.enabled && VSSServerNetworking.isRegistered(player);
    }

    public static void reconcile(MinecraftServer server, Map<UUID, ?> states, Set<UUID> pending,
                                 Set<UUID> suppressed) {
        var players = server.getPlayerList().getPlayers();
        var connected = new HashSet<UUID>();
        var owned = new HashSet<UUID>();
        for (var player : players) {
            connected.add(player.getUUID());
            if (owns(player)) owned.add(player.getUUID());
        }
        reconcile(states, pending, suppressed, connected, owned::contains);
    }

    static void reconcile(Map<UUID, ?> states, Set<UUID> pending, Set<UUID> suppressed,
                          Set<UUID> connected, Predicate<UUID> owned) {
        for (var id : connected) {
            if (owned.test(id)) {
                suppressed.add(id);
                pending.remove(id);
                Object old = states.remove(id);
                if (old instanceof RetirableState state) state.vss$retire();
            } else if (suppressed.remove(id)) {
                // Native channel/config checks still run in tryEnable on the same tick.
                pending.add(id);
            }
        }
        suppressed.retainAll(connected);
    }

    public static void retireAll(Map<UUID, ?> states) {
        for (Object state : states.values())
            if (state instanceof RetirableState retired) retired.vss$retire();
    }

    private static final ClassValue<Method> BUFFER = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) {
            try {
                Method method = type.getDeclaredMethod("buf");
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unsupported native LoD outgoing packet " + type.getName(), e);
            }
        }
    };

    public static void releaseOutgoing(Object outgoing) {
        try { ReferenceCountUtil.release(BUFFER.get(outgoing.getClass()).invoke(outgoing)); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot release retired native LoD packet", e); }
    }
}
