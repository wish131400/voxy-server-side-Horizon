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
            lookup.listRegistries().forEach(registry -> values.add(copy(registry)));
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
        // VanillaRegistries is a datagen lookup: listTags deliberately throws.
        // Dimension timelines are named sets in 1.21.11. Load their real vanilla
        // tag resources rather than inventing empty tags for codec round trips.
        if (source.key().equals(Registries.TIMELINE)) {
            sourceLookup.lookupOrThrow(Registries.DIMENSION_TYPE).listElements().forEach(holder ->
                    holder.value().timelines().unwrapKey().ifPresent(key -> bindTimelineTag(
                            (MappedRegistry) result, key.location(), new java.util.HashSet<>())));
        }
        return result.freeze();
    }
    private static List<net.minecraft.core.Holder<net.minecraft.world.timeline.Timeline>> bindTimelineTag(
            MappedRegistry<net.minecraft.world.timeline.Timeline> registry,
            net.minecraft.resources.Identifier id, java.util.Set<net.minecraft.resources.Identifier> active) {
        if (!active.add(id)) throw new IllegalStateException("Cyclic vanilla timeline tag " + id);
        var values = new ArrayList<net.minecraft.core.Holder<net.minecraft.world.timeline.Timeline>>();
        String resource = "/data/" + id.getNamespace() + "/tags/timeline/" + id.getPath() + ".json";
        try (var input = TerrainPredictionFixtures.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing vanilla timeline tag " + resource);
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(
                    input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (var element : json.getAsJsonArray("values")) {
                String name = element.isJsonPrimitive() ? element.getAsString() : element.getAsJsonObject().get("id").getAsString();
                if (name.startsWith("#")) values.addAll(bindTimelineTag(registry,
                        net.minecraft.resources.Identifier.parse(name.substring(1)), active));
                else values.add(registry.getOrThrow(net.minecraft.resources.ResourceKey.create(
                        Registries.TIMELINE, net.minecraft.resources.Identifier.parse(name))));
            }
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        active.remove(id);
        var unique = values.stream().distinct().toList();
        registry.bindTag(net.minecraft.tags.TagKey.create(Registries.TIMELINE, id), unique);
        return unique;
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
