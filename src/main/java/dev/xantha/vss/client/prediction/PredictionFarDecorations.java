package dev.xantha.vss.client.prediction;

/** The experiment is available only in the audited 26.3 overlay. */
final class PredictionFarDecorations {
    static boolean supported(ClientTerrainSampler sampler) { return false; }
    PredictionFarDecorations(ClientTerrainSampler sampler) { }
    PredictionVoxySource.Tile build(PredictionVoxySource.Tile base,
            dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities,boolean trees,
            java.util.function.LongPredicate captured,java.util.function.BooleanSupplier current) {
        throw new UnsupportedOperationException("Far decoration experiment is unavailable for this Minecraft version");
    }
    void invalidate(int x,int z) { }
    String diagnostics() { return "farDecoration=unavailable"; }
}
