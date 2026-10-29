package dev.xantha.vss.compat;

import dev.xantha.vss.api.VSSApi;
import dev.xantha.vss.api.VoxelColumnData;
import dev.xantha.vss.client.prediction.ClientPredictionState;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** tkettela's native server stream remains usable when no VSS stream owns the connection. */
public final class VoxyServerLodClientCompat {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static volatile MethodHandle nativeRawIngest;
    private static volatile boolean nativeTracking;

    private VoxyServerLodClientCompat() { }

    public static boolean pauseNativeReceiver() {
        // Prediction can be off while VSS still owns authoritative server columns.
        return VSSApi.clientSession().isClientLodSessionActive();
    }

    public static Object beginNativeColumn(Minecraft client, Object handler, FriendlyByteBuf packet) {
        Object session = nativeSession();
        return beginNativeColumn(client, handler, packet, session, VoxyServerLodClientCompat::nativeSession);
    }

    static Object beginNativeColumn(Minecraft client, Object handler, FriendlyByteBuf packet,
                                    Object session, Supplier<Object> currentSession) {
        Capture capture = null;
        ClientLevel level = client.level;
        if (!pauseNativeReceiver() && session != null && level != null && client.getConnection() == handler) {
            // The native receiver owns the packet. A non-retained duplicate does not move its reader index.
            FriendlyByteBuf header = new FriendlyByteBuf(packet.duplicate());
            try {
                var dimension = header.readResourceLocation();
                long position = header.readLong();
                int status = header.readUnsignedByte();
                long timestamp = header.readLong();
                if ((status == 0 || status == 3) && level.dimension().location().equals(dimension)) {
                    int minimum = header.readVarInt(), count = header.readVarInt();
                    // The verified native protocol sends every section, including empty sections.
                    if (count > 0 && count <= 256 && minimum == level.getMinSection()
                            && count == level.getSectionsCount()) {
                        capture = new Capture(client, handler, level, session, currentSession,
                                ChunkPos.getX(position), ChunkPos.getZ(position), minimum, count, timestamp);
                    }
                }
            } catch (RuntimeException malformed) {
                // Native decode retains its original error/reporting path; malformed input gains no authority.
            }
        }
        Scope scope = new Scope(CURRENT.get(), capture, () -> level != null && session != null
                && client.level == level && client.getConnection() == handler && currentSession.get() == session);
        CURRENT.set(scope);
        return scope;
    }

    public static boolean ingestNativeSection(Object engine, LevelChunkSection section,
            int x, int sectionY, int z, DataLayer block, DataLayer sky) throws Throwable {
        MethodHandle raw = nativeRawIngest;
        if (raw == null) {
            synchronized (VoxyServerLodClientCompat.class) {
                raw = nativeRawIngest;
                if (raw == null) {
                    Class<?> ingest = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
                    Class<?> world = Class.forName("me.cortex.voxy.common.world.WorldEngine");
                    raw = MethodHandles.publicLookup().findStatic(ingest, "rawIngest", MethodType.methodType(
                            boolean.class, world, LevelChunkSection.class, int.class, int.class, int.class,
                            DataLayer.class, DataLayer.class)).asType(MethodType.methodType(boolean.class,
                            Object.class, LevelChunkSection.class, int.class, int.class, int.class,
                            DataLayer.class, DataLayer.class));
                    nativeTracking = VoxyCompat.hasProcessJobCompletionHook(ingest);
                    nativeRawIngest = raw;
                }
            }
        }
        MethodHandle target = raw;
        return ingestNativeSection(engine, section, x, sectionY, z, block, sky, nativeTracking,
                () -> (boolean) target.invokeExact(engine, section, x, sectionY, z, block, sky));
    }

    static boolean ingestNativeSection(Object engine, LevelChunkSection section,
            int x, int sectionY, int z, DataLayer block, DataLayer sky, boolean tracking,
            VoxyIngestControl.ThrowingBooleanSupplier action) throws Throwable {
        Scope scope = CURRENT.get();
        Capture capture = scope == null ? null : scope.capture;
        if (pauseNativeReceiver()) {
            if (capture != null) capture.reject();
            return false;
        }
        if (scope != null && !scope.current.getAsBoolean()) {
            if (capture != null) capture.reject();
            return false;
        }
        if (capture != null && !capture.offer(engine, sectionY, x, z, section, block, sky)) return false;
        try {
            boolean accepted = VoxyIngestControl.runServerIngest(() -> tracking
                    ? VoxyIngestTracker.runRawIngest(engine, section, x, z,
                        capture == null ? null : capture::settled, action)
                    : action.getAsBoolean());
            if (capture != null && !tracking) capture.untracked(accepted);
            if (capture != null && !accepted) capture.reject();
            return accepted;
        } catch (Throwable failure) {
            if (capture != null && !tracking) capture.untracked(false);
            if (capture != null) capture.reject();
            throw failure;
        }
    }

    public static void finishNativeColumn(Object token, boolean completed) {
        if (!(token instanceof Scope scope)) return;
        try {
            if (scope.capture != null) scope.capture.finish(completed);
        } finally {
            if (CURRENT.get() == scope) {
                if (scope.previous == null) CURRENT.remove(); else CURRENT.set(scope.previous);
            }
        }
    }

    private static Object nativeSession() {
        try {
            Class<?> receiver = Class.forName("me.cortex.voxy.client.network.ClientLodReceiver", false,
                    VoxyServerLodClientCompat.class.getClassLoader());
            var field = receiver.getDeclaredField("session");
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private static boolean nativeColumnVersionCurrent(Object session, int x, int z, long timestamp) {
        try {
            var field = session.getClass().getDeclaredField("timestamps");
            field.setAccessible(true);
            if (!(field.get(session) instanceof Long2LongOpenHashMap timestamps)) return false;
            long position = ChunkPos.asLong(x, z);
            synchronized (timestamps) {
                return timestamps.containsKey(position) && timestamps.get(position) <= timestamp;
            }
        } catch (ReflectiveOperationException unavailable) {
            // Native ingestion still owns its data, but unavailable metadata proves no VSS authority.
            return false;
        }
    }

    private static void retryNativeColumn(Object session, int x, int z, long failedTimestamp) {
        // The native timestamp is updated at enqueue time. Failed conversion must request data again,
        // rather than accepting UNCHANGED forever. This runs only on the owning client thread.
        try {
            Class<?> type = session.getClass();
            var timestampsField = type.getDeclaredField("timestamps");
            var requestedField = type.getDeclaredField("requested");
            var spiralField = type.getDeclaredField("spiralStarted");
            var dirtyField = type.getDeclaredField("dirty");
            timestampsField.setAccessible(true); requestedField.setAccessible(true);
            spiralField.setAccessible(true); dirtyField.setAccessible(true);
            long position = ChunkPos.asLong(x, z);
            var timestamps = (Long2LongOpenHashMap) timestampsField.get(session);
            synchronized (timestamps) {
                // A late failed worker cannot erase a newer successful server push.
                if (timestamps.containsKey(position) && timestamps.get(position) > failedTimestamp) return;
                timestamps.remove(position);
            }
            ((LongOpenHashSet) requestedField.get(session)).remove(position);
            spiralField.setBoolean(session, false);
            dirtyField.setBoolean(session, true);
        } catch (ReflectiveOperationException | ClassCastException unavailable) {
            // This helper is selected only for the audited native receiver contract.
        }
    }

    private record Scope(Scope previous, Capture capture, BooleanSupplier current) { }

    private static final class Capture {
        final Minecraft client;
        final Object handler, session;
        final ClientLevel level;
        final Supplier<Object> currentSession;
        final int x, z, minimum;
        final long timestamp;
        final long ingestGeneration = VoxyIngestTracker.generation();
        final VoxelColumnData.SectionData[] sections;
        Object engine;
        int offered, completed;
        boolean failed, finished, scheduled, verified = true;

        Capture(Minecraft client, Object handler, ClientLevel level, Object session,
                Supplier<Object> currentSession, int x, int z, int minimum, int count, long timestamp) {
            this.client = client; this.handler = handler; this.level = level; this.session = session;
            this.currentSession = currentSession; this.x = x; this.z = z; this.minimum = minimum;
            this.timestamp = timestamp; sections = new VoxelColumnData.SectionData[count];
        }

        boolean current() {
            return !pauseNativeReceiver() && client.level == level && client.getConnection() == handler
                    && currentSession.get() == session;
        }

        synchronized boolean offer(Object world, int y, int cx, int cz, LevelChunkSection section,
                DataLayer block, DataLayer sky) {
            Object liveEngine = VoxyCompat.engineForLevel(level);
            if (!current() || world == null || (liveEngine != null && liveEngine != world)
                    || (engine != null && engine != world) || cx != x || cz != z
                    || offered >= sections.length || y != minimum + offered) {
                failed = true; return false;
            }
            engine = world;
            if (liveEngine == null) verified = false;
            // These sections are owned by the network decoder; native conversion only reads them.
            sections[offered++] = new VoxelColumnData.SectionData(y, section, block, sky);
            return true;
        }

        synchronized void reject() { failed = true; }

        void untracked(boolean accepted) {
            synchronized (this) { verified = false; }
            settled(accepted);
        }

        void settled(boolean successful) {
            synchronized (this) { completed++; failed |= !successful; }
            publishWhenComplete();
        }

        void finish(boolean successful) {
            synchronized (this) { finished = true; failed |= !successful || offered != sections.length; }
            publishWhenComplete();
        }

        void publishWhenComplete() {
            boolean retry;
            synchronized (this) {
                if (scheduled || !finished || completed != offered) return;
                retry = failed || offered != sections.length;
                scheduled = true;
                // Without the audited worker hook the native stream still owns its normal data path.
                // Successful enqueue alone cannot grant VSS authority, and must not create a retry storm.
                if (!retry && !verified) return;
            }
            client.execute(() -> {
                if (!current()) return;
                if (retry || ingestGeneration != VoxyIngestTracker.generation()) {
                    retryNativeColumn(session, x, z, timestamp); return;
                }
                if (engine == null || VoxyCompat.engineForLevel(level) != engine) return;
                // Different sections can finish on different native workers. A completed
                // older column must not replace a later server push in the exact snapshot.
                if (!nativeColumnVersionCurrent(session, x, z, timestamp)) return;
                VoxelColumnData data = new VoxelColumnData(sections, timestamp, true);
                if (ClientPredictionState.onExactColumn(level.dimension(), x, z, data)) {
                    VoxyCompat.confirmNativeColumn(level, engine, x, z);
                } else retryNativeColumn(session, x, z, timestamp);
            });
        }
    }
}
