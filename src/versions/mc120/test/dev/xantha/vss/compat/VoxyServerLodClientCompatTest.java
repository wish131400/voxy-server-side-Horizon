package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.api.ClientLodSession;
import dev.xantha.vss.api.VSSApi;
import dev.xantha.vss.api.VoxelColumnData;
import dev.xantha.vss.client.prediction.ClientPredictionState;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class VoxyServerLodClientCompatTest {
    @BeforeAll static void config() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        System.setProperty("vss.configDir", java.nio.file.Files.createTempDirectory("vss-native-stream").toString());
    }

    @Test void completeNativeColumnCapturesEverySectionIncludingAirOnlyAfterTheLastWorker() throws Throwable {
        try (Rig rig = new Rig()) {
            Object scope = rig.begin(0, -4, 2);
            Object first = rig.section(-4), second = rig.section(-3);
            VoxyServerLodClientCompat.finishNativeColumn(scope, true);
            assertTrue(rig.clientQueue.isEmpty());
            rig.finish(first);
            assertTrue(rig.clientQueue.isEmpty());
            rig.finish(second);
            assertEquals(1, rig.clientQueue.size());
            rig.runClient();
            rig.prediction.verify(() -> ClientPredictionState.onExactColumn(eq(Level.OVERWORLD), eq(10), eq(0),
                    argThat(data -> data.authoritative() && data.replaceMissingSections()
                            && data.completesRequest() && data.sections().length == 2
                            && data.sections()[0].sectionY() == -4 && data.sections()[1].sectionY() == -3)));
            rig.compat.verify(() -> VoxyCompat.confirmNativeColumn(rig.level, rig.engine, 10, 0));
        }
    }

    @Test void immediateWorkerCompletionCannotPublishBeforeTheWholePacketReturns() throws Throwable {
        try (Rig rig = new Rig()) {
            Object scope = rig.begin(3, -4, 2);
            rig.finish(rig.section(-4));
            rig.finish(rig.section(-3));
            assertTrue(rig.clientQueue.isEmpty());
            VoxyServerLodClientCompat.finishNativeColumn(scope, true);
            assertEquals(1, rig.clientQueue.size());
            rig.runClient();
            rig.compat.verify(() -> VoxyCompat.confirmNativeColumn(rig.level, rig.engine, 10, 0));
        }
    }

    @Test void olderColumnFinishingAfterANewerPushCannotReplaceItsExactSnapshot() throws Throwable {
        try (Rig rig = new Rig()) {
            Object older = rig.begin(0, -4, 2);
            Object olderFirst = rig.section(-4), olderSecond = rig.section(-3);
            VoxyServerLodClientCompat.finishNativeColumn(older, true);
            NativeSession session = (NativeSession) rig.session.get();
            session.timestamps.put(ChunkPos.asLong(10, 0), 1235L);
            Object newer = rig.begin(3, -4, 2, 1235L);
            Object newerFirst = rig.section(-4), newerSecond = rig.section(-3);
            VoxyServerLodClientCompat.finishNativeColumn(newer, true);
            rig.finish(newerFirst); rig.finish(newerSecond);
            rig.finish(olderFirst); rig.finish(olderSecond);
            assertEquals(2, rig.clientQueue.size(), "Exercise successful native receipts in reverse version order");
            rig.runClient();
            rig.prediction.verify(() -> ClientPredictionState.onExactColumn(eq(Level.OVERWORLD), eq(10), eq(0),
                    argThat(data -> data.columnTimestamp() == 1235L)), times(1));
            rig.prediction.verifyNoMoreInteractions();
            rig.compat.verify(() -> VoxyCompat.confirmNativeColumn(rig.level, rig.engine, 10, 0), times(1));
            assertEquals(1235L, session.timestamps.get(ChunkPos.asLong(10, 0)));
        }
    }

    @Test void unavailableNativeVersionMetadataCannotGrantAuthority() throws Throwable {
        try (Rig rig = new Rig()) {
            rig.session.set(new Object());
            Object scope = rig.begin(0, -4, 2);
            Object first = rig.section(-4), second = rig.section(-3);
            VoxyServerLodClientCompat.finishNativeColumn(scope, true);
            rig.finish(first); rig.finish(second);
            rig.runClient();
            rig.prediction.verifyNoInteractions();
            rig.compat.verify(() -> VoxyCompat.confirmNativeColumn(any(), any(), anyInt(), anyInt()), never());
        }
    }

    @Test void partialDecodeRejectedNativeWorkAndQueueDropsNeverConfirmMissingAir() throws Throwable {
        for (int scenario = 0; scenario < 4; scenario++) {
            try (Rig rig = new Rig()) {
                Object scope = rig.begin(0, -4, 2);
                Object first = rig.section(-4);
                if (scenario != 0) {
                    Object second = rig.section(-3);
                    if (scenario == 1) VoxyIngestTracker.discarded(second);
                    else if (scenario == 2) {
                        Object worker = VoxyIngestTracker.enterWorker();
                        VoxyIngestTracker.workerTask(second);
                        VoxyIngestTracker.workerFailed(worker, new IllegalStateException("native conversion failed"));
                    } else rig.finish(second);
                }
                rig.finish(first);
                VoxyServerLodClientCompat.finishNativeColumn(scope, scenario != 3);
                rig.runClient();
                rig.prediction.verifyNoInteractions();
                rig.compat.verify(() -> VoxyCompat.confirmNativeColumn(any(), any(), anyInt(), anyInt()), never());
                var session = (NativeSession) rig.session.get();
                assertFalse(session.timestamps.containsKey(ChunkPos.asLong(10, 0)));
                assertFalse(session.requested.contains(ChunkPos.asLong(10, 0)));
                assertFalse(session.spiralStarted);
            }
        }
    }

    @Test void staleConnectionLevelSessionAndEngineAreRecheckedOnTheClientThread() throws Throwable {
        for (int scenario = 0; scenario < 4; scenario++) {
            try (Rig rig = new Rig()) {
                Object scope = rig.begin(0, -4, 2);
                rig.finish(rig.section(-4)); rig.finish(rig.section(-3));
                VoxyServerLodClientCompat.finishNativeColumn(scope, true);
                assertEquals(1, rig.clientQueue.size());
                if (scenario == 0) rig.client.level = mock(ClientLevel.class);
                if (scenario == 1) when(rig.client.getConnection()).thenReturn(mock(ClientPacketListener.class));
                if (scenario == 2) rig.session.set(new Object());
                if (scenario == 3) rig.liveEngine.set(new Object());
                rig.runClient();
                rig.prediction.verifyNoInteractions();
                rig.compat.verify(() -> VoxyCompat.confirmNativeColumn(any(), any(), anyInt(), anyInt()), never());
            }
        }
    }

    @Test void switchingToVssStopsNativeWorkRegardlessOfThePredictionToggle() throws Throwable {
        try (Rig rig = new Rig()) {
            Object scope = rig.begin(0, -4, 2);
            VSSApi.bindClientSession(new ClientLodSession() {
                @Override public boolean isClientLodSessionActive() { return true; }
                @Override public boolean isPredictionActive() { return false; }
            });
            assertTrue(VoxyServerLodClientCompat.pauseNativeReceiver());
            assertFalse(VoxyServerLodClientCompat.ingestNativeSection(rig.engine, mock(LevelChunkSection.class),
                    10, -4, 0, null, null, true, () -> { fail("VSS owns native ingestion"); return true; }));
            VoxyServerLodClientCompat.finishNativeColumn(scope, true);
            rig.runClient();
            rig.prediction.verifyNoInteractions();
            NativeSession session = (NativeSession) rig.session.get();
            assertEquals(1234, session.timestamps.get(ChunkPos.asLong(10, 0)), "VSS owns this session now");
            VSSApi.bindClientSession(ClientLodSession.DISCONNECTED);
            assertFalse(VoxyServerLodClientCompat.pauseNativeReceiver());
        }
    }

    @Test void wrongHeightAndUnknownCompletionHookKeepNativeFallbackWithoutGrantingAuthority() throws Throwable {
        try (Rig rig = new Rig()) {
            Object scope = rig.begin(0, -4, 1);
            assertTrue(VoxyServerLodClientCompat.ingestNativeSection(rig.engine, mock(LevelChunkSection.class),
                    10, -4, 0, null, null, false, () -> {
                        assertTrue(VoxyIngestControl.isServerIngestActive()); return true;
                    }));
            VoxyServerLodClientCompat.finishNativeColumn(scope, true);
            assertTrue(rig.clientQueue.isEmpty());
            rig.prediction.verifyNoInteractions();
        }
        try (Rig rig = new Rig()) {
            Object scope = rig.begin(0, -4, 2);
            for (int y = -4; y < -2; y++) assertTrue(VoxyServerLodClientCompat.ingestNativeSection(
                    rig.engine, mock(LevelChunkSection.class), 10, y, 0, null, null, false, () -> true));
            VoxyServerLodClientCompat.finishNativeColumn(scope, true);
            assertTrue(rig.clientQueue.isEmpty(), "Unverified successes must not cause a native retry storm");
            rig.prediction.verifyNoInteractions();
        }
    }

    @Test void falseThrownAndUnpublishedNativeResultsClearTheTimestampForRetry() throws Throwable {
        for (boolean tracking : new boolean[] { false, true }) for (int result = 0; result < 3; result++) {
            try (Rig rig = new Rig()) {
                Object scope = rig.begin(0, -4, 2);
                final int mode = result;
                VoxyIngestControl.ThrowingBooleanSupplier raw = () -> {
                    if (mode == 1) throw new IllegalStateException("native rejected");
                    return mode == 2;
                };
                if (result == 1) assertThrows(IllegalStateException.class, () ->
                        VoxyServerLodClientCompat.ingestNativeSection(rig.engine, mock(LevelChunkSection.class),
                                10, -4, 0, null, null, tracking, raw));
                else VoxyServerLodClientCompat.ingestNativeSection(rig.engine, mock(LevelChunkSection.class),
                        10, -4, 0, null, null, tracking, raw);
                VoxyServerLodClientCompat.finishNativeColumn(scope, true);
                rig.runClient();
                rig.prediction.verifyNoInteractions();
                NativeSession session = (NativeSession) rig.session.get();
                assertFalse(session.timestamps.containsKey(ChunkPos.asLong(10, 0)));
                assertFalse(session.requested.contains(ChunkPos.asLong(10, 0)));
                assertFalse(VoxyIngestControl.isServerIngestActive());
                assertFalse(VoxyIngestTracker.rawScopeActive());
            }
        }
    }

    @Test void staleEngineRejectsBeforeSubmissionAndOlderFailureCannotEraseANewerPush() throws Throwable {
        try (Rig rig = new Rig()) {
            Object scope = rig.begin(0, -4, 2);
            rig.liveEngine.set(new Object());
            assertFalse(VoxyServerLodClientCompat.ingestNativeSection(rig.engine, mock(LevelChunkSection.class),
                    10, -4, 0, null, null, true, () -> { fail("stale engine"); return true; }));
            VoxyServerLodClientCompat.finishNativeColumn(scope, true);
            NativeSession session = (NativeSession) rig.session.get();
            session.timestamps.put(ChunkPos.asLong(10, 0), 1235);
            rig.runClient();
            assertEquals(1235, session.timestamps.get(ChunkPos.asLong(10, 0)));
            assertTrue(session.requested.contains(ChunkPos.asLong(10, 0)));
            rig.prediction.verifyNoInteractions();
        }
    }

    private static final class Rig implements AutoCloseable {
        final ClientLodSession oldSession = VSSApi.clientSession();
        final Minecraft client = mock(Minecraft.class);
        final ClientLevel level = mock(ClientLevel.class);
        final ClientPacketListener handler = mock(ClientPacketListener.class);
        final Object engine = new Object();
        final AtomicReference<Object> liveEngine = new AtomicReference<>(engine);
        final AtomicReference<Object> session = new AtomicReference<>(new NativeSession());
        final List<Runnable> clientQueue = new ArrayList<>();
        final MockedStatic<VoxyCompat> compat = mockStatic(VoxyCompat.class);
        final MockedStatic<ClientPredictionState> prediction = mockStatic(ClientPredictionState.class);

        Rig() {
            VSSApi.bindClientSession(ClientLodSession.DISCONNECTED);
            client.level = level;
            when(client.getConnection()).thenReturn(handler);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.getMinSection()).thenReturn(-4);
            when(level.getSectionsCount()).thenReturn(2);
            doAnswer(call -> { clientQueue.add(call.getArgument(0)); return null; }).when(client).execute(any(Runnable.class));
            compat.when(() -> VoxyCompat.engineForLevel(level)).thenAnswer(call -> liveEngine.get());
            prediction.when(() -> ClientPredictionState.onExactColumn(any(), anyInt(), anyInt(), any(VoxelColumnData.class)))
                    .thenReturn(true);
        }

        Object begin(int status, int minimum, int count) {
            return begin(status, minimum, count, 1234L);
        }

        Object begin(int status, int minimum, int count, long timestamp) {
            FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
            try {
                packet.writeResourceLocation(Level.OVERWORLD.location());
                packet.writeLong(ChunkPos.asLong(10, 0)); packet.writeByte(status); packet.writeLong(timestamp);
                packet.writeVarInt(minimum); packet.writeVarInt(count);
                int readerIndex = packet.readerIndex();
                Object token = VoxyServerLodClientCompat.beginNativeColumn(client, handler, packet, session.get(), session::get);
                assertEquals(readerIndex, packet.readerIndex());
                return token;
            } finally { packet.release(); }
        }

        Object section(int y) throws Throwable {
            Object task = new Object();
            assertTrue(VoxyServerLodClientCompat.ingestNativeSection(engine, mock(LevelChunkSection.class),
                    10, y, 0, null, null, true, () -> {
                        assertTrue(VoxyIngestControl.isServerIngestActive());
                        VoxyIngestTracker.submittedSnapshot(task); return true;
                    }));
            assertFalse(VoxyIngestControl.isServerIngestActive());
            return task;
        }

        void finish(Object task) {
            Object worker = VoxyIngestTracker.enterWorker();
            VoxyIngestTracker.workerTask(task); VoxyIngestTracker.finishWorker(worker);
        }
        void runClient() { List.copyOf(clientQueue).forEach(Runnable::run); clientQueue.clear(); }
        @Override public void close() {
            VoxyIngestTracker.reset(); prediction.close(); compat.close(); VSSApi.bindClientSession(oldSession);
        }
    }

    private static final class NativeSession {
        private final Long2LongOpenHashMap timestamps = new Long2LongOpenHashMap();
        private final LongOpenHashSet requested = new LongOpenHashSet();
        private boolean spiralStarted = true, dirty;
        NativeSession() {
            timestamps.put(ChunkPos.asLong(10, 0), 1234L);
            requested.add(ChunkPos.asLong(10, 0));
        }
    }
}
