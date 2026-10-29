package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VoxyNativeIngestAuthorityTest {
    @BeforeAll static void config() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        System.setProperty("vss.configDir", java.nio.file.Files.createTempDirectory("vss-native-config").toString());
    }
    @AfterEach void cleanup() { VoxyIngestTracker.reset(); VoxyCompat.onDisconnect(); }

    @Test void completionForOneEngineCannotSettleTheOtherEngineAtTheSameCoordinates() throws Throwable {
        Object first = new Object(), second = new Object();
        Field handle = VoxyCompat.class.getDeclaredField("worldEngineNullable");
        handle.setAccessible(true);
        Object original = handle.get(null);
        MethodHandle resolver = MethodHandles.lookup().findStatic(VoxyNativeIngestAuthorityTest.class,
                "resolveEngine", MethodType.methodType(Object.class, Level.class));
        handle.set(null, resolver);
        try {
            var unscoped = VoxyIngestTracker.begin(new Object(), 4, -5);
            var firstTicket = VoxyIngestTracker.begin(first, new Object(), 4, -5, null);
            var secondTicket = VoxyIngestTracker.begin(second, new Object(), 4, -5, null);
            selectedEngine = first;
            assertTrue(VoxyCompat.confirmNativeColumn(mock(Level.class), first, 4, -5));
            selectedEngine = second;
            assertTrue(VoxyCompat.confirmNativeColumn(mock(Level.class), second, 4, -5));
            assertFalse(VoxyCompat.confirmedPredictionColumn(first, 4, -5));
            assertFalse(VoxyCompat.confirmedPredictionColumn(second, 4, -5));
            VoxyIngestTracker.complete(firstTicket);
            assertFalse(VoxyCompat.confirmedPredictionColumn(first, 4, -5), "Unscoped work still owns this column");
            VoxyIngestTracker.complete(unscoped);
            assertTrue(VoxyCompat.confirmedPredictionColumn(first, 4, -5));
            assertFalse(VoxyCompat.confirmedPredictionColumn(second, 4, -5));
            VoxyIngestTracker.complete(secondTicket);
            assertTrue(VoxyCompat.confirmedPredictionColumn(second, 4, -5));
            selectedEngine = second;
            assertFalse(VoxyCompat.confirmNativeColumn(mock(Level.class), first, 9, 9));
            assertFalse(VoxyCompat.confirmedPredictionColumn(first, 9, 9));
            var stale = VoxyCompat.confirmedPredictionColumns(first);
            VoxyCompat.onDisconnect();
            assertFalse(stale.contains(4, -5));
        } finally { handle.set(null, original); selectedEngine = null; }
    }

    @Test void nativeReceiptsDistinguishExecutedWorkFromDropsFailuresAndUnpublishedTrue() throws Throwable {
        Object engine = new Object(), section = new Object();
        List<Boolean> results = new ArrayList<>();
        Object task = new Object();
        assertTrue(VoxyIngestTracker.runRawIngest(engine, section, 1, 2, results::add, () -> {
            VoxyIngestTracker.submittedSnapshot(task); return true;
        }));
        assertTrue(results.isEmpty());
        Object worker = VoxyIngestTracker.enterWorker();
        VoxyIngestTracker.workerTask(task);
        VoxyIngestTracker.finishWorker(worker);
        VoxyIngestTracker.finishWorker(worker);
        assertEquals(List.of(true), results);

        Object discarded = new Object();
        VoxyIngestTracker.runRawIngest(engine, section, 1, 2, results::add, () -> {
            VoxyIngestTracker.submittedSnapshot(discarded); return true;
        });
        VoxyIngestTracker.discarded(discarded);
        assertEquals(List.of(true, false), results);

        Object failed = new Object();
        VoxyIngestTracker.runRawIngest(engine, section, 1, 2, results::add, () -> {
            VoxyIngestTracker.submittedSnapshot(failed); return true;
        });
        worker = VoxyIngestTracker.enterWorker();
        VoxyIngestTracker.workerTask(failed);
        VoxyIngestTracker.workerFailed(worker, new IllegalStateException("native failed"));
        VoxyIngestTracker.runRawIngest(engine, section, 1, 2, results::add, () -> true);
        assertEquals(List.of(true, false, false, false), results);
        assertFalse(VoxyIngestTracker.pendingColumn(engine, 1, 2));
        assertFalse(VoxyIngestTracker.pendingColumn(1, 2));
        assertFalse(VoxyIngestTracker.workerScopeActive());
    }

    @Test void resetRetiresReceiptsAndOldCompletionCannotConsumeOrNotifyTheNewSession() throws Throwable {
        Object engine = new Object(), section = new Object(), oldTask = new Object(), newTask = new Object();
        List<Boolean> oldResults = new ArrayList<>(), newResults = new ArrayList<>();
        VoxyIngestTracker.runRawIngest(engine, section, 1, 2, oldResults::add, () -> {
            VoxyIngestTracker.submittedSnapshot(oldTask); return true;
        });
        VoxyIngestTracker.reset();
        VoxyIngestTracker.runRawIngest(engine, section, 1, 2, newResults::add, () -> {
            VoxyIngestTracker.submittedSnapshot(newTask); return true;
        });
        VoxyIngestTracker.discarded(oldTask);
        assertTrue(VoxyIngestTracker.pendingColumn(engine, 1, 2));
        assertTrue(newResults.isEmpty()); assertEquals(List.of(false), oldResults);
        Object worker = VoxyIngestTracker.enterWorker();
        VoxyIngestTracker.workerTask(newTask); VoxyIngestTracker.finishWorker(worker);
        assertEquals(List.of(true), newResults);
    }

    private static Object selectedEngine;
    private static Object resolveEngine(Level ignored) { return selectedEngine; }
}
