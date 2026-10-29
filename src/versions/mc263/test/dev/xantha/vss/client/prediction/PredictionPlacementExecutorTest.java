package dev.xantha.vss.client.prediction;

import net.minecraft.world.level.levelgen.feature.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;

import net.minecraft.world.level.levelgen.feature.Feature;


import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionPlacementExecutorTest {
    @BeforeAll static void bootstrap() { PredictionDecorationQueryTest.bootstrap(); }
    static final BlockPos ORIGIN = new BlockPos(-16, 101, -16);
    record Candidate(BlockPos pos, int terminalRandom) { }

    @Test void unavailableMixinHostCannotBreakVanillaFallback() {
        assertDoesNotThrow(() -> PredictionPlacementExecutor.unmodified(PlacedFeature.class));
        assertDoesNotThrow(() -> PredictionPlacementExecutor.available());
    }

    static final class Fixture {
        final ClientTerrainSampler terrain = new PredictionVegetationLocalInvalidationTest.Fixture().terrain;
        final ChunkGenerator generator = terrain.generatorContext();
        PredictionDecorationLevel level() {
            return new PredictionDecorationLevel(terrain, terrain, PredictionDecorationQueryTest.access, -1, -1);
        }
    }

    static PlacedFeature feature(List<PlacementModifier> modifiers) {
        var block = new SimpleBlockFeature(net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider.of(Blocks.STONE));
        var nested = new PlacedFeature(Holder.direct(block), List.of(
                BlockPredicateFilter.forPredicate(BlockPredicate.ONLY_IN_AIR_PREDICATE)));
        var patch = new SimpleRandomSelectorFeature(net.minecraft.core.HolderSet.direct(Holder.direct(nested)));
        return new PlacedFeature(Holder.direct(patch), modifiers);
    }

    static List<PlacementModifier> surfaceModifiers() {
        return List.of(CountPlacement.of(UniformInt.of(8, 16)), InSquarePlacement.spread(),
                HeightmapPlacement.onHeightmap(Heightmap.Types.WORLD_SURFACE_WG),
                RarityFilter.onAverageOnceEvery(2),
                BlockPredicateFilter.forPredicate(BlockPredicate.ONLY_IN_AIR_PREDICATE));
    }

    @Test void everyCandidateAndTerminalRandomDrawMatchTheVanillaMaterializedPipeline() {
        var opaque = new EmittingModifier();
        var mixed = List.<PlacementModifier>of(CountPlacement.of(UniformInt.of(3, 7)), InSquarePlacement.spread(),
                HeightRangePlacement.uniform(VerticalAnchor.absolute(100), VerticalAnchor.absolute(107)),
                EnvironmentScanPlacement.scanningFor(Direction.DOWN, BlockPredicate.matchesBlocks(Blocks.GRASS_BLOCK), 8),
                OffsetPlacement.vertical(UniformInt.of(1, 2)), opaque,
                HeightmapPlacement.onHeightmap(Heightmap.Types.WORLD_SURFACE_WG),
                RarityFilter.onAverageOnceEvery(3), CountPlacement.of(2));
        for (var modifiers : List.of(surfaceModifiers(), mixed,
                List.<PlacementModifier>of(CountOnEveryLayerPlacement.of(UniformInt.of(1, 3)),
                        SurfaceRelativeThresholdFilter.of(Heightmap.Types.WORLD_SURFACE_WG, -1, 3)),
                List.<PlacementModifier>of(NoiseBasedCountPlacement.of(2, 80, 1),
                        NoiseThresholdCountPlacement.of(-.2, 1, 3), SurfaceWaterDepthFilter.forMaxDepth(1)))) {
            var feature = feature(modifiers);
            var plan = PredictionPlacementExecutor.compile(modifiers, ignored -> true);
            var fixture = new Fixture();
            for (long seed = 0; seed < 64; seed++) {
                var original = RandomSource.create(seed); var candidate = RandomSource.create(seed);
                var expected = new ArrayList<Candidate>();
                var actual = new ArrayList<Candidate>();
                var oldContext = new PlacementContext(fixture.level(), fixture.generator, Optional.of(feature));
                var newContext = new PlacementContext(fixture.level(), fixture.generator, Optional.of(feature));
                PredictionPlacementExecutor.stream(feature, oldContext, original, ORIGIN,
                        pos -> expected.add(new Candidate(pos.immutable(), original.nextInt(997))));
                plan.visit(newContext, candidate, ORIGIN,
                        pos -> actual.add(new Candidate(pos.immutable(), candidate.nextInt(997))));
                assertEquals(expected, actual, "seed=" + seed + " modifiers=" + modifiers);
                continuation(original, candidate);
            }
        }
        assertTrue(opaque.completions.get() > 0, "opaque modifier callbacks must complete before downstream placement");
    }

    @Test void actualVanillaFeaturePlacerAgreesWhenModifiersEmitSeveralRandomCandidates() {
        var fixture = new Fixture();
        var active = new java.util.concurrent.atomic.AtomicReference<List<Candidate>>();
        var terminal = new ModernFeatureFixtures.FeatureAdapter() {
            @Override public boolean place(ModernFeatureFixtures.Context context) {
                active.get().add(new Candidate(context.origin().immutable(), context.random().nextInt(997)));
                return true;
            }
        };
        for (var modifiers : List.of(
                List.<PlacementModifier>of(CountPlacement.of(3), new EmittingModifier()),
                List.<PlacementModifier>of(new EmittingModifier(), InSquarePlacement.spread(), new EmittingModifier()),
                List.<PlacementModifier>of(CountPlacement.of(UniformInt.of(2, 4)), new EmittingModifier(),
                        OffsetPlacement.vertical(UniformInt.of(1, 3)), CountPlacement.of(2)))) {
            var feature = new PlacedFeature(Holder.direct(terminal), modifiers);
            var plan = PredictionPlacementExecutor.compile(modifiers, ignored -> true);
            for (int seed = 0; seed < 64; seed++) {
                var original = RandomSource.create(seed); var candidate = RandomSource.create(seed);
                var expected = new ArrayList<Candidate>(); var actual = new ArrayList<Candidate>();
                active.set(expected);
                new FeaturePlacer(fixture.level(), fixture.generator).placeWithBiomeCheck(feature, original, ORIGIN);
                active.set(actual);
                plan.visit(new PlacementContext(fixture.level(), fixture.generator, Optional.of(feature)), candidate, ORIGIN,
                        position -> terminal.place(fixture.level(), fixture.generator, candidate, position));
                assertFalse(expected.isEmpty());
                assertEquals(expected, actual, "actual FeaturePlacer seed=" + seed + " modifiers=" + modifiers);
                continuation(original, candidate);
            }
        }
    }

    @Test void biomeFilterKeepsTheRegisteredTopFeatureAndItsAcceptanceDecision() {
        var fixture = new Fixture();
        var modifiers = List.<PlacementModifier>of(CountPlacement.of(4), InSquarePlacement.spread(), BiomeFilter.biome());
        var feature = feature(modifiers);
        var registered = fixture.generator.getBiomeGenerationSettings(fixture.terrain.noiseBiome(-4, 25, -4))
                .features().get(0).stream().findFirst().orElseThrow().value();
        var plan = PredictionPlacementExecutor.compile(modifiers, ignored -> true);
        assertEquals(3, plan.fastOperations());
        for (var top : List.of(registered, feature)) for (int seed = 0; seed < 32; seed++) {
            var original = RandomSource.create(seed); var candidate = RandomSource.create(seed);
            var expected = new ArrayList<Candidate>(); var actual = new ArrayList<Candidate>();
            PredictionPlacementExecutor.stream(feature,
                    new PlacementContext(fixture.level(), fixture.generator, Optional.of(top)), original, ORIGIN,
                    pos -> expected.add(new Candidate(pos.immutable(), original.nextInt())));
            plan.visit(new PlacementContext(fixture.level(), fixture.generator, Optional.of(top)), candidate, ORIGIN,
                    pos -> actual.add(new Candidate(pos.immutable(), candidate.nextInt())));
            assertEquals(top == registered ? 4 : 0, expected.size());
            assertEquals(expected, actual);
            continuation(original, candidate);
        }
    }

    @Test void independentCompatibilityMethodsDoNotAuthorizeAnOverwriteOrUnknownMixin() throws Exception {
        var compatibility = GuardShapes.class.getDeclaredMethod("byepregen$mayProduceMultipleOrigins");
        String audited = "com.moepus.byepregen.mixin.feature.placement.RepeatingPlacementPlanCompatibilityMixin";
        assertTrue(PredictionPlacementExecutor.independentAddition(CountPlacement.class, audited, compatibility));
        assertFalse(PredictionPlacementExecutor.independentAddition(CountPlacement.class, "unknown.mixin.RepeatingPlacementPlanCompatibilityMixin", compatibility));
        assertFalse(PredictionPlacementExecutor.independentAddition(InSquarePlacement.class, audited, compatibility));
        var original = CountPlacement.class.getDeclaredMethods();
        for (var method : original)
            assertFalse(PredictionPlacementExecutor.independentAddition(CountPlacement.class, audited, method));
        for (var method : PlacedFeature.class.getDeclaredMethods())
            assertFalse(PredictionPlacementExecutor.independentAddition(PlacedFeature.class,
                    "com.moepus.byepregen.mixin.feature.placement.PlacedFeatureMixin", method));
    }

    @Test void wholeVanillaFeatureWritesAndRandomContinuationMatchAtNegativeCoordinates() {
        var fixture = new Fixture();
        var feature = feature(surfaceModifiers());
        var plan = PredictionPlacementExecutor.compile(feature.placement(), ignored -> true);
        assertEquals(feature.placement().size(), plan.fastOperations());
        for (long seed = 0; seed < 48; seed++) {
            var original = fixture.level();
            var candidate = fixture.level();
            var oldRandom = RandomSource.create(seed); var newRandom = RandomSource.create(seed);
            replay(feature, original, fixture.generator, oldRandom, null);
            replay(feature, candidate, fixture.generator, newRandom, plan);
            assertEquals(original.placed(), candidate.placed(), "seed=" + seed);
            assertFalse(original.placed().isEmpty(), "fixture must exercise actual configured placement");
            continuation(oldRandom, newRandom);
        }
    }

    @Test void guardedClassesAndOpaqueCustomModifiersKeepTheirOriginalMethods() {
        var opaque = new EmittingModifier();
        var modifiers = List.<PlacementModifier>of(CountPlacement.of(2), opaque, InSquarePlacement.spread());
        var feature = feature(modifiers);
        var plan = PredictionPlacementExecutor.compile(modifiers, ignored -> false);
        assertEquals(0, plan.fastOperations(), "a transformed class must not bypass modify");
        var fixture = new Fixture();
        var original = RandomSource.create(17); var candidate = RandomSource.create(17);
        var expected = new ArrayList<Candidate>(); var actual = new ArrayList<Candidate>();
        PredictionPlacementExecutor.stream(feature, new PlacementContext(fixture.level(), fixture.generator, Optional.of(feature)),
                original, ORIGIN, pos -> expected.add(new Candidate(pos.immutable(), original.nextInt())));
        plan.visit(new PlacementContext(fixture.level(), fixture.generator, Optional.of(feature)), candidate, ORIGIN,
                pos -> actual.add(new Candidate(pos.immutable(), candidate.nextInt())));
        assertEquals(expected, actual);
        assertEquals(4, opaque.completions.get());
        continuation(original, candidate);
    }

    @Test void terminalFailureFinishesTheCurrentModifierAndDoesNotConsumeLaterCandidates() {
        var opaque = new EmittingModifier();
        var modifiers = List.<PlacementModifier>of(CountPlacement.of(8), opaque);
        var feature = feature(modifiers);
        var plan = PredictionPlacementExecutor.compile(modifiers, ignored -> true);
        var fixture = new Fixture();
        var original = RandomSource.create(9); var candidate = RandomSource.create(9);
        var failure = new UnsupportedOperationException("terminal failure");
        assertSame(failure, assertThrows(UnsupportedOperationException.class,
                () -> PredictionPlacementExecutor.stream(feature,
                        new PlacementContext(fixture.level(), fixture.generator, Optional.of(feature)), original, ORIGIN,
                        pos -> { original.nextInt(); throw failure; })));
        assertSame(failure, assertThrows(UnsupportedOperationException.class,
                () -> plan.visit(new PlacementContext(fixture.level(), fixture.generator, Optional.of(feature)), candidate, ORIGIN,
                        pos -> { candidate.nextInt(); throw failure; })));
        assertEquals(2, opaque.completions.get());
        continuation(original, candidate);
    }

    @Test void nestedContextsKeepEmptyTopFeatureAndCustomTerminalRandomDrawOrder() {
        var fixture = new Fixture();
        var modifiers = List.<PlacementModifier>of(CountPlacement.of(UniformInt.of(2, 5)),
                new ContextModifier(false), InSquarePlacement.spread(), RarityFilter.onAverageOnceEvery(2));
        var feature = feature(modifiers);
        var plan = PredictionPlacementExecutor.compile(modifiers, ignored -> true);
        for (int seed = 0; seed < 64; seed++) {
            var original = RandomSource.create(seed); var candidate = RandomSource.create(seed);
            var expected = new ArrayList<Candidate>(); var actual = new ArrayList<Candidate>();
            PredictionPlacementExecutor.stream(feature,
                    new PlacementContext(fixture.level(), fixture.generator, Optional.empty()), original, ORIGIN,
                    pos -> expected.add(new Candidate(pos.immutable(), original.nextInt())));
            plan.visit(new PlacementContext(fixture.level(), fixture.generator, Optional.empty()), candidate, ORIGIN,
                    pos -> actual.add(new Candidate(pos.immutable(), candidate.nextInt())));
            assertEquals(expected, actual);
            continuation(original, candidate);
        }
    }

    static void replay(PlacedFeature feature, PredictionDecorationLevel level, ChunkGenerator generator, RandomSource random,
                       PredictionPlacementExecutor.Plan plan) {
        level.beginFeature();
        boolean success = false;
        try {
            if (plan == null) new FeaturePlacer(level, generator).placeWithBiomeCheck(feature, random, ORIGIN);
            else plan.visit(new PlacementContext(level, generator, Optional.of(feature)), random, ORIGIN,
                    pos -> feature.feature().value().place(level, generator, random, pos));
            success = true;
        } finally { level.endFeature(success); }
    }

    private static void continuation(RandomSource expected, RandomSource actual) {
        for (int i = 0; i < 16; i++) assertEquals(expected.nextLong(), actual.nextLong());
    }

    private static final class EmittingModifier implements PlacementModifier {
        final AtomicInteger completions = new AtomicInteger();
        @Override public void modify(PlacementContext context, RandomSource random, BlockPos position, Consumer<BlockPos> output) {
            for (int index = 0; index < 2; index++)
                output.accept(position.offset(random.nextInt(3), index, random.nextInt(3)));
            completions.incrementAndGet();
        }
        @Override public com.mojang.serialization.MapCodec<? extends PlacementModifier> codec() {
            return com.mojang.serialization.MapCodec.unit(this);
        }
    }

    private static final class GuardShapes {
        public boolean byepregen$mayProduceMultipleOrigins() { return true; }
    }

    static final class ContextModifier implements PlacementModifier {
        private final boolean registered;
        ContextModifier(boolean registered) { this.registered = registered; }
        @Override public void modify(PlacementContext context, RandomSource random, BlockPos position, Consumer<BlockPos> output) {
            assertEquals(registered, context.topFeature().isPresent());
            output.accept(position);
        }
        @Override public com.mojang.serialization.MapCodec<? extends PlacementModifier> codec() {
            return com.mojang.serialization.MapCodec.unit(this);
        }
    }
}
