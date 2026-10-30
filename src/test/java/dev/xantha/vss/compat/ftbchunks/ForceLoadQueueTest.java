package dev.xantha.vss.compat.ftbchunks;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForceLoadQueueTest {
    private static final UUID OWNER = new UUID(1, 2);

    @Test void oneBudgetCoversSeveralDimensions() {
        var queue = new ForceLoadQueue<Object>();
        Object a = new Object(), b = new Object();
        var calls = new ArrayList<Integer>();
        queue.put(key(a, 0), () -> calls.add(0));
        queue.put(key(b, 1), () -> calls.add(1));
        queue.put(key(a, 2), () -> calls.add(2));
        queue.drain(level -> true, 2);
        assertEquals(List.of(0, 1), calls);
        assertEquals(1, queue.size());
        queue.drain(level -> true, 2);
        assertEquals(List.of(0, 1, 2), calls);
    }

    @Test void duplicateOwnerColumnKeepsOnlyLatestRequest() {
        var queue = new ForceLoadQueue<Object>();
        Object level = new Object();
        var calls = new ArrayList<String>();
        queue.put(key(level, 0), () -> calls.add("add"));
        queue.put(key(level, 0), () -> calls.add("remove"));
        queue.drain(value -> true, 5);
        assertEquals(List.of("remove"), calls);
    }

    @Test void cancellationPreventsOldAddFromRestoringRemovedTicket() {
        var queue = new ForceLoadQueue<Object>();
        Object level = new Object();
        queue.put(key(level, 0), () -> fail("Cancelled add must not run"));
        queue.remove(key(level, 0));
        queue.drain(value -> true, 5);
        assertEquals(0, queue.size());
    }

    @Test void ownersAndModIdsAreNotCoalescedTogether() {
        var queue = new ForceLoadQueue<Object>();
        Object level = new Object();
        var calls = new ArrayList<Integer>();
        queue.put(key(level, 0), () -> calls.add(1));
        queue.put(new ForceLoadQueue.Key<>(level, "ftbchunks", new UUID(3, 4), 0, 0), () -> calls.add(2));
        queue.put(new ForceLoadQueue.Key<>(level, "another", OWNER, 0, 0), () -> calls.add(3));
        queue.drain(value -> true, 5);
        assertEquals(List.of(1, 2, 3), calls);
    }

    @Test void releaseAfterOwnershipChangeCancelsEveryEarlierAddToThatVanillaTicket() {
        var queue = new ForceLoadQueue<Object>();
        Object level = new Object(), otherLevel = new Object();
        var calls = new ArrayList<Integer>();
        queue.put(key(level, 0), () -> fail("Old owner add is stale"));
        queue.put(new ForceLoadQueue.Key<>(level, "ftbchunks", new UUID(3, 4), 0, 0),
                () -> fail("Second owner add is older than release"));
        queue.put(key(otherLevel, 0), () -> calls.add(1));
        queue.put(new ForceLoadQueue.Key<>(level, "another", OWNER, 0, 0), () -> calls.add(2));
        queue.removeColumn(level, "ftbchunks", 0, 0);
        queue.drain(value -> true, 5);
        assertEquals(List.of(1, 2), calls);
    }

    @Test void serverStopDropsOnlyThatServersLevelReferences() {
        var queue = new ForceLoadQueue<Object>();
        Object oldServer = new Object(), otherServer = new Object();
        var calls = new ArrayList<Integer>();
        queue.put(key(oldServer, 0), () -> fail("Stopped server must be released"));
        queue.put(key(otherServer, 0), () -> calls.add(1));
        queue.clear(value -> value == oldServer);
        queue.drain(value -> value == otherServer, 5);
        assertEquals(List.of(1), calls);
        assertEquals(0, queue.size());
    }

    @Test void oneServerCannotDrainAnotherServersRequests() {
        var queue = new ForceLoadQueue<Object>();
        Object a = new Object(), b = new Object();
        var calls = new ArrayList<Integer>();
        queue.put(key(b, 0), () -> calls.add(2));
        queue.put(key(a, 0), () -> calls.add(1));
        queue.drain(value -> value == a, 5);
        assertEquals(List.of(1), calls);
        assertEquals(1, queue.size());
    }

    @Test void scopedSuppressionRestoresAfterNestedCallAndException() {
        var outer = new ForceLoadQueue.Key<net.minecraft.server.level.ServerLevel>(null, "ftbchunks", OWNER, 1, 2);
        var inner = new ForceLoadQueue.Key<net.minecraft.server.level.ServerLevel>(null, "ftbchunks", OWNER, 3, 4);
        assertFalse(FTBChunksForceLoadCompat.skipSynchronousChunkRead(null, 1, 2));
        assertThrows(IllegalStateException.class, () -> FTBChunksForceLoadCompat.runScoped(outer, () -> {
            assertTrue(FTBChunksForceLoadCompat.skipSynchronousChunkRead(null, 1, 2));
            assertFalse(FTBChunksForceLoadCompat.skipSynchronousChunkRead(null, 3, 4));
            FTBChunksForceLoadCompat.runScoped(inner, () -> {
                assertTrue(FTBChunksForceLoadCompat.skipSynchronousChunkRead(null, 3, 4));
                assertFalse(FTBChunksForceLoadCompat.skipSynchronousChunkRead(null, 1, 2));
            });
            assertTrue(FTBChunksForceLoadCompat.skipSynchronousChunkRead(null, 1, 2));
            throw new IllegalStateException("test cleanup");
        }));
        assertFalse(FTBChunksForceLoadCompat.skipSynchronousChunkRead(null, 1, 2));
    }

    private static ForceLoadQueue.Key<Object> key(Object level, int x) {
        return new ForceLoadQueue.Key<>(level, "ftbchunks", OWNER, x, 0);
    }
}
