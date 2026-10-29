package dev.xantha.vss.client.prediction;

import net.minecraft.world.level.biome.BiomeSpecialEffects;

/** Version-specific minimum biome effects for offline vegetation fixtures. */
final class PredictionTestBiomeEffects {
    static BiomeSpecialEffects empty() {
        return new BiomeSpecialEffects.Builder().waterColor(0).build();
    }
}
