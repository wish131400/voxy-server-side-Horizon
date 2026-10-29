package dev.xantha.vss.networking.server.request;

import dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard;
import dev.xantha.vss.networking.server.sending.ColumnDeliveryService;

import dev.xantha.vss.networking.server.diagnostics.ServerRequestStats;
import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
import dev.xantha.vss.networking.server.runtime.DiskTaskRuntime;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.NbtSectionSerializer;
import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.networking.server.storage.PersistentColumnWriter;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.DiagnosticCounters;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LoadedColumnData;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 单列请求的缓存、持久化读取与生成回退管线。
 * <p>注入的 registry/guard/delivery 来自同一运行时；异步完成后先复核 epoch、
 * 玩家状态对象和请求有效性，再回到服务端线程交付，禁止自行通过静态门面重找依赖。
 * 成功、失败、取消与拒绝均须结束所属读取许可；交付规则由 ColumnDeliveryService 统一处理。
 */
public final class ColumnStorageReadPipeline {
    private final ServerLifecycleGuard lifecycle;
    private final ColumnDeliveryService delivery;
    private final PlayerRequestRegistry playerRegistry;
    private final ChunkGenerationService generationService;
    private final ColumnLodCache columnCache;
    private final PersistentColumnLodStore persistentStore;
    private final PersistentColumnWriter persistentWriter;
    private final ServerRequestStats requestStats;
    private final DiskTaskRuntime diskRuntime;
    private final DiagnosticCounters generationDiagnostics = new DiagnosticCounters(
            VSSLogger::isDebugEnabled, 5_000_000_000L);

    public ColumnStorageReadPipeline(
            PlayerRequestRegistry playerRegistry,
            ChunkGenerationService generationService,
            ColumnLodCache columnCache,
            PersistentColumnLodStore persistentStore,
            PersistentColumnWriter persistentWriter,
            ServerRequestStats requestStats,
            DiskTaskRuntime diskRuntime,
            ServerLifecycleGuard lifecycle,
            ColumnDeliveryService delivery) {
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle");
        this.delivery = java.util.Objects.requireNonNull(delivery, "delivery");
        this.playerRegistry = playerRegistry;
        this.generationService = generationService;
        this.columnCache = columnCache;
        this.persistentStore = persistentStore;
        this.persistentWriter = persistentWriter;
        this.requestStats = requestStats;
        this.diskRuntime = diskRuntime;
    }

    public boolean submitLoadedColumn(
            ServerPlayer player,
            PlayerRequestState state,
            ServerLevel level,
            LevelChunk chunk,
            int requestId,
            int cx,
            int cz,
            long minimumTimestamp,
            boolean priority) {
        return generationService.submitLoadedColumn(
                player.getUUID(),
                state,
                requestId,
                level,
                chunk,
                cx,
                cz,
                minimumTimestamp,
                priority);
    }

    public void submitStorageRead(
            ServerPlayer player,
            PlayerRequestState state,
            int requestId,
            int cx,
            int cz,
            long columnTimestamp,
            long dirtyTimestamp,
            boolean preferLoadedColumn,
            boolean allowGeneration,
            boolean cacheProbe,
            boolean priority) {
        if (lifecycle.isStopping()) {
            state.clearRequest(requestId);
            return;
        }
        generationDiagnostics.record(cacheProbe ? "storageProbe" : allowGeneration
                ? "storageGenerationAllowed" : "storageSyncOnly");
        logGenerationDiagnostics();
        UUID playerId = player.getUUID();
        ServerLevel level = player.level();
        MinecraftServer server = player.level().getServer();
        DiskReadContext readContext = new DiskReadContext(
                lifecycle.currentEpoch(),
                playerId,
                level,
                state,
                requestId,
                cx,
                cz,
                columnTimestamp,
                preferLoadedColumn,
                allowGeneration,
                cacheProbe,
                priority);
        DiskTaskRuntime.ReadKey readKey = new DiskTaskRuntime.ReadKey(
                level.dimension().identifier(), cx, cz);
        boolean submitted = diskRuntime.submitCoalescedRead(
                readKey,
                false,
                VSSServerConfig.CONFIG.diskReadQueueLimit,
                0,
                () -> readPersistentColumn(server, readContext, dirtyTimestamp),
                storedData -> continueAfterPersistentRead(readContext, storedData, dirtyTimestamp),
                e -> {
                    readContext.requestState().clearRequest(readContext.requestId());
                    sendBackpressured(player, readContext.requestId());
                });
        if (submitted) {
            requestStats.recordDiskReadSubmitted();
        }
    }

    private PersistentColumnLodStore.Entry readPersistentColumn(
            MinecraftServer server,
            DiskReadContext readContext,
            long dirtyTimestamp) {
        try {
            if (lifecycle.isStale(readContext.lifecycleEpoch())) {
                return null;
            }
            ColumnLodCache.Entry cached = columnCache.get(
                    readContext.level().dimension(), readContext.cx(), readContext.cz());
            if (cached != null
                    && cached.isDeliverable()
                    && cached.timestamp() >= dirtyTimestamp) {
                requestStats.recordPreloadReuse();
                return new PersistentColumnLodStore.Entry(cached.columnData());
            }
            PersistentColumnLodStore.Entry storedData = persistentStore.read(
                    server,
                    readContext.level().dimension(),
                    readContext.cx(),
                    readContext.cz(),
                    dirtyTimestamp > 0L ? dirtyTimestamp : 0L);
            if (lifecycle.isStale(readContext.lifecycleEpoch())) {
                return null;
            }
            return storedData;
        } catch (RejectedExecutionException e) {
            throw e;
        } catch (Exception e) {
            VSSLogger.warn("Failed to finish VSS disk read at "
                    + readContext.cx() + ", " + readContext.cz() + ": " + e.getMessage());
            return null;
        }
    }

    private void continueAfterPersistentRead(
            DiskReadContext readContext,
            PersistentColumnLodStore.Entry storedData,
            long dirtyTimestamp) {
        if (readContext.requestState().consumeCancelled(readContext.requestId())
                || !readContext.requestState().isActiveRequest(readContext.requestId())) {
            return;
        }
        ColumnLodCache.Entry cached = columnCache.get(
                readContext.level().dimension(), readContext.cx(), readContext.cz());
        if (cached != null && cached.isDeliverable() && cached.timestamp() >= dirtyTimestamp) {
            scheduleDiskReadFinish(readContext, storedData, DiskNbtReadResult.cached(cached.columnData()));
            return;
        }
        if (storedData != null
                || readContext.preferLoadedColumn()
                || !shouldReadExistingChunkNbt(
                        VSSServerConfig.CONFIG.enableChunkNbtColumnSync,
                        readContext.allowGeneration(),
                        VSSServerConfig.CONFIG.enableChunkGeneration,
                        readContext.cacheProbe())) {
            scheduleDiskReadFinish(readContext, storedData, DiskNbtReadResult.empty());
            return;
        }

        VSSServerConfig config = VSSServerConfig.CONFIG;
        boolean submitted = diskRuntime.<DiskNbtReadResult>submitCoalescedNbtRead(
                new DiskTaskRuntime.ReadKey(
                        readContext.level().dimension().identifier(), readContext.cx(), readContext.cz()),
                config.maxConcurrentNbtReads,
                config.nbtReadQueueLimit,
                config.diskReadQueueLimit,
                () -> !lifecycle.isStale(readContext.lifecycleEpoch())
                        && readContext.requestState().isActiveRequest(readContext.requestId()),
                completion -> startChunkNbtRead(readContext, completion),
                result -> scheduleDiskReadFinish(readContext, storedData, result),
                error -> {
                    readContext.requestState().clearRequest(readContext.requestId());
                    ServerPlayer player = readContext.level().getServer().getPlayerList().getPlayer(readContext.playerId());
                    if (player != null) {
                        sendBackpressured(player, readContext.requestId());
                    }
                });
        if (!submitted) {
            readContext.requestState().clearRequest(readContext.requestId());
        }
    }

    private void startChunkNbtRead(
            DiskReadContext readContext,
            DiskTaskRuntime.AsyncReadCompletion<DiskNbtReadResult> completion) {
        if (lifecycle.isStale(readContext.lifecycleEpoch())
                || !completion.hasActiveListeners()) {
            completeNbtRead(completion, DiskNbtReadResult.empty());
            return;
        }
        try {
            NbtSectionSerializer.readSectionsAsync(
                            readContext.level().getChunkSource().chunkMap,
                            readContext.cx(),
                            readContext.cz(),
                            VSSServerConfig.CONFIG.diskReadTimeoutMillis)
                    .whenComplete((optionalTag, error) -> {
                        if (error != null) {
                            completeNbtRead(
                                    completion,
                                    lifecycle.isStale(readContext.lifecycleEpoch())
                                            ? DiskNbtReadResult.empty()
                                            : DiskNbtReadResult.failure());
                            return;
                        }
                        completion.execute(() -> {
                            try {
                                if (lifecycle.isStale(readContext.lifecycleEpoch())
                                        || !completion.hasActiveListeners()) {
                                    completeNbtRead(completion, DiskNbtReadResult.empty());
                                    return;
                                }
                                LoadedColumnData rawDiskData = NbtSectionSerializer.serializeTag(
                                        readContext.level(),
                                        readContext.cx(),
                                        readContext.cz(),
                                        optionalTag);
                                EncodedColumnData encoded = rawDiskData != null
                                                && rawDiskData.isDeliverable()
                                                && rawDiskData.sectionBytes() != null
                                                && rawDiskData.sizeBytes() > 0
                                        ? EncodedColumnData.encode(rawDiskData, 0L)
                                        : null;
                                completeNbtRead(
                                        completion,
                                        encoded == null
                                                ? DiskNbtReadResult.miss()
                                                : DiskNbtReadResult.hit(encoded));
                            } catch (Exception exception) {
                                VSSLogger.warn("Failed to transcode chunk NBT at "
                                        + readContext.cx() + ", " + readContext.cz() + ": " + exception.getMessage());
                                completeNbtRead(completion, DiskNbtReadResult.failure());
                            }
                        });
                    });
        } catch (Exception exception) {
            completeNbtRead(completion, DiskNbtReadResult.failure());
        }
    }

    private void completeNbtRead(
            DiskTaskRuntime.AsyncReadCompletion<DiskNbtReadResult> completion,
            DiskNbtReadResult result) {
        if (!completion.complete(result)) {
            return;
        }
        if (result.outcome() == NbtReadOutcome.HIT) {
            diskRuntime.recordNbtReadHit();
        } else if (result.outcome() == NbtReadOutcome.MISS) {
            diskRuntime.recordNbtReadMiss();
        } else if (result.outcome() == NbtReadOutcome.FAILED) {
            diskRuntime.recordNbtReadFailure();
        }
    }

    private void scheduleDiskReadFinish(
            DiskReadContext readContext,
            PersistentColumnLodStore.Entry storedData,
            DiskNbtReadResult result) {
        try {
            readContext.level().getServer().execute(() -> finishDiskRead(
                    readContext,
                    storedData,
                    result.columnData(),
                    result.failed()));
        } catch (RejectedExecutionException error) {
            readContext.requestState().clearRequest(readContext.requestId());
        }
    }

    private static boolean shouldReadExistingChunkNbt(boolean allowGeneration) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        return shouldReadExistingChunkNbt(
                config.enableChunkNbtColumnSync,
                allowGeneration,
                config.enableChunkGeneration);
    }

    public static boolean shouldReadExistingChunkNbt(
            boolean enableChunkNbtColumnSync,
            boolean allowGeneration,
            boolean enableChunkGeneration) {
        return enableChunkNbtColumnSync || !allowGeneration || !enableChunkGeneration;
    }

    static boolean shouldReadExistingChunkNbt(
            boolean enableChunkNbtColumnSync,
            boolean allowGeneration,
            boolean enableChunkGeneration,
            boolean cacheProbe) {
        if (cacheProbe) {
            return false;
        }
        return shouldReadExistingChunkNbt(
                enableChunkNbtColumnSync,
                allowGeneration,
                enableChunkGeneration);
    }

    private void finishDiskRead(
            DiskReadContext readContext,
            PersistentColumnLodStore.Entry storedData,
            EncodedColumnData diskData,
            boolean readFailed) {
        if (lifecycle.isStale(readContext.lifecycleEpoch())) {
            return;
        }
        if (readFailed) {
            requestStats.recordDiskReadFailure();
        }
        ServerLevel level = readContext.level();
        PlayerRequestState requestState = readContext.requestState();
        int requestId = readContext.requestId();
        int cx = readContext.cx();
        int cz = readContext.cz();
        long columnTimestamp = readContext.columnTimestamp();
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(readContext.playerId());
        if (player == null || !playerRegistry.isCurrent(readContext.playerId(), requestState)
                || requestState.consumeCancelled(requestId) || !requestState.isActiveRequest(requestId)) {
            return;
        }
        if (!player.level().dimension().equals(level.dimension())) {
            requestState.clearRequest(requestId);
            return;
        }
        if (!delivery.isColumnStillRelevant(player, level.dimension(), cx, cz)) {
            requestState.clearRequest(requestId);
            return;
        }
        long latestDirtyTimestamp = DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), cx, cz);
        long effectiveColumnTimestamp = Math.max(columnTimestamp, latestDirtyTimestamp);
        if (storedData != null
                && storedData.columnData() != null
                && storedData.columnData().isDeliverable()
                && storedData.timestamp() >= latestDirtyTimestamp) {
            sendStoredColumn(readContext, storedData, player, effectiveColumnTimestamp);
            return;
        }
        if (readContext.preferLoadedColumn()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk != null) {
                if (submitLoadedColumn(player, requestState, level, chunk, requestId, cx, cz, effectiveColumnTimestamp, readContext.priority())) {
                    return;
                }
                requestState.clearRequest(requestId);
                sendRateLimited(player, requestId);
                return;
            }
        }
        if (latestDirtyTimestamp > columnTimestamp) {
            handleMissingDiskColumn(readContext, player, effectiveColumnTimestamp);
            return;
        }
        if (diskData == null || !diskData.hasBody() || !diskData.isDeliverable()) {
            handleMissingDiskColumn(readContext, player, effectiveColumnTimestamp);
            return;
        }
        sendDiskColumn(readContext, player, diskData, effectiveColumnTimestamp);
    }

    private void sendStoredColumn(
            DiskReadContext readContext,
            PersistentColumnLodStore.Entry storedData,
            ServerPlayer player,
            long columnTimestamp) {
        ServerLevel level = readContext.level();
        EncodedColumnData columnData = storedData.columnData().withColumnStamp(
                Math.max(storedData.timestamp(), columnTimestamp));
        requestStats.recordDiskReadHit();
        delivery.queueColumn(player, readContext.requestState(), new VoxelColumnS2CPayload(
                readContext.requestId(),
                level.dimension(),
                columnData), readContext.priority());
        columnCache.put(level.dimension(), columnData);
    }

    private void handleMissingDiskColumn(DiskReadContext readContext, ServerPlayer player, long minimumTimestamp) {
        requestStats.recordDiskReadMiss();
        generationDiagnostics.record(readContext.cacheProbe() ? "missProbe"
                : !readContext.allowGeneration() ? "missGenerationNotRequested"
                : !VSSServerConfig.CONFIG.enableChunkGeneration ? "missServerGenerationDisabled"
                : "missSubmitGeneration");
        logGenerationDiagnostics();
        if (!readContext.cacheProbe()
                && readContext.allowGeneration()
                && VSSServerConfig.CONFIG.enableChunkGeneration) {
            submitGeneration(
                    player,
                    readContext.requestState(),
                    readContext.level(),
                    readContext.requestId(),
                    readContext.cx(),
                    readContext.cz(),
                    minimumTimestamp,
                    readContext.priority());
            return;
        }
        readContext.requestState().clearRequest(readContext.requestId());
        sendNotGenerated(player, readContext.requestId());
    }

    private void sendDiskColumn(
            DiskReadContext readContext,
            ServerPlayer player,
            EncodedColumnData diskData,
            long columnTimestamp) {
        ServerLevel level = readContext.level();
        requestStats.recordDiskReadHit();
        EncodedColumnData encodedDiskData = diskData.withColumnStamp(columnTimestamp);
        delivery.queueColumn(player, readContext.requestState(), new VoxelColumnS2CPayload(
                readContext.requestId(),
                level.dimension(),
                encodedDiskData), readContext.priority());
        columnCache.put(level.dimension(), encodedDiskData);
        persistentWriter.write(level.getServer(), level.dimension(), encodedDiskData);
    }

    private void submitGeneration(
            ServerPlayer player,
            PlayerRequestState state,
            ServerLevel level,
            int requestId,
            int cx,
            int cz,
            long minimumTimestamp,
            boolean priority) {
        if (lifecycle.isStopping()) {
            state.clearRequest(requestId);
            return;
        }
        boolean accepted = generationService.submitGeneration(
                player.getUUID(),
                state,
                requestId,
                level,
                cx,
                cz,
                minimumTimestamp,
                priority);
        generationDiagnostics.record(accepted ? "generationAccepted" : "generationRejected");
        if (!accepted) {
            state.clearRequest(requestId);
            sendRateLimited(player, requestId);
            return;
        }
        sendGenerationQueued(player, requestId);
    }

    private void logGenerationDiagnostics() {
        String events = generationDiagnostics.poll(System.nanoTime());
        if (events != null) {
            VSSLogger.debug("VSS generation diagnostic server v1: scope=storagePipelineAllPlayers"
                    + ", generationEnabled=" + VSSServerConfig.CONFIG.enableChunkGeneration
                    + ", " + events);
        }
    }

    private static void sendRateLimited(ServerPlayer player, int requestId) {
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_RATE_LIMITED},
                        new int[] {requestId},
                        1));
    }

    private static void sendBackpressured(ServerPlayer player, int requestId) {
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_BACKPRESSURE},
                        new int[] {requestId},
                        1));
    }

    private static void sendGenerationQueued(ServerPlayer player, int requestId) {
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_GENERATION_QUEUED},
                        new int[] {requestId},
                        1));
    }

    private static void sendNotGenerated(ServerPlayer player, int requestId) {
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_NOT_GENERATED},
                        new int[] {requestId},
                        1));
    }

    private record DiskReadContext(
            long lifecycleEpoch,
            UUID playerId,
            ServerLevel level,
            PlayerRequestState requestState,
            int requestId,
            int cx,
            int cz,
            long columnTimestamp,
            boolean preferLoadedColumn,
            boolean allowGeneration,
            boolean cacheProbe,
            boolean priority) {
    }

    private record DiskNbtReadResult(EncodedColumnData columnData, NbtReadOutcome outcome) {
        static DiskNbtReadResult empty() {
            return new DiskNbtReadResult(null, NbtReadOutcome.CANCELLED);
        }

        static DiskNbtReadResult cached(EncodedColumnData columnData) {
            return new DiskNbtReadResult(columnData, NbtReadOutcome.CANCELLED);
        }

        static DiskNbtReadResult hit(EncodedColumnData columnData) {
            return new DiskNbtReadResult(columnData, NbtReadOutcome.HIT);
        }

        static DiskNbtReadResult miss() {
            return new DiskNbtReadResult(null, NbtReadOutcome.MISS);
        }

        static DiskNbtReadResult failure() {
            return new DiskNbtReadResult(null, NbtReadOutcome.FAILED);
        }

        boolean failed() {
            return outcome == NbtReadOutcome.FAILED;
        }
    }

    private enum NbtReadOutcome {
        HIT,
        MISS,
        FAILED,
        CANCELLED
    }

}
