package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.List;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Real target codecs and the production JNI document boundary, with no old block palette. */
final class ModernNativeDocuments {
    static JsonObject overworld() throws Exception {
        var lookup = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var source = new net.minecraft.world.level.biome.FixedBiomeSource(lookup.lookupOrThrow(Registries.BIOME)
                .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
        var generator = new NoiseBasedChunkGenerator(source, settings);
        var context = new ClientTerrainSampler(42,
                new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                        net.minecraft.resources.Identifier.withDefaultNamespace("overworld"),
                        -64, 384, "noise", "minecraft:overworld", 1)) {
            @Override NoiseBasedChunkGenerator generatorContext() { return generator; }
        };
        return document(lookup, settings.value(), JsonParser.parseString(
                "{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:plains\"}"), context);
    }

    static JsonObject registries(HolderLookup.Provider lookup) {
        var result = new JsonObject();
        result.add("density_functions", registry(lookup, Registries.DENSITY_FUNCTION, DensityFunctions.DIRECT_CODEC));
        result.add("noises", registry(lookup, Registries.NOISE, NormalNoise.DIRECT_CODEC));
        result.add("biomes", registry(lookup, Registries.BIOME, Biome.DIRECT_CODEC));
        result.add("configured_features", registry(lookup, Registries.FEATURE, Feature.DIRECT_CODEC));
        result.add("placed_features", registry(lookup, Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC));
        result.add("material_rules", registry(lookup, Registries.MATERIAL_RULE, MaterialRule.DIRECT_CODEC));
        result.add("material_conditions", registry(lookup, Registries.MATERIAL_CONDITION, MaterialCondition.DIRECT_CODEC));
        result.add("block_state_providers", registry(lookup, Registries.BLOCK_STATE_PROVIDER, BlockStateProvider.DIRECT_CODEC));
        return result;
    }

    static JsonObject document(HolderLookup.Provider lookup, NoiseGeneratorSettings settings,
            JsonElement biomeSource, ClientTerrainSampler context) throws Exception {
        var generator = new JsonObject();
        generator.add("settings", NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(
                lookup.createSerializationContext(JsonOps.INSTANCE), settings).getOrThrow());
        generator.add("biome_source", biomeSource);
        var result = RustWorldgenDocument.snapshot(generator, registries(lookup), context);
        var root = Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen");
        for (String map : List.of("grass", "foliage")) result.add(map + "_colormap",
                JsonParser.parseString(Files.readString(root.resolve(map + ".json"))));
        return result;
    }

    private static <T> JsonObject registry(HolderLookup.Provider lookup, ResourceKey<Registry<T>> key, Codec<T> codec) {
        var values = new JsonObject();
        var ops = lookup.createSerializationContext(JsonOps.INSTANCE);
        lookup.lookupOrThrow(key).listElements().forEach(holder -> {
            var encoded = codec.encodeStart(ops, holder.value()).getOrThrow();
            if (holder.value() instanceof Feature feature)
                encoded = dev.xantha.vss.common.worldgen.VanillaFeatureSnapshot.correct(feature, encoded);
            values.add(holder.key().identifier().toString(), encoded);
        });
        return values;
    }
}
