package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonElement;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.foliageplacers.CherryFoliagePlacer;

/** Preserve effective runtime values which the 1.21.11 vanilla codec encodes incorrectly. */
public final class VanillaFeatureSnapshot {
    private VanillaFeatureSnapshot() { }

    public static JsonElement correct(ConfiguredFeature<?, ?> feature, JsonElement encoded) {
        if (feature.config() instanceof TreeConfiguration tree
                && tree.foliagePlacer instanceof CherryFoliagePlacer cherry) {
            // Vanilla's corner_hole_chance getter reads wideBottomLayerHoleChance.
            // The access widener keeps this reference valid after Fabric remaps production names.
            encoded.getAsJsonObject().getAsJsonObject("config")
                    .getAsJsonObject("foliage_placer")
                    .addProperty("corner_hole_chance", cherry.cornerHoleChance);
        }
        return encoded;
    }

}
