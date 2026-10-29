package dev.xantha.vss.common.worldgen;

import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.densityfunction.*;

/** Captures effective references using the density rewrite contract introduced in 26.3. */
public final class DensityFunctionSnapshot {
    private DensityFunctionSnapshot() { }
    public static DensityFunction applied(DensityFunction function) {
        DensityFunction inlined = DfRewriteRule.INLINE_REFERENCE.rewrite(function);
        if (inlined != function) return applied(inlined);
        // Lithostitched's codec deliberately serializes the pre-modifier
        // original graph. Its sampler executes full, including nested wrappers.
        // Rewrite the executable graph before invoking any replay/source codec.
        if (function.getClass().getName().equals(
                "dev.worldgen.lithostitched.impl.worldgen.densityfunction.marker.MergedFunction")) {
            try {
                return applied((DensityFunction) function.getClass().getMethod("full").invoke(function));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot capture applied Lithostitched density function", failure);
            }
        }
        return function.rewriteChildren(DensityFunctionSnapshot::applied);
    }
    public static NoiseGeneratorSettings applied(NoiseGeneratorSettings s) {
        NoiseRouter r = s.noiseRouter();
        NoiseRouter mapped = new NoiseRouter(applied(r.temperature()), applied(r.vegetation()), applied(r.continents()),
                applied(r.erosion()), applied(r.depth()), applied(r.ridges()), applied(r.chunkSurfaceLevel()), applied(r.finalDensity()));
        return new NoiseGeneratorSettings(s.noiseSettings(), s.defaultBlock(), s.defaultFluid(), mapped,
                s.materialRule(), s.spawnTarget(), s.seaLevel(), s.disableMobGeneration(), s.aquifers(),
                s.useLegacyRandomSource(), s.debugFunctions());
    }
}
