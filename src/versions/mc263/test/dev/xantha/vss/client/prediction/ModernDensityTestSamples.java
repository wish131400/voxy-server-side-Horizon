package dev.xantha.vss.client.prediction;

import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/** Actual vanilla compiler for the opt-in released-mod numerical contracts. */
final class ModernDensityTestSamples {
    private static RandomState random;
    private ModernDensityTestSamples() { }
    static synchronized float sample(DensityFunction function, int x, int y, int z) {
        if (random == null) {
            ModernDensityFixtures.bootstrap();
            random = ModernDensityFixtures.random(ModernDensityFixtures.settings(NoiseGeneratorSettings.OVERWORLD), 0);
        }
        return random.getSampler(function).sampleValue(SamplerContext.EMPTY_UNCACHED, x, y, z);
    }
}
