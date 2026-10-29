package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.MapCodec;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.data.worldgen.Pools;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutStructure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MostructuresSnapshotTest {
    @BeforeAll
    static void bootstrap() {
        WorldgenOptionalSnapshotTest.bootstrap();
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualMoStructuresCodecAccessErrorIsIsolatedAndHealthyStructuresStillDecode() throws Exception {
        String jar = System.getProperty("vss.mostructuresJar");
        assumeTrue(jar != null, "provide -PvssMostructuresJar with a Minecraft 26.3 compatible Mo Structures JAR");
        var vanilla = VanillaRegistries.createWorldLookup();
        var biomes = new MappedRegistry<net.minecraft.world.level.biome.Biome>(Registries.BIOME, Lifecycle.stable());
        var plains = biomes.register(Biomes.PLAINS, vanilla.lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        biomes.freeze();
        var settings = new Structure.StructureSettings(HolderSet.direct(plains), Map.of(),
                GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE);
        Structure healthy = new SwampHutStructure(settings);

        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> modClass = Class.forName("io.github.frqnny.mostructures.structure.ModStructure", true, loader);
            Structure broken = (Structure) modClass.getConstructors()[0].newInstance(settings,
                    vanilla.lookupOrThrow(Registries.TEMPLATE_POOL).getOrThrow(Pools.EMPTY),
                    Optional.empty(), 1, ConstantHeight.of(VerticalAnchor.absolute(64)), false,
                    Optional.empty(), 80, -1, List.of(),
                    JigsawStructure.DEFAULT_DIMENSION_PADDING, JigsawStructure.DEFAULT_LIQUID_SETTINGS);
            Codec<Structure> actualCodec = ((MapCodec<Structure>) modClass.getField("CODEC").get(null)).codec();
            IllegalAccessError error = assertThrows(IllegalAccessError.class,
                    () -> actualCodec.encodeStart(JsonOps.INSTANCE, broken));
            assertTrue(error.getMessage().contains("settings"), error.toString());

            var structures = new MappedRegistry<Structure>(Registries.STRUCTURE, Lifecycle.stable());
            structures.register(ResourceKey.create(Registries.STRUCTURE, Identifier.parse("vss:healthy")),
                    healthy, RegistrationInfo.BUILT_IN);
            structures.register(ResourceKey.create(Registries.STRUCTURE, Identifier.parse("mostructures:broken")),
                    broken, RegistrationInfo.BUILT_IN);
            structures.freeze();
            RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes, structures));

            // Dispatch directly to the actual mod codec without initializing
            // the mod entrypoint or changing Minecraft's frozen type registry.
            Codec<Structure> dispatched = new Codec<>() {
                @Override
                public <T> DataResult<T> encode(Structure value, DynamicOps<T> ops, T prefix) {
                    return modClass.isInstance(value) ? actualCodec.encode(value, ops, prefix)
                            : Structure.DIRECT_CODEC.encode(value, ops, prefix);
                }

                @Override
                public <T> DataResult<Pair<Structure, T>> decode(DynamicOps<T> ops, T input) {
                    return Structure.DIRECT_CODEC.decode(ops, input);
                }
            };
            JsonObject encoded = WorldgenCodecSnapshot.encodeOptional(
                    access, RegistryOps.create(JsonOps.INSTANCE, access), Registries.STRUCTURE, dispatched);
            assertEquals(java.util.Set.of("vss:healthy"), encoded.keySet());
            assertEquals("minecraft:plains", encoded.getAsJsonObject("vss:healthy")
                    .getAsJsonArray("biomes").get(0).getAsString());

            JsonObject root = JsonParser.parseString("""
                    {"noises":{},"density_functions":{},
                     "structure_sets":{
                       "vss:healthy_set":{"structures":[{"structure":"vss:healthy","weight":1}],
                         "placement":{"type":"minecraft:random_spread","spacing":32,"separation":8,"salt":1}},
                       "mostructures:broken_set":{"structures":[{"structure":"mostructures:broken","weight":1}],
                         "placement":{"type":"minecraft:random_spread","spacing":32,"separation":8,"salt":2}}
                     }}
                    """).getAsJsonObject();
            root.add("structures", encoded);
            root.add("structure_sets", WorldgenCodecSnapshot.filterStructureSets(
                    encoded, root.getAsJsonObject("structure_sets")));
            Class<?> client = Class.forName("dev.xantha.vss.client.prediction.ClientWorldgenRegistries");
            var decode = client.getDeclaredMethod("decode", JsonObject.class, RegistryAccess.class);
            decode.setAccessible(true);
            Object decoded = decode.invoke(null, root, new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)));
            var getAccess = client.getDeclaredMethod("access");
            getAccess.setAccessible(true);
            RegistryAccess recovered = (RegistryAccess) getAccess.invoke(decoded);
            assertEquals(1, recovered.lookupOrThrow(Registries.STRUCTURE).size());
            assertEquals(1, recovered.lookupOrThrow(Registries.STRUCTURE_SET).size());
            assertNotNull(recovered.lookupOrThrow(Registries.STRUCTURE).get(Identifier.parse("vss:healthy")));
            assertEquals(2, structures.size(), "the real server registry is not pruned");
        }
    }
}
