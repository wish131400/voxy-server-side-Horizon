package dev.xantha.vss.compat.ftbchunks;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSServerConfig;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Runs FTB's own platform implementation, retaining its saved state and ticket semantics. */
public final class FTBChunksForceLoadCompat {
    private static final ForceLoadQueue<ServerLevel> PENDING = new ForceLoadQueue<>();
    private static final ThreadLocal<ForceLoadQueue.Key<ServerLevel>> APPLYING = new ThreadLocal<>();
    private static Method legacyMethod;
    private static boolean legacyUnavailable;
    private static boolean registered;

    private FTBChunksForceLoadCompat() { }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(FTBChunksForceLoadCompat::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(FTBChunksForceLoadCompat::onServerStopping);
    }

    /** Entry used by the 2001/2101/2111 Fabric platform bridge. */
    public static boolean tryHandle(ServerLevel level, String modId, UUID owner, int x, int z, boolean add) {
        if (isReplaying(level, modId, owner, x, z)) return false;
        Method method = legacyMethod();
        return method != null && submit(level, modId, owner, x, z, add,
                () -> invokeLegacy(method, level, modId, owner, x, z, add));
    }

    /** 26.x supplies the vanilla forced ticket implementation as a platform lambda. */
    public static boolean tryHandleVanilla(ServerLevel level, UUID owner, int x, int z, boolean add) {
        return submit(level, "ftbchunks", owner, x, z, add, () -> level.setChunkForced(x, z, add));
    }

    private static boolean submit(ServerLevel level, String modId, UUID owner, int x, int z,
                                  boolean add, Runnable original) {
        if (level == null) return false;
        var key = new ForceLoadQueue.Key<>(level, modId, owner, x, z);
        // A new request supersedes one queued before the setting changed too.
        if (!add || !VSSServerConfig.CONFIG.ftbChunksSafeForceLoad) {
            PENDING.removeColumn(level, modId, x, z);
        } else {
            PENDING.remove(key);
        }
        if (!VSSServerConfig.CONFIG.ftbChunksSafeForceLoad) return false;
        Runnable scoped = () -> runScoped(key, original);
        if (!add && level.getServer().isSameThread()) {
            scoped.run();
        } else {
            PENDING.put(key, scoped);
        }
        return true;
    }

    static void runScoped(ForceLoadQueue.Key<ServerLevel> key, Runnable original) {
        var previous = APPLYING.get();
        APPLYING.set(key);
        try { original.run(); }
        finally {
            if (previous == null) APPLYING.remove();
            else APPLYING.set(previous);
        }
    }

    private static boolean isReplaying(ServerLevel level, String modId, UUID owner, int x, int z) {
        var applying = APPLYING.get();
        return applying != null && applying.equals(new ForceLoadQueue.Key<>(level, modId, owner, x, z));
    }

    /** The getChunk result is discarded by vanilla setChunkForced; the saved ticket still loads it. */
    public static boolean skipSynchronousChunkRead(ServerLevel level, int x, int z) {
        var applying = APPLYING.get();
        return applying != null && applying.level() == level && applying.x() == x && applying.z() == z;
    }

    static void onServerTick(MinecraftServer server) {
        // Drain pending requests even after the option is disabled; don't drop accepted force-loads.
        PENDING.drain(level -> level.getServer() == server,
                VSSServerConfig.CONFIG.ftbChunksForceLoadTicketsPerTick);
    }

    static void onServerStopping(MinecraftServer server) {
        // FTB persists claims/owners itself and restores them on the next server start.
        PENDING.clear(level -> level.getServer() == server);
    }

    private static synchronized Method legacyMethod() {
        if (legacyMethod != null || legacyUnavailable) return legacyMethod;
        try {
            legacyMethod = Class.forName("dev.ftb.mods.ftbchunks.fabric.FTBChunksExpectedImpl")
                    .getMethod("addChunkToForceLoaded", ServerLevel.class, String.class, UUID.class,
                            int.class, int.class, boolean.class);
        } catch (ReflectiveOperationException failure) {
            legacyUnavailable = true;
            VSSLogger.warn("FTB Chunks force-load bridge is unavailable; retaining its platform implementation", failure);
        }
        return legacyMethod;
    }

    private static void invokeLegacy(Method method, ServerLevel level, String modId, UUID owner,
                                     int x, int z, boolean add) {
        try {
            method.invoke(null, level, modId, owner, x, z, add);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("FTB Chunks platform force-load failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FTB Chunks platform force-load failed", failure);
        }
    }
}
