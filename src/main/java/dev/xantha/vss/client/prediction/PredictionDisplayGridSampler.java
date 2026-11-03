package dev.xantha.vss.client.prediction;

/** Optional larger display submission; existing samplers keep their 8x8 path. */
interface PredictionDisplayGridSampler {
    void sampleDisplayRegion(int x, int z, int step, int size, ClientColumnSample[] samples,
                             java.util.function.BooleanSupplier current);
    default java.util.Map<String,Object> displayPerformanceSnapshot() { return java.util.Map.of(); }
}
