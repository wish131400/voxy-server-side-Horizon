package dev.xantha.vss.client.prediction;

import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.block.Block;

/** Apply tag fixtures through the same pending reload contract as Minecraft. */
final class PredictionTestTags {
    private PredictionTestTags() { }
    static void bind(Map<TagKey<Block>,List<Holder<Block>>> tags) {
        BuiltInRegistries.BLOCK.prepareTagReload(new TagLoader.LoadResult<>(BuiltInRegistries.BLOCK.key(),tags)).apply();
    }
}
