package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BlobFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.*;
import net.minecraft.world.level.levelgen.feature.trunkplacers.StraightTrunkPlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Uses real version tags, rather than putting grass back into the old dirt tag. */
class PredictionTreeModelEnvironmentTest {
    private static net.minecraft.world.level.chunk.ChunkGenerator generator;
    private static final BlockPos ORIGIN = new BlockPos(0, FeatureStampLevel.GROUND_Y, 0);

    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        generator = dev.xantha.vss.testutil.TerrainPredictionFixtures.noise();
        assertFalse(Blocks.GRASS_BLOCK.defaultBlockState().is(BlockTags.DIRT),
                "fixture must reproduce the modern dirt tag");
        assertTrue(Blocks.GRASS_BLOCK.defaultBlockState().is(BlockTags.SUPPORTS_VEGETATION));
    }

    @Test void grassAndOtherModernSubstratesReuseModelsAndEvaluateTheActualSoilRule() {
        var provider = RuleBasedStateProvider.ifTrueThenProvide(
                BlockPredicate.not(BlockPredicate.matchesTag(BlockTags.CANNOT_REPLACE_BELOW_TREE_TRUNK)), Blocks.DIRT);
        var feature = tree(provider);
        var models = new PredictionTreeModels(RegistryAccess.EMPTY, generator);
        assertTrue(models.supports(feature));
        for (Block ground : List.of(Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
                Blocks.MOSS_BLOCK, Blocks.MUD, Blocks.COARSE_DIRT)) {
            for (int repeat = 0; repeat < 2; repeat++) {
                var level = level(ground);
                var expectedSoil = provider.getOptionalState(level, RandomSource.create(7), ORIGIN.below());
                if (expectedSoil == null) expectedSoil = ground.defaultBlockState();
                models.place(feature, level, RandomSource.create(7), ORIGIN);
                assertTrue(level.placed().values().stream().anyMatch(state -> state.is(Blocks.OAK_LOG)),
                        ground + " " + models.diagnostics());
                assertEquals(expectedSoil, level.getBlockState(ORIGIN.below()),
                        "nullable provider must preserve actual soil on " + ground);
            }
        }
        assertEquals(12L, models.performanceSnapshot().get("placed"));
        assertEquals(6L, models.performanceSnapshot().get("hits"));
        assertEquals(0L, models.performanceSnapshot().get("environmentExactFallbacks"));
    }

    @Test void unusualAndWetEnvironmentsUseTheOriginalFeatureWithoutChangingItsRandomStream() {
        var feature = tree(RuleBasedStateProvider.ifTrueThenProvide(
                BlockPredicate.not(BlockPredicate.matchesTag(BlockTags.CANNOT_REPLACE_BELOW_TREE_TRUNK)), Blocks.DIRT));
        for (boolean wet : new boolean[] { false, true }) {
            var exact = level(wet ? Blocks.GRASS_BLOCK : Blocks.STONE);
            var actual = level(wet ? Blocks.GRASS_BLOCK : Blocks.STONE);
            if (wet) {
                exact.setBlock(ORIGIN, Blocks.WATER.defaultBlockState(), 19, 0);
                actual.setBlock(ORIGIN, Blocks.WATER.defaultBlockState(), 19, 0);
            }
            var expectedRandom = RandomSource.create(91);
            var actualRandom = RandomSource.create(91);
            placeExact(feature, exact, expectedRandom);
            var models = new PredictionTreeModels(RegistryAccess.EMPTY, generator);
            models.place(feature, actual, actualRandom, ORIGIN);
            assertEquals(exact.placed(), actual.placed(), "fallback must retain real feature semantics, wet=" + wet);
            assertEquals(expectedRandom.nextLong(), actualRandom.nextLong(), "cache admission cannot consume RNG");
            assertEquals(1L, models.performanceSnapshot().get("environmentExactFallbacks"));
            assertEquals(0L, models.performanceSnapshot().get("built"));
        }
    }

    @Test void saplingSurvivalStillRejectsInvalidGroundBeforeAnyModelOrSoilIsWritten() {
        var base = tree(simple(Blocks.DIRT));
        var feature = new PlacedFeature(base.feature(), List.of(
                net.minecraft.data.worldgen.placement.PlacementUtils.filteredByBlockSurvival(Blocks.OAK_SAPLING)));
        var models = new PredictionTreeModels(RegistryAccess.EMPTY, generator);
        var level = level(Blocks.STONE);
        models.place(feature, level, RandomSource.create(1), ORIGIN);
        assertTrue(level.placed().isEmpty());
        assertEquals(0L, models.performanceSnapshot().get("built"));
        assertEquals(1L, models.performanceSnapshot().get("placementFiltered"));
    }

    @Test void providerRunsOnceAfterPreflightAndNeverConsumesRandomnessForAnObstructedModel() {
        var calls = new AtomicInteger();
        var counting = countingProvider(calls);
        var feature = tree(RuleBasedStateProvider.ifTrueThenProvide(BlockPredicate.alwaysTrue(), counting));
        var models = new PredictionTreeModels(RegistryAccess.EMPTY, generator);
        var random = RandomSource.create(19);
        var expected = RandomSource.create(19);
        var level = level(Blocks.GRASS_BLOCK);
        models.place(feature, level, random, ORIGIN);
        assertEquals(1, calls.get(), "one below-trunk attempt must evaluate the provider exactly once");
        expected.nextInt(2);
        assertEquals(expected.nextLong(), random.nextLong(), "model replay must consume only the actual soil decision");

        calls.set(0);
        var blocked = level(Blocks.GRASS_BLOCK);
        blocked.setBlock(ORIGIN.above(), Blocks.STONE.defaultBlockState(), 19, 0);
        var before = java.util.Map.copyOf(blocked.placed());
        random = RandomSource.create(19);
        expected = RandomSource.create(19);
        models.place(feature, blocked, random, ORIGIN);
        assertEquals(before, blocked.placed(), "obstructed trunk must not leave a soil edit or partial canopy");
        assertEquals(0, calls.get(), "soil provider cannot run before trunk preflight succeeds");
        assertEquals(expected.nextLong(), random.nextLong());
    }

    @Test void repairedTreesRejectStaleDecorationAndFinishedVoxelsButKeepTerrain(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        int flag = PredictionTreeModels.SETTINGS_VERSION;
        assertEquals(1 << 17, flag);
        int otherVersions = PredictionVegetation.PREDICATE_SETTINGS_VERSION
                | PredictionVegetation.STRUCTURE_RUNTIME_SETTINGS_VERSION | PredictionVegetation.END_CLIMATE_SETTINGS_VERSION
                | PredictionVegetation.REGISTRY_CONTEXT_SETTINGS_VERSION | PredictionVegetation.DECORATION_WRITE_SETTINGS_VERSION
                | PredictionVegetation.STABLE_GROUND_SETTINGS_VERSION;
        assertEquals(0, otherVersions & flag, "tree revision must have a dedicated settings bit");
        int repaired = 3 | PredictionVegetation.runtimeSettings();
        int old = repaired & ~flag;
        assertEquals(flag, repaired ^ old);
        assertEquals(flag, PredictionVegetation.runtimeSettings(net.minecraft.world.level.Level.OVERWORLD.identifier()) & flag);
        var key = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD, 0, 0, 0);
        var tile = PredictionVoxelCacheTest.tile(key, 8, true);
        var identity = new byte[32];
        try (var disk = new PredictionDiskCache(directory,42)) {
            try (var oldSurface = disk.lease(PredictionDiskCache.Key.surface(0,0,old | 28))) {
                assertTrue(disk.writeSurface(oldSurface, java.util.Map.of(), true));
                assertNotNull(disk.readSurface(oldSurface));
            }
            try (var repairedSurface = disk.lease(PredictionDiskCache.Key.surface(0,0,repaired | 28))) {
                assertNull(disk.readSurface(repairedSurface), "old missing-tree output cannot satisfy the repaired namespace");
            }
            try (var voxels = disk.lease(PredictionDiskCache.Key.voxels(0,0,0))) {
                assertTrue(disk.writeVoxelsLater(voxels,tile,old,identity).get(10,java.util.concurrent.TimeUnit.SECONDS));
                assertNotNull(disk.readVoxels(voxels,key,old,identity));
                assertNull(disk.readVoxels(voxels,key,repaired,identity), "finished voxels with missing trees must rebuild");
            }
            try (var terrain = disk.lease(PredictionDiskCache.Key.terrain(0,0,0))) {
                assertTrue(disk.writeTerrain(terrain,tile.samples()));
                assertNotNull(disk.readTerrain(terrain,tile.samples().length), "tree repair must retain low-level terrain records");
            }
        }
    }

    private static FeatureStampLevel level(Block ground) {
        return new FeatureStampLevel(42, RegistryAccess.EMPTY, ground.defaultBlockState());
    }

    private static PlacedFeature tree(BlockStateProvider soil) {
        var feature = new ModernFeatureFixtures.TreeBuilder(
                simple(Blocks.OAK_LOG), new StraightTrunkPlacer(4, 0, 0), simple(Blocks.OAK_LEAVES),
                new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                new TwoLayersFeatureSize(1, 0, 1), soil).ignoreVines().build();
        return new PlacedFeature(Holder.direct(feature), List.of());
    }

    private static BlockStateProvider simple(Block block) { return BlockStateProvider.of(block); }

    private static void placeExact(PlacedFeature feature, FeatureStampLevel level, RandomSource random) {
        feature.feature().value().place(level, generator, random, ORIGIN);
    }

    private static BlockStateProvider countingProvider(AtomicInteger calls) {
        return new BlockStateProvider() {
            @Override public com.mojang.serialization.MapCodec<? extends BlockStateProvider> codec() {
                return com.mojang.serialization.MapCodec.unit(this);
            }
            @Override public BlockState getState(net.minecraft.world.level.LevelAccessor level,
                    RandomSource random, BlockPos pos) {
                calls.incrementAndGet();
                return (random.nextInt(2) == 0 ? Blocks.DIRT : Blocks.ROOTED_DIRT).defaultBlockState();
            }
        };
    }
}
