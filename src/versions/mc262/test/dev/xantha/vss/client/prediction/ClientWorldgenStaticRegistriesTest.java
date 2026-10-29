package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.*;
import com.mojang.serialization.*;
import dev.xantha.vss.common.worldgen.VanillaFeatureSnapshot;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.*;

class ClientWorldgenStaticRegistriesTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path gameDirectory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void vanillaDecorationRoundTripsWithOnlyNetworkBiomeFallback() throws Exception {
        var source = TerrainPredictionFixtures.sourceLookup();
        var ops = source.createSerializationContext(JsonOps.INSTANCE);
        var root = new JsonObject();
        root.add("noises", encode(source.lookupOrThrow(Registries.NOISE), NormalNoise.NoiseParameters.DIRECT_CODEC, ops));
        root.add("density_functions", encode(source.lookupOrThrow(Registries.DENSITY_FUNCTION), DensityFunctions.DIRECT_CODEC, ops));
        root.add("configured_carvers", encode(source.lookupOrThrow(Registries.CONFIGURED_CARVER), ConfiguredWorldCarver.DIRECT_CODEC, ops));
        root.add("configured_features", encode(source.lookupOrThrow(Registries.CONFIGURED_FEATURE), ConfiguredFeature.DIRECT_CODEC, ops));
        root.add("placed_features", encode(source.lookupOrThrow(Registries.PLACED_FEATURE), PlacedFeature.DIRECT_CODEC, ops));
        root.add("biomes", encode(source.lookupOrThrow(Registries.BIOME), Biome.DIRECT_CODEC, ops));
        root.add("processor_lists", encode(source.lookupOrThrow(Registries.PROCESSOR_LIST), StructureProcessorType.DIRECT_CODEC, ops));
        root.add("template_pools", encode(source.lookupOrThrow(Registries.TEMPLATE_POOL), StructureTemplatePool.DIRECT_CODEC, ops));
        root.add("structures", encode(source.lookupOrThrow(Registries.STRUCTURE), Structure.DIRECT_CODEC, ops));
        root.add("structure_sets", encode(source.lookupOrThrow(Registries.STRUCTURE_SET), StructureSet.DIRECT_CODEC, ops));

        // Real login biomes omit generation settings. A fallback to these
        // holders must not make broken snapshot decoding look successful.
        var networkBiomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        source.lookupOrThrow(Registries.BIOME).listElements().forEach(holder -> networkBiomes.register(holder.key(),
                Biome.NETWORK_CODEC.parse(ops, Biome.NETWORK_CODEC.encodeStart(ops, holder.value()).getOrThrow()).getOrThrow(),
                RegistrationInfo.BUILT_IN));
        networkBiomes.freeze();
        var client = new RegistryAccess.ImmutableRegistryAccess(List.of(networkBiomes));
        var decoded = ClientWorldgenRegistries.decode(root, client, List.of());
        assertCount(decoded, Registries.CONFIGURED_CARVER, root, "configured_carvers");
        assertCount(decoded, Registries.CONFIGURED_FEATURE, root, "configured_features");
        assertCount(decoded, Registries.PLACED_FEATURE, root, "placed_features");
        assertCount(decoded, Registries.BIOME, root, "biomes");
        assertCount(decoded, Registries.PROCESSOR_LIST, root, "processor_lists");
        assertCount(decoded, Registries.TEMPLATE_POOL, root, "template_pools");
        assertCount(decoded, Registries.STRUCTURE, root, "structures");
        assertCount(decoded, Registries.STRUCTURE_SET, root, "structure_sets");
        var forestKey = ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace("forest"));
        var forest = decoded.access().lookupOrThrow(Registries.BIOME).getOrThrow(forestKey);
        assertNotSame(networkBiomes.getOrThrow(forestKey), forest, "snapshot holders must outrank network biomes");
        assertTrue(forest.value().getGenerationSettings().features().stream().anyMatch(step -> step.size() > 0));

        var generator = new JsonObject();
        generator.add("settings", NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops,
                source.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value()).getOrThrow());
        generator.add("biome_source", JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:forest\"}"));
        var profile = new DimensionProfile(Level.OVERWORLD.identifier(), 42, -64, 384, "noise", "minecraft:overworld", 1);
        var sampler = ClientWorldgenProfileDecoder.decodeJavaSampler(profile, generator, decoded, client);
        try (var loader = mockStatic(net.fabricmc.loader.api.FabricLoader.class)) {
            var fabric = mock(net.fabricmc.loader.api.FabricLoader.class);
            when(fabric.getGameDir()).thenReturn(gameDirectory);
            loader.when(net.fabricmc.loader.api.FabricLoader::getInstance).thenReturn(fabric);
            var vegetation = new PredictionVegetation(sampler);
            assertTrue(vegetation.available(), vegetation.diagnostics());
            assertFalse(vegetation.diagnostics().contains("structureSets=0,"), vegetation.diagnostics());
            System.out.println("VANILLA_DECORATION_ROUNDTRIP configured=" + root.getAsJsonObject("configured_features").size()
                    + " placed=" + root.getAsJsonObject("placed_features").size() + " biomes=" + root.getAsJsonObject("biomes").size()
                    + " structures=" + root.getAsJsonObject("structures").size() + " " + vegetation.diagnostics());
        }
    }

    @Test void clientRegistriesOutrankBuiltinsWhileStaticHoldersRemainAvailableWithoutClientEntries() {
        var root = new JsonObject();
        root.add("noises", new JsonObject()); root.add("density_functions", new JsonObject());
        var empty = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, List.of());
        var soundCodec = RegistryFixedCodec.create(Registries.SOUND_EVENT);
        assertTrue(soundCodec.parse(empty.ops(), new JsonPrimitive("minecraft:block.grass.break")).isSuccess());
        var custom = new MappedRegistry<SoundEvent>(Registries.SOUND_EVENT, Lifecycle.stable());
        var id = Identifier.parse("minecraft:block.grass.break");
        var override = custom.register(ResourceKey.create(Registries.SOUND_EVENT, id),
                SoundEvent.createVariableRangeEvent(id), RegistrationInfo.BUILT_IN);
        custom.freeze();
        var decoded = ClientWorldgenRegistries.decode(root, new RegistryAccess.ImmutableRegistryAccess(List.of(custom)), List.of());
        assertSame(override, soundCodec.parse(decoded.ops(), new JsonPrimitive(id.toString())).getOrThrow());
        assertSame(custom, decoded.access().lookupOrThrow(Registries.SOUND_EVENT));
    }

    private static <T> void assertCount(ClientWorldgenRegistries decoded, ResourceKey<? extends Registry<T>> key,
                                        JsonObject root, String field) {
        assertEquals(root.getAsJsonObject(field).size(), decoded.access().lookupOrThrow(key).size(), field);
    }

    private static <T> JsonObject encode(HolderLookup.RegistryLookup<T> registry, Codec<T> codec, DynamicOps<JsonElement> ops) {
        var result = new JsonObject();
        registry.listElements().forEach(holder -> {
            JsonElement value = codec.encodeStart(ops, holder.value()).getOrThrow();
            if (holder.value() instanceof ConfiguredFeature<?, ?> feature) value = VanillaFeatureSnapshot.correct(feature, value);
            if (holder.value() instanceof Structure) {
                var object = value.getAsJsonObject();
                if (object.get("biomes").isJsonPrimitive()) {
                    String name = object.get("biomes").getAsString();
                    var biomes = new JsonArray();
                    if (name.startsWith("#")) biomeTag(Identifier.parse(name.substring(1)), new HashSet<>()).forEach(biomes::add);
                    else biomes.add(name);
                    object.add("biomes", biomes);
                }
            }
            if (holder.value() instanceof StructureSet) {
                var placement = value.getAsJsonObject().getAsJsonObject("placement");
                var preferred = placement.get("preferred_biomes");
                if (preferred != null && preferred.isJsonPrimitive() && preferred.getAsString().startsWith("#")) {
                    var biomes = new JsonArray();
                    biomeTag(Identifier.parse(preferred.getAsString().substring(1)), new HashSet<>()).forEach(biomes::add);
                    placement.add("preferred_biomes", biomes);
                }
            }
            result.add(holder.key().identifier().toString(), value);
        });
        return result;
    }

    @Test void serverSnapshotInlinesConcentricRingBiomeTagBeforeClientFreeze() throws Exception {
        var source = TerrainPredictionFixtures.sourceLookup();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        var biome = biomes.register(net.minecraft.world.level.biome.Biomes.FOREST,
                source.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.FOREST).value(),
                RegistrationInfo.BUILT_IN);
        var tag = net.minecraft.tags.TagKey.create(Registries.BIOME, Identifier.parse("minecraft:stronghold_biased_to"));
        biomes.freeze();
        biomes.prepareTagReload(new net.minecraft.tags.TagLoader.LoadResult<>(Registries.BIOME,
                Map.of(tag, List.<Holder<Biome>>of(biome)))).apply();
        var structures = new MappedRegistry<Structure>(Registries.STRUCTURE, Lifecycle.stable());
        var original = source.lookupOrThrow(Registries.STRUCTURE).listElements().findFirst().orElseThrow();
        var structure = structures.register(original.key(), original.value(), RegistrationInfo.BUILT_IN);
        structures.freeze();
        var placement = new net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement(
                32, 3, 128, biomes.getOrThrow(tag));
        var value = new StructureSet(List.of(new StructureSet.StructureSelectionEntry(structure, 1)), placement);
        var ops = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes, structures)).createSerializationContext(JsonOps.INSTANCE);
        var encoder = Class.forName("dev.xantha.vss.networking.server.session.WorldgenCodecSnapshot")
                .getDeclaredMethod("encodeEntry", RegistryOps.class, Codec.class, Object.class);
        encoder.setAccessible(true);
        var encoded = (JsonElement) encoder.invoke(null, ops, StructureSet.DIRECT_CODEC, value);
        assertEquals(JsonParser.parseString("[\"minecraft:forest\"]"), encoded.getAsJsonObject()
                .getAsJsonObject("placement").get("preferred_biomes"));
    }

    // Vanilla's datagen holders deliberately leave biome tags unresolved.
    // Expand the real game resources as the server's loaded registry does.
    private static Set<String> biomeTag(Identifier id, Set<Identifier> active) {
        if (!active.add(id)) throw new IllegalStateException("Cyclic biome tag " + id);
        var result = new LinkedHashSet<String>();
        String path = "/data/" + id.getNamespace() + "/tags/worldgen/biome/" + id.getPath() + ".json";
        try (var stream = ClientWorldgenStaticRegistriesTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            for (var value : JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("values")) {
                String name = value.isJsonPrimitive() ? value.getAsString() : value.getAsJsonObject().get("id").getAsString();
                if (name.startsWith("#")) result.addAll(biomeTag(Identifier.parse(name.substring(1)), active));
                else result.add(name);
            }
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        active.remove(id);
        return result;
    }
}
