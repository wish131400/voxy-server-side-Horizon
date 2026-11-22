package dev.xantha.vss.client.prediction;

import com.google.gson.JsonElement;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.function.Supplier;

/** Provides the server's biome data to WorldWeaver while its prediction source builds its maps. */
public final class WorldWeaverBiomeContext {
    private static final ThreadLocal<RegistryAccess> ACTIVE = new ThreadLocal<>();

    private WorldWeaverBiomeContext() {}

    public static RegistryAccess activeRegistries() {
        return ACTIVE.get();
    }

    static BiomeSource decode(JsonElement source, ClientWorldgenRegistries registries) {
        return withSource(source, registries, () -> {
            BiomeSource decoded = BiomeSource.CODEC.parse(registries.ops(), source).getOrThrow();
            prepare(decoded);
            return decoded;
        });
    }

    static ChunkGenerator decodeGenerator(JsonElement generator, JsonElement source, ClientWorldgenRegistries registries) {
        return withSource(source, registries, () -> {
            ChunkGenerator decoded = ChunkGenerator.CODEC.parse(registries.ops(), generator).getOrThrow();
            prepare(decoded.getBiomeSource());
            return decoded;
        });
    }

    private static void prepare(BiomeSource source) {
        if (ACTIVE.get() != null && source.possibleBiomes().isEmpty()) {
            throw new IllegalStateException("WorldWeaver did not reconstruct any biomes from the server snapshot");
        }
    }

    private static <T> T withSource(JsonElement source, ClientWorldgenRegistries registries, Supplier<T> decode) {
        if (source == null || !source.isJsonObject() || !source.getAsJsonObject().has("type")
                || !source.getAsJsonObject().get("type").getAsString().startsWith("wover:")) {
            return decode.get();
        }
        RegistryAccess previous = ACTIVE.get();
        ACTIVE.set(registries.access());
        try {
            return decode.get();
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }
}
