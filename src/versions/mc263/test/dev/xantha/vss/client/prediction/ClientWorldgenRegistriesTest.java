package dev.xantha.vss.client.prediction;

import net.minecraft.world.level.levelgen.feature.*;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.feature.Feature;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClientWorldgenRegistriesTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test
    void configuredSelectorCanReferForwardToAPlacedFeature() {
        var root = JsonParser.parseString("""
                {
                  "noises": {}, "density_functions": {},
                  "configured_features": {
                    "vss:selector": {
                      "type": "minecraft:random_selector",
                      "features": [], "default": "vss:placed"
                    },
                    "vss:ground": {
                      "type": "minecraft:simple_block",
                      "to_place": {"id": "minecraft:grass_block"}
                    }
                  },
                  "placed_features": {
                    "vss:placed": {"feature": "vss:ground", "placement": []}
                  }
                }
                """).getAsJsonObject();
        var registries = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, java.util.List.of());
        var configured = registries.access().lookupOrThrow(Registries.FEATURE);
        var selector = configured.getOptional(Identifier.parse("vss:selector"));
        assertTrue(selector.isPresent(), "a forward placed-feature reference must not disable all configured models");
        var config = assertInstanceOf(RandomSelectorFeature.class, selector.get());
        assertTrue(config.defaultFeature().isBound());
        assertInstanceOf(SimpleBlockFeature.class, config.defaultFeature().value().feature().value());
        assertTrue(configured.getOptional(Identifier.parse("vss:ground")).isPresent());
    }
    @Test
    void registeredProvidersKeepForwardReferencesAndActualPlacementWithoutClientFallback() {
        var root = JsonParser.parseString("""
                {
                  "noises": {}, "density_functions": {},
                  "block_state_providers": {
                    "vss:a_forward": {"type":"minecraft:rule_based","rules":[
                      {"if_true":{"type":"minecraft:true"},"then":"vss:z_target"}]},
                    "vss:z_target": {"id":"minecraft:oak_planks"}
                  },
                  "configured_features": {
                    "vss:marker": {"type":"minecraft:simple_block","to_place":"vss:a_forward"}
                  },
                  "placed_features": {"vss:marker":{"feature":"vss:marker","placement":[]}}
                }
                """).getAsJsonObject();
        var registries = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, java.util.List.of());
        assertTrue(RegistryAccess.EMPTY.lookup(Registries.BLOCK_STATE_PROVIDER).isEmpty());
        var providers = registries.access().lookupOrThrow(Registries.BLOCK_STATE_PROVIDER);
        var forward = assertInstanceOf(net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedStateProvider.class,
                providers.getOptional(Identifier.parse("vss:a_forward")).orElseThrow());
        var target = providers.get(Identifier.parse("vss:z_target")).orElseThrow();
        assertSame(target, forward.rules().getFirst().then(), "the forward holder must bind to the decoded server registry");
        var placed = registries.access().lookupOrThrow(Registries.PLACED_FEATURE)
                .getOptional(Identifier.parse("vss:marker")).orElseThrow();
        var block = assertInstanceOf(SimpleBlockFeature.class, placed.feature().value());
        assertSame(providers.get(Identifier.parse("vss:a_forward")).orElseThrow(), block.toPlace());
        assertActualMarkerPlacement(registries, placed, net.minecraft.world.level.block.Blocks.OAK_PLANKS);
    }

    @Test
    void realServerSnapshotIncludesRegisteredProvidersAndRoundTripsTheirFeatureHolders() throws Exception {
        var providers = new net.minecraft.core.MappedRegistry<net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider>(
                Registries.BLOCK_STATE_PROVIDER, com.mojang.serialization.Lifecycle.stable());
        var targetKey = net.minecraft.resources.ResourceKey.create(Registries.BLOCK_STATE_PROVIDER, Identifier.parse("vss:z_target"));
        var target = providers.register(targetKey,
                net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider.of(net.minecraft.world.level.block.Blocks.BIRCH_PLANKS),
                net.minecraft.core.RegistrationInfo.BUILT_IN);
        var forwardKey = net.minecraft.resources.ResourceKey.create(Registries.BLOCK_STATE_PROVIDER, Identifier.parse("vss:a_forward"));
        var forward = providers.register(forwardKey,
                new net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedStateProvider(null, java.util.List.of(
                        new net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedStateProvider.Rule(
                                net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate.alwaysTrue(), target))),
                net.minecraft.core.RegistrationInfo.BUILT_IN);
        var features = new net.minecraft.core.MappedRegistry<Feature>(Registries.FEATURE, com.mojang.serialization.Lifecycle.stable());
        var marker = features.register(net.minecraft.resources.ResourceKey.create(Registries.FEATURE, Identifier.parse("vss:marker")),
                new SimpleBlockFeature(forward, false), net.minecraft.core.RegistrationInfo.BUILT_IN);
        var placed = new net.minecraft.core.MappedRegistry<net.minecraft.world.level.levelgen.placement.PlacedFeature>(
                Registries.PLACED_FEATURE, com.mojang.serialization.Lifecycle.stable());
        placed.register(net.minecraft.resources.ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse("vss:marker")),
                new net.minecraft.world.level.levelgen.placement.PlacedFeature(marker, java.util.List.of()), net.minecraft.core.RegistrationInfo.BUILT_IN);
        var values = new java.util.ArrayList<net.minecraft.core.Registry<?>>();
        values.add(new net.minecraft.core.MappedRegistry<>(Registries.NOISE, com.mojang.serialization.Lifecycle.stable()).freeze());
        values.add(new net.minecraft.core.MappedRegistry<>(Registries.DENSITY_FUNCTION, com.mojang.serialization.Lifecycle.stable()).freeze());
        values.add(new net.minecraft.core.MappedRegistry<>(Registries.MATERIAL_RULE, com.mojang.serialization.Lifecycle.stable()).freeze());
        values.add(new net.minecraft.core.MappedRegistry<>(Registries.MATERIAL_CONDITION, com.mojang.serialization.Lifecycle.stable()).freeze());
        values.add(providers.freeze()); values.add(features.freeze()); values.add(placed.freeze());
        // The snapshot's provider/feature entries have a new owner. Avoid unrelated
        // vanilla biome feature holders, which belong to their original registry.
        values.add(new net.minecraft.core.MappedRegistry<>(Registries.BIOME, com.mojang.serialization.Lifecycle.stable()).freeze());
        var access = new RegistryAccess.ImmutableRegistryAccess(values);
        var snapshot = Class.forName("dev.xantha.vss.networking.server.session.WorldgenCodecSnapshot");
        var method = snapshot.getDeclaredMethod("encodeRegistries", RegistryAccess.class,
                net.minecraft.server.packs.resources.ResourceManager.class,
                dev.xantha.vss.common.worldgen.DensityFunctionReferences.class,
                dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies.class);
        method.setAccessible(true);
        var encoded = method.invoke(null, access, null, new dev.xantha.vss.common.worldgen.DensityFunctionReferences(),
                new dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies(access, java.util.List.of()));
        byte[] bytes = (byte[]) property(encoded, "bytes");
        int compression = (Integer) property(encoded, "compression"), size = (Integer) property(encoded, "rawSize");
        var json = JsonParser.parseString(new String(dev.xantha.vss.common.processing.LodByteCompression.decompress(
                bytes, compression, size, 16_000_000), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(java.util.Set.of("vss:a_forward", "vss:z_target"), json.getAsJsonObject("block_state_providers").keySet());
        assertEquals("vss:z_target", json.getAsJsonObject("block_state_providers").getAsJsonObject("vss:a_forward")
                .getAsJsonArray("rules").get(0).getAsJsonObject().get("then").getAsString());
        assertEquals("vss:a_forward", json.getAsJsonObject("configured_features").getAsJsonObject("vss:marker").get("to_place").getAsString());
        var decoded = ClientWorldgenRegistries.decode(json, RegistryAccess.EMPTY, java.util.List.of());
        var decodedPlaced = decoded.access().lookupOrThrow(Registries.PLACED_FEATURE).getOptional(Identifier.parse("vss:marker")).orElseThrow();
        assertActualMarkerPlacement(decoded, decodedPlaced, net.minecraft.world.level.block.Blocks.BIRCH_PLANKS);
    }

    private static Object property(Object record, String name) throws Exception {
        var method = record.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(record);
    }

    private static void assertActualMarkerPlacement(ClientWorldgenRegistries registries,
            net.minecraft.world.level.levelgen.placement.PlacedFeature feature, net.minecraft.world.level.block.Block expected) {
        var terrain = new ClientTerrainSampler(42, PredictionDecorationQueryTest.PROFILE) {
            @Override public ClientColumnSample sampleSurface(int x, int z) { return PredictionVoxelFixtures.sample(64); }
            @Override public ClientColumnSample sample(int x, int z) { return sampleSurface(x, z); }
        };
        var generator = dev.xantha.vss.testutil.TerrainPredictionFixtures.noise();
        var level = new PredictionDecorationLevel(terrain, terrain, registries.access(), 0, 0);
        var origin = new net.minecraft.core.BlockPos(1, 64, 1);
        assertTrue(new net.minecraft.world.level.levelgen.placement.FeaturePlacer(level, generator)
                .place(feature, net.minecraft.util.RandomSource.create(7), origin));
        assertTrue(level.getBlockState(origin).is(expected), "registered provider must control actual feature writes");
        assertEquals(1, level.placed().size());
    }

}
