package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.TrapezoidHeight;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionFeatureBiomeBoundsTest {
    private static net.minecraft.core.HolderLookup.Provider lookup;
    private static WorldGenerationContext context;

    @BeforeAll static void bootstrap() {
        dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap();
        lookup = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
        var level = new dev.xantha.vss.client.prediction.feature.FeatureStampLevel(42,
                net.minecraft.core.RegistryAccess.EMPTY, net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
        context = new WorldGenerationContext(dev.xantha.vss.testutil.TerrainPredictionFixtures.noise(), level);
    }

    @Test void actualSulfurAndCaveVineResourcesIncludeAllPossibleBiomeFilterLocations() {
        var sulfur = feature("sulfur_pool");
        var vines = feature("classic_vines_cave_feature");
        var origin = new BlockPos(-32, -64, 48);
        var sulfurPlan = PredictionFeatureBiomeBounds.compile(sulfur, c -> true);
        var vinePlan = PredictionFeatureBiomeBounds.compile(vines, c -> true);
        assertTrue(sulfurPlan.known()); assertTrue(vinePlan.known());
        assertEquals(new PredictionFeatureBiomeBounds.Bounds(-32, -65, 48, -17, 287, 63),
                sulfurPlan.bounds(origin, context, -64, 319));
        assertEquals(new PredictionFeatureBiomeBounds.Bounds(-32, -64, 48, -17, 256, 63),
                vinePlan.bounds(origin, context, -64, 319));
    }

    @Test void allDirectionsAndOffsetProvidersExtendOnlyTheirPossibleAxes() {
        for (String direction : List.of("up", "down", "east", "west", "north", "south")) {
            var scan = JsonParser.parseString("{\"type\":\"minecraft:environment_scan\",\"direction_of_search\":\""
                    + direction + "\",\"max_steps\":32}").getAsJsonObject();
            var end = JsonParser.parseString("{\"type\":\"minecraft:biome\"}").getAsJsonObject();
            var plan = new PredictionFeatureBiomeBounds.Plan(List.of(scan, end));
            var b = plan.bounds(BlockPos.ZERO, context, -64, 319);
            assertNotNull(b);
            assertEquals(direction.equals("west") ? -32 : 0, b.minX());
            assertEquals(direction.equals("east") ? 32 : 0, b.maxX());
            assertEquals(direction.equals("down") ? -32 : 0, b.minY());
            assertEquals(direction.equals("up") ? 32 : 0, b.maxY());
            assertEquals(direction.equals("north") ? -32 : 0, b.minZ());
            assertEquals(direction.equals("south") ? 32 : 0, b.maxZ());
        }
        var offset = new OffsetPlacement(UniformInt.of(-3, 7), ConstantInt.of(-1), UniformInt.of(2, 6));
        var modified = new PlacedFeature(feature("sulfur_pool").feature(), List.of(offset, BiomeFilter.biome()));
        assertEquals(new PredictionFeatureBiomeBounds.Bounds(-3,-1,2,7,-1,6),
                PredictionFeatureBiomeBounds.compile(modified, c -> true).bounds(BlockPos.ZERO, context, -64, 319));
    }

    @Test void constantUniformAndHeightmapRangesUseTheActualGenerationContext() {
        var base = feature("classic_vines_cave_feature");
        for (var anchor : List.of(VerticalAnchor.absolute(-12), VerticalAnchor.aboveBottom(4),
                VerticalAnchor.belowTop(3), VerticalAnchor.relativeToSeaLevel(7))) {
            var feature = new PlacedFeature(base.feature(), List.of(
                    HeightRangePlacement.of(ConstantHeight.of(anchor)), BiomeFilter.biome()));
            int y = anchor.resolveY(context);
            assertEquals(new PredictionFeatureBiomeBounds.Bounds(0,y,0,0,y,0),
                    PredictionFeatureBiomeBounds.compile(feature,c -> true).bounds(BlockPos.ZERO,context,-64,319));
        }
        var heightmap = new PlacedFeature(base.feature(), List.of(
                HeightmapPlacement.onHeightmap(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG), BiomeFilter.biome()));
        assertEquals(new PredictionFeatureBiomeBounds.Bounds(0,-64,0,0,320,0),
                PredictionFeatureBiomeBounds.compile(heightmap,c -> true).bounds(BlockPos.ZERO,context,-64,319));
    }

    @Test void unknownModifiedOrUnboundedImplementationsNeverProduceAProof() {
        var base = feature("classic_vines_cave_feature");
        assertFalse(PredictionFeatureBiomeBounds.compile(base, c -> c != HeightRangePlacement.class).known());
        assertFalse(PredictionFeatureBiomeBounds.compile(base, c -> c != UniformInt.class && c != ConstantInt.class).known());
        var unknownHeight = new PlacedFeature(base.feature(), List.of(HeightRangePlacement.of(
                TrapezoidHeight.of(VerticalAnchor.bottom(),VerticalAnchor.top(),0)),BiomeFilter.biome()));
        assertFalse(PredictionFeatureBiomeBounds.compile(unknownHeight,c -> true).known());
        var noBiome = new PlacedFeature(base.feature(), List.of(CountPlacement.of(256),InSquarePlacement.spread()));
        assertFalse(PredictionFeatureBiomeBounds.compile(noBiome,c -> true).known());
        var overflow = PredictionFeatureBiomeBounds.compile(new PlacedFeature(base.feature(),
                List.of(InSquarePlacement.spread(),BiomeFilter.biome())),c -> true);
        assertNull(overflow.bounds(new BlockPos(Integer.MAX_VALUE,0,0),context,-64,319));
    }

    @Test void quartEnvelopeCoversAllEightBiomeManagerCornersAtNegativeAndPositiveBoundaries() {
        for (int min=-19; min<=19; min++) for (int max=min; max<=Math.min(19,min+7); max++) {
            var blocks = new PredictionFeatureBiomeBounds.Bounds(min,min,min,max,max,max);
            var q = PredictionFeatureBiomeBounds.quartEnvelope(blocks);
            for (int block=min; block<=max; block++) for (int corner=0; corner<2; corner++) {
                int possible = Math.floorDiv(block - 2,4) + corner;
                assertTrue(possible>=q.minX() && possible<=q.maxX());
                assertTrue(possible>=q.minY() && possible<=q.maxY());
                assertTrue(possible>=q.minZ() && possible<=q.maxZ());
            }
        }
    }

    private static PlacedFeature feature(String path) {
        return lookup.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(ResourceKey.create(
                Registries.PLACED_FEATURE,Identifier.withDefaultNamespace(path))).value();
    }
}
