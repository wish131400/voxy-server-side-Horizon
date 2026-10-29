package dev.xantha.vss.client.prediction;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;

/** Exact climate selection through the vanilla compiler with caches isolated per worker. */
final class PredictionClimateSampler implements AutoCloseable {
    private final PredictionDensityContext context;
    private final Climate.Sampler sampler;
    PredictionClimateSampler(RandomState state, NoiseRouter router) {
        context = new PredictionDensityContext(state);
        sampler = new Climate.Sampler(context.bind(router.temperature()), context.bind(router.vegetation()),
                context.bind(router.continents()), context.bind(router.erosion()), context.bind(router.depth()), context.bind(router.ridges()));
        FabricBiomeCompat.copyClimateContext(state.createClimateSampler(
                net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED), sampler);
    }
    Climate.Sampler sampler() { return sampler; }
    String diagnostics() { return context.diagnostics(); }
    @Override public void close() { context.close(); }
}
