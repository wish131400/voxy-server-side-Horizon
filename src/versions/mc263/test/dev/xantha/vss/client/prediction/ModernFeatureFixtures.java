package dev.xantha.vss.client.prediction;

import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.featuresize.FeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;

/** Test fixture conveniences; all emitted features are the actual 26.3 feature records. */
final class ModernFeatureFixtures {
    private ModernFeatureFixtures() { }
    record Context(WorldGenLevel level, ChunkGenerator chunkGenerator, RandomSource random, BlockPos origin) { }

    abstract static class FeatureAdapter implements Feature {
        abstract boolean place(Context context);
        @Override public final boolean place(WorldGenLevel level, ChunkGenerator generator,
                RandomSource random, BlockPos origin) {
            return place(new Context(level, generator, random, origin));
        }
        @Override public final MapCodec<? extends Feature> codec() { return MapCodec.unit(this); }
    }

    static BlockStateProvider belowTrunk() {
        return RuleBasedStateProvider.ifTrueThenProvide(
                BlockPredicate.not(BlockPredicate.matchesTag(BlockTags.CANNOT_REPLACE_BELOW_TREE_TRUNK)), Blocks.DIRT);
    }

    static final class TreeBuilder {
        private final Holder<BlockStateProvider> trunk;
        private final TrunkPlacer trunkPlacer;
        private final Holder<BlockStateProvider> foliage;
        private final FoliagePlacer foliagePlacer;
        private final FeatureSize size;
        private final Holder<BlockStateProvider> belowTrunk;
        private boolean ignoreVines;
        private List<TreeDecorator> decorators = List.of();
        TreeBuilder(BlockStateProvider trunk, TrunkPlacer trunkPlacer,
                BlockStateProvider foliage, FoliagePlacer foliagePlacer, FeatureSize size,
                BlockStateProvider belowTrunk) {
            this(Holder.direct(trunk), trunkPlacer, Holder.direct(foliage), foliagePlacer, size, Holder.direct(belowTrunk));
        }
        TreeBuilder(Holder<BlockStateProvider> trunk, TrunkPlacer trunkPlacer,
                Holder<BlockStateProvider> foliage, FoliagePlacer foliagePlacer, FeatureSize size,
                Holder<BlockStateProvider> belowTrunk) {
            this.trunk = trunk; this.trunkPlacer = trunkPlacer; this.foliage = foliage;
            this.foliagePlacer = foliagePlacer; this.size = size; this.belowTrunk = belowTrunk;
        }
        TreeBuilder ignoreVines() { ignoreVines = true; return this; }
        TreeBuilder decorators(List<TreeDecorator> values) { decorators = values; return this; }
        TreeFeature build() {
            return new TreeFeature(trunk, trunkPlacer, foliage, foliagePlacer, Optional.empty(),
                    size, decorators, ignoreVines, belowTrunk);
        }
    }
}
