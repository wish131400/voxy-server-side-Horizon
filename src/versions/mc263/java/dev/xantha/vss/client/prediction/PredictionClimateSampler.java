package dev.xantha.vss.client.prediction;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;

/** Exact climate selection through the vanilla compiler with caches isolated per worker. */
final class PredictionClimateSampler implements AutoCloseable {
    private final PredictionHorizontalClimate horizontal = new PredictionHorizontalClimate();
    private final PredictionDensityContext context;
    private final Climate.Sampler sampler;
    PredictionClimateSampler(RandomState state, NoiseRouter router) {
        context = new PredictionDensityContext(state);
        var roots = horizontal.wrap(router.temperature(), router.vegetation(), router.continents(), router.erosion(), router.depth(), router.ridges());
        sampler = new Climate.Sampler(context.bind(roots[0]), context.bind(roots[1]), context.bind(roots[2]),
                context.bind(roots[3]), context.bind(roots[4]), context.bind(roots[5]));
        FabricBiomeCompat.copyClimateContext(state.createClimateSampler(
                net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED), sampler);
    }
    Climate.Sampler sampler() { return sampler; }
    String diagnostics() { return context.diagnostics(); }
    String horizontalDiagnostics() { return horizontal.diagnostics(); }
    void bindHorizontal(long world, net.minecraft.world.level.levelgen.RandomState random, com.google.gson.JsonObject document, long seed) { horizontal.bind(world, random, document, seed); }
    void detachHorizontal() { horizontal.detach(); }
    @Override public void close() { horizontal.close(); context.close(); }
}
