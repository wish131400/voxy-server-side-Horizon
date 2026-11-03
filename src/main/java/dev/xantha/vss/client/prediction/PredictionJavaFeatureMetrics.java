package dev.xantha.vss.client.prediction;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Per-reason counters read without invoking native code or waiting on placement. */
final class PredictionJavaFeatureMetrics {
    private static final class Counters {
        final LongAdder attempts = new LongAdder(), nanos = new LongAdder();
        final LongAdder withoutWrites = new LongAdder(), failed = new LongAdder();
    }
    private final Map<String,Counters> reasons = new ConcurrentHashMap<>();

    void record(String reason, int writes, boolean success, long nanos) {
        var counters = reasons.computeIfAbsent(reason, ignored -> new Counters());
        counters.attempts.increment();
        counters.nanos.add(nanos);
        if (writes == 0) counters.withoutWrites.increment();
        if (!success) counters.failed.increment();
    }

    Map<String,Object> snapshot() {
        var result = new LinkedHashMap<String,Object>();
        reasons.forEach((reason,counters) -> result.put(reason, Map.of(
                "attempts",counters.attempts.sum(), "nanos",counters.nanos.sum(),
                "withoutWrites",counters.withoutWrites.sum(), "failed",counters.failed.sum())));
        return Map.copyOf(result);
    }
}
