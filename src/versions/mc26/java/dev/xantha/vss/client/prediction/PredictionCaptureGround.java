package dev.xantha.vss.client.prediction;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 26.1 split grass, moss and mud out of minecraft:dirt. */
final class PredictionCaptureGround {
    private static final TagKey<Block> SUBSTRATE = TagKey.create(Registries.BLOCK,
            Identifier.withDefaultNamespace("substrate_overworld"));

    static boolean includes(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(SUBSTRATE);
    }

    static boolean obsolete(ClientColumnSample sample) {
        // Old parent reductions lost FLAG_CAPTURED, so their selected surface
        // cannot prove it was independent of an incorrectly classified capture.
        int provenance = ClientColumnSample.FLAG_CAPTURED | ClientColumnSample.FLAG_CAPTURE_MIP
                | ClientColumnSample.FLAG_PARENT_LOD;
        return (sample.flags() & provenance) != 0
                && (sample.flags() & ClientColumnSample.FLAG_CAPTURE_GROUND_V2) == 0;
    }
}
