package dev.xantha.vss.client.prediction;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;

import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;

/** Reuses vanilla block placement while omitting live-world post-processing. */
final class PredictionSurfaceFeatureAdapters {
    private PredictionSurfaceFeatureAdapters() { }

    static void place(PlacedFeature feature, PredictionDecorationLevel level, ChunkGenerator generator,
                      RandomSource random, BlockPos origin) {
        var configured = feature.feature().value();
        boolean adapted = configured instanceof EndSpikeFeature;
        if (!adapted && !PredictionPlacementExecutor.available()) {
            new FeaturePlacer(level, generator).placeWithBiomeCheck(feature, random, origin);
            return;
        }
        // BiomeFilter must see the original registered placed feature, including
        // its modifiers and global seed index, even when its block adapter differs.
        var context = new PlacementContext(level,generator,Optional.of(feature));
        java.util.function.Consumer<BlockPos> terminal = pos -> {
            if (configured instanceof EndSpikeFeature spikes)
                PredictionEndSpikes.place(level,random,spikes,pos);
            else configured.place(level, generator, random, pos);
        };
        if (PredictionPlacementExecutor.available())
            PredictionPlacementExecutor.visit(feature, context, random, origin, terminal);
        else PredictionPlacementExecutor.stream(feature, context, random, origin, terminal);
    }
}
