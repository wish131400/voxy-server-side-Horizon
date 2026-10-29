package dev.xantha.vss.common.worldgen;

import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.densityfunction.*;

/** Captures effective references using the density rewrite contract introduced in 26.3. */
public final class DensityFunctionSnapshot {
    private DensityFunctionSnapshot() { }
    public static DensityFunction applied(DensityFunction function) {
        return DfRewriteRule.INLINE_REFERENCE.rewrite(function.rewriteChildren(DensityFunctionSnapshot::applied));
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
