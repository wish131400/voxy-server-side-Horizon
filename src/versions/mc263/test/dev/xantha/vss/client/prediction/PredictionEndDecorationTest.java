package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.google.gson.JsonObject;
import com.mojang.serialization.Lifecycle;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.junit.jupiter.api.*;

class PredictionEndDecorationTest {
    private static HolderLookup.Provider lookup;
    private static Map<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>,
            List<Holder<net.minecraft.world.level.block.Block>>> previousTags;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path gameDirectory;
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        lookup = VanillaRegistries.createWorldLookup();
        // Bootstrap does not run the game's resource tag reload. Chorus placement
        // now checks support tags, so use the actual vanilla definitions here.
        previousTags = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getTags()
                .collect(java.util.stream.Collectors.toMap(tag -> tag.key(), tag -> tag.stream().toList()));
        var tags = new HashMap<>(previousTags);
        for (String name : List.of("supports_chorus_plant", "supports_chorus_flower")) {
            var key = net.minecraft.tags.TagKey.create(Registries.BLOCK,
                    net.minecraft.resources.Identifier.withDefaultNamespace(name));
            try (var input = PredictionEndDecorationTest.class.getResourceAsStream(
                    "/data/minecraft/tags/block/" + name + ".json")) {
                assertNotNull(input, name);
                var entries = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(
                        input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("values");
                var holders = new ArrayList<Holder<net.minecraft.world.level.block.Block>>();
                for (var entry : entries) holders.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .get(net.minecraft.resources.Identifier.parse(entry.getAsString())).orElseThrow());
                tags.put(key, List.copyOf(holders));
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        PredictionTestTags.bind(tags);
    }

    @AfterAll static void restoreTags() { PredictionTestTags.bind(previousTags); }

    @Test void actualChorusFeatureProducesPlantsOnEndStone() {
        var terrain = sampler(RegistryAccess.EMPTY, new JsonObject());
        var vegetation = new PredictionVegetation(terrain, null, false);
        var blocks = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) blocks.putAll(vegetation.chunk(x, z));
        assertTrue(blocks.values().stream().anyMatch(s -> s.is(Blocks.CHORUS_PLANT)), vegetation.diagnostics());
        assertTrue(blocks.values().stream().anyMatch(s -> s.is(Blocks.CHORUS_FLOWER)), vegetation.diagnostics());
        assertEquals(0L, vegetation.performanceSnapshot().get("failedFeatures"));
    }

    @Test void endCityUsesOriginalTemplatesAndSurfaceStructurePipeline() throws Exception {
        var structures = new MappedRegistry<Structure>(Registries.STRUCTURE, Lifecycle.stable());
        var originalCity = lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.END_CITY);
        // The standalone vanilla lookup has no datapack biome tags. Bind the
        // original End City type explicitly to its actual highlands biome.
        var city = new net.minecraft.world.level.levelgen.structure.structures.EndCityStructure(
                new Structure.StructureSettings(HolderSet.direct(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.END_HIGHLANDS)),
                        Map.of(), GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE));
        structures.register(BuiltinStructures.END_CITY, city, RegistrationInfo.BUILT_IN);
        var sets = new MappedRegistry<StructureSet>(Registries.STRUCTURE_SET, Lifecycle.stable());
        var endCities = lookup.lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(BuiltinStructureSets.END_CITIES);
        sets.register(endCities.key(), new StructureSet(List.of(
                new StructureSet.StructureSelectionEntry(Holder.direct(city), 1)), endCities.value().placement()), RegistrationInfo.BUILT_IN);
        var access = new RegistryAccess.ImmutableRegistryAccess(List.of(structures.freeze(), sets.freeze()));
        var templates = new JsonObject();
        for (String name : List.of("base_floor", "base_roof", "bridge_end", "bridge_gentle_stairs", "bridge_piece",
                "bridge_steep_stairs", "fat_tower_base", "fat_tower_middle", "fat_tower_top", "second_floor_1",
                "second_floor_2", "second_roof", "ship", "third_floor_1", "third_floor_2", "third_roof",
                "tower_base", "tower_piece", "tower_top")) {
            String id = "minecraft:end_city/" + name;
            try (var input = getClass().getResourceAsStream("/data/minecraft/structure/end_city/" + name + ".nbt")) {
                assertNotNull(input, id);
                templates.addProperty(id, Base64.getEncoder().encodeToString(input.readAllBytes()));
            }
        }
        // Template storage is a loader boundary. Supply the same game-directory
        // contract here; plain unit tests do not launch a Fabric game provider.
        try (var loader = mockStatic(net.fabricmc.loader.api.FabricLoader.class)) {
        var fabric = mock(net.fabricmc.loader.api.FabricLoader.class);
        when(fabric.getGameDir()).thenReturn(gameDirectory);
        loader.when(net.fabricmc.loader.api.FabricLoader::getInstance).thenReturn(fabric);
        var terrain = sampler(access, templates);
        var prediction = new PredictionSurfaceStructures(terrain);
        assertTrue(prediction.available(), prediction.diagnostics());
        var placement = (RandomSpreadStructurePlacement) endCities.value().placement();
        var candidate = placement.getPotentialStructureChunk(42, 0, 0);
        var blocks = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (int z = candidate.z() - 1; z <= candidate.z() + 1; z++) for (int x = candidate.x() - 1; x <= candidate.x() + 1; x++) {
            var level = new PredictionDecorationLevel(terrain, terrain, access, x, z);
            prediction.place(level, x, z, 0);
            blocks.putAll(level.placed());
        }
        assertTrue(blocks.size() > 50, prediction.diagnostics());
        assertTrue(blocks.values().stream().anyMatch(s -> s.is(Blocks.PURPUR_BLOCK)), prediction.diagnostics());
        assertTrue(blocks.values().stream().anyMatch(s -> s.is(Blocks.END_STONE_BRICKS)), prediction.diagnostics());
        assertTrue(prediction.diagnostics().contains("skippedStructures=0"), prediction.diagnostics());
        }
    }

    private static ClientTerrainSampler sampler(RegistryAccess access, JsonObject templates) {
        var source = new FixedBiomeSource(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.END_HIGHLANDS));
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.END);
        var random = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), 42, settings.value());
        var generator = spy(new NoiseBasedChunkGenerator(source, settings));
        // Isolate placement from terrain shape: every candidate sits on an island.
        doReturn(72).when(generator).getBaseHeight(anyInt(), anyInt(), any(Heightmap.Types.class),
                any(LevelHeightAccessor.class), same(random));
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                Level.END.identifier(), 42, 0, 256, "noise", "minecraft:end", 1);
        return new ClientTerrainSampler(42, profile) {
            @Override public ClientColumnSample sample(int x, int z) {
                int stone = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.END_STONE);
                return new ClientColumnSample(72, 72, 0, stone, 0, 0, 0, 0, 0, 0, 0, stone, stone,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
            @Override public ClientColumnSample sampleSurface(int x, int z) { return sample(x, z); }
            @Override public ClientColumnSample sampleForLod(int x, int z, int spacing) { return sample(x, z); }
            @Override ClientColumnSample sampleInterior(int x, int z) { return sample(x, z); }
            @Override NoiseBasedChunkGenerator generatorContext() { return generator; }
            @Override RandomState randomStateContext() { return random; }
            @Override net.minecraft.world.level.biome.Climate.Sampler climateContext() {
                return random.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED);
            }
            @Override BiomeSource biomeSourceContext() { return source; }
            @Override Holder<Biome> noiseBiome(int x, int y, int z) {
                return source.getNoiseBiome(x, y, z);
            }
            @Override RegistryAccess decorationAccess() { return access; }
            @Override JsonObject structureTemplates() { return templates; }
        };
    }
}
