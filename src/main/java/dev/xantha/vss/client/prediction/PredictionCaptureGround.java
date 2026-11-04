package dev.xantha.vss.client.prediction;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Version-specific natural surface tags and capture migration policy. */
final class PredictionCaptureGround {
    static boolean includes(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK);
    }

    static boolean obsolete(ClientColumnSample sample) {
        return false; // Before 26.1, minecraft:dirt already included grass blocks.
    }
}
