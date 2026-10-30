package dev.xantha.vss.common.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class AppliedDensityRecordsTest {
    private record Density(int value) { }
    private record Branch(Object left, Object right) { }
    private record Unrelated(Object child) { }

    private static AppliedDensityRecords records() {
        return new AppliedDensityRecords(value -> value instanceof Density density && density.value() == 1
                ? new Density(2) : value, value -> value instanceof Density || value instanceof Branch);
    }

    @Test void identityCacheRetainsSharedChildrenAndLeavesUnchangedGraphsIntact() {
        var shared = new Density(1);
        var mapped = (Branch) records().capture(new Branch(shared, shared));
        assertSame(mapped.left(), mapped.right());
        assertEquals(new Density(2), mapped.left());
        var unchanged = new Branch(new Density(3), new Unrelated(shared));
        assertSame(unchanged, records().capture(unchanged));
        assertSame(shared, ((Unrelated) unchanged.right()).child(), "do not traverse unrelated records");
    }

    @Test void cyclicDensityChildrenFailWithoutRecursingForever() {
        var children = new ArrayList<Object>();
        var cyclic = new Branch(children, new Density(3));
        children.add(cyclic);
        var failure = assertThrows(IllegalStateException.class, () -> records().capture(cyclic));
        assertTrue(failure.getMessage().contains("Cyclic applied density graph"));
    }
}
