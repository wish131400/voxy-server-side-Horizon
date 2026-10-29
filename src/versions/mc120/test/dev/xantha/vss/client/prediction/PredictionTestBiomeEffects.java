package dev.xantha.vss.client.prediction;

import net.minecraft.world.level.biome.BiomeSpecialEffects;

/** Minecraft 1.20.1 requires all four color fields in a test biome. */
final class PredictionTestBiomeEffects {
    static BiomeSpecialEffects empty() {
        return new BiomeSpecialEffects.Builder().fogColor(0).skyColor(0)
                .waterFogColor(0).waterColor(0).build();
    }
}
