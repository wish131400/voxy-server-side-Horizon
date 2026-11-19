package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class FreeTerraForgedPredictionTasksTest {
    @Test void onlyOwnedPredictionBatchesUseTheirAdmittedCallerAndKeepFutureFailures() throws Exception {
        try (var input = getClass().getResourceAsStream("/vss.compat.mixins.json")) {
            assertNotNull(input);
            assertTrue(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("client.UltraTerraForgedPredictionTasksMixin"), "effective version resources must enable the production hook");
        }
        try (var input = getClass().getResourceAsStream("/vss.compat.mixins.json")) {
            assertNotNull(input);
            assertTrue(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("client.UltraTerraForgedPredictionTileCacheMixin"), "the cache unlock hook must be enabled too");
        }
        Object prediction = new Object(), server = new Object();
        FreeTerraForgedPredictionTasks.ownGenerator(prediction);
        AtomicInteger submissions = new AtomicInteger();
        Executor upstream = action -> { submissions.incrementAndGet(); action.run(); };
        AtomicReference<Thread> worker = new AtomicReference<>();
        FreeTerraForgedPredictionTasks.run(prediction, () -> worker.set(Thread.currentThread()), upstream).join();
        assertSame(Thread.currentThread(), worker.get()); assertEquals(0, submissions.get());
        FreeTerraForgedPredictionTasks.run(server, () -> {}, upstream).join();
        assertEquals(1, submissions.get(), "server-owned generation must keep the upstream executor");
        var failure = FreeTerraForgedPredictionTasks.run(prediction, () -> { throw new IllegalStateException("failure"); }, upstream);
        assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, failure::join).getCause());
    }
}
