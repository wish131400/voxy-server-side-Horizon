package dev.xantha.vss.testutil;

import com.google.gson.JsonObject;
import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

public final class TerrainPredictionFixtures {
    private static RegistryAccess access;
    private static HolderLookup.Provider sourceLookup;
    private TerrainPredictionFixtures() { }
    public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    public static synchronized RegistryAccess access() {
        if (access == null) {
            var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
            sourceLookup = lookup;
            var values = new ArrayList<Registry<?>>();
            lookup.listRegistries().forEach(registry -> values.add(copy(lookup.lookupOrThrow(registry))));
            access = new RegistryAccess.ImmutableRegistryAccess(values);
        }
        return access;
    }
    /** Original owner of embedded holder sets used while encoding vanilla values. */
    public static synchronized HolderLookup.Provider sourceLookup() { access(); return sourceLookup; }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Registry<T> copy(HolderLookup.RegistryLookup<T> source) {
        var result = new MappedRegistry<T>((net.minecraft.resources.ResourceKey) source.key(), Lifecycle.stable());
        source.listElements().forEach(holder -> result.register(holder.key(), holder.value(), net.minecraft.core.RegistrationInfo.BUILT_IN));
        return result.freeze();
    }
    public static NoiseBasedChunkGenerator noise() {
        var registries = access();
        var original = sourceLookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        // Values from the datagen lookup contain holders owned by that lookup.
        // Rebind the settings codec to the copied runtime registry just as a
        // real client snapshot decoder does; never relax holder ownership.
        var encoded = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(
                sourceLookup.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), original).getOrThrow();
        var rebound = NoiseGeneratorSettings.DIRECT_CODEC.parse(
                registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), encoded).getOrThrow();
        return new NoiseBasedChunkGenerator(new FixedBiomeSource(registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)),
                net.minecraft.core.Holder.direct(rebound));
    }
    public static JsonObject emptyRegistries() {
        var result = new JsonObject();
        for (String name : List.of("noises", "density_functions", "biomes", "configured_features", "placed_features"))
            result.add(name, new JsonObject());
        return result;
    }
}
