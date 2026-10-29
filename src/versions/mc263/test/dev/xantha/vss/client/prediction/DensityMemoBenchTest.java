package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LevelHeightAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Repeated-query benchmark for 26.3's vanilla float compiler and worker caches.
 * The old vss.densityMemo switch has no role on this version. Independent
 * FloatDensityCompilerTest compares uncached vanilla and retained worker values. */
class DensityMemoBenchTest {
    private static net.minecraft.core.HolderLookup.Provider lookup;
    private static RegistryAccess access;

    @BeforeAll
    static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        lookup = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
        var key = net.minecraft.core.registries.Registries.BIOME;
        var biomes = new net.minecraft.core.MappedRegistry<net.minecraft.world.level.biome.Biome>(
                key, com.mojang.serialization.Lifecycle.stable());
        lookup.lookupOrThrow(key).listElements().forEach(
                h -> biomes.register(h.key(), h.value(), net.minecraft.core.RegistrationInfo.BUILT_IN));
        access = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes.freeze()));
    }

    static ClientTerrainSampler sampler(JsonObject doc, long seed) {
        doc = PredictionTestWorldgen.current(doc);
        var ops = lookup.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        var settings = net.minecraft.world.level.levelgen.NoiseGeneratorSettings.DIRECT_CODEC
                .parse(ops, doc.get("settings")).getOrThrow();
        var biome = net.minecraft.world.level.biome.BiomeSource.CODEC
                .parse(ops, doc.get("biome_source")).getOrThrow();
        var noise = doc.getAsJsonObject("settings").getAsJsonObject("noise");
        var profile = new DimensionProfile(Identifier.withDefaultNamespace("overworld"), seed,
                noise.get("min_y").getAsInt(), noise.get("height").getAsInt(),
                "noise", "minecraft:overworld", 0L);
        var generator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(
                biome, net.minecraft.core.Holder.direct(settings));
        var random = net.minecraft.world.level.levelgen.RandomState.create(lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE), seed, settings);
        return new ClientTerrainSampler(seed, profile, generator, random,
                LevelHeightAccessor.create(profile.minY(), profile.height()),
                settings.seaLevel(), null, access);
    }

    /** Dense 8x8 grid, the shape the client uses for nearby tiles. */
    private static List<int[]> denseGrid(int baseX, int baseZ) {
        var points = new ArrayList<int[]>();
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 8; x++) points.add(new int[]{baseX + x, baseZ + z});
        }
        return points;
    }

    /** Sparse 8x8 grid, 32 blocks apart, the shape used for far coverage. */
    private static List<int[]> sparseGrid(int baseX, int baseZ) {
        var points = new ArrayList<int[]>();
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 8; x++) points.add(new int[]{baseX + x * 32, baseZ + z * 32});
        }
        return points;
    }

    private static int[] heights(ClientTerrainSampler sampler, List<int[]> points) {
        int[] result = new int[points.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = sampler.surfaceY(points.get(i)[0], points.get(i)[1]);
        }
        return result;
    }

    /** Best-of-N wall time for one pass over the points. */
    private static long bestNanos(ClientTerrainSampler sampler, List<int[]> points, int rounds) {
        long best = Long.MAX_VALUE;
        for (int round = 0; round < rounds; round++) {
            long start = System.nanoTime();
            int[] sampled = heights(sampler, points);
            long elapsed = System.nanoTime() - start;
            if (sampled.length == -1) throw new AssertionError("unreachable");
            best = Math.min(best, elapsed);
        }
        return best;
    }

    private static void compare(String label, JsonObject doc, List<int[]> points) {
        // Separate sampler instances must retain the same exact heights.
        ClientTerrainSampler plain = sampler(doc, 42L);
        int[] expected = heights(plain, points);
        long plainNanos = bestNanos(plain, points, 5);


        ClientTerrainSampler memoed = sampler(doc, 42L);
        int[] actual = heights(memoed, points);
        long memoNanos = bestNanos(memoed, points, 5);

        // The memo is a pure-function cache: heights must be bit-identical.
        assertArrayEquals(expected, actual, label + " memo changed sampled heights");

        System.out.printf(java.util.Locale.ROOT,
                "VANILLA_FLOAT_COMPILER_REPEAT %s points=%d firstMs=%.3f secondMs=%.3f ratio=%.3f%n",
                label, points.size(), plainNanos / 1e6, memoNanos / 1e6,
                plainNanos / (double) memoNanos);
    }

    @Test
    void compilerPreservesHeightsAcrossFreshSamplerInstances() throws Exception {
        var doc = LithostitchedNativeTest.document();
        // This benchmark isolates density evaluation, not repeated height hits.
        System.setProperty("vss.javaHeightCache", "off");
        try {
            compare("dense8x8", doc, denseGrid(-40, 56));
            compare("sparse8x8", doc, sparseGrid(-1024, -1024));
        } finally {

            System.clearProperty("vss.javaHeightCache");
        }
    }
}
