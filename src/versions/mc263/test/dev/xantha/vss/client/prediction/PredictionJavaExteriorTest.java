package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionJavaExteriorTest {
    @BeforeAll static void bootstrap() { ModernDensityFixtures.bootstrap(); }

    private record Context(NoiseBasedChunkGenerator generator, RandomState random, LevelHeightAccessor heights) {
        PredictionJavaExterior fast() { return new PredictionJavaExterior(generator, random, heights); }
        boolean[] original(int x, int z, int step, int bottom, int top) {
            boolean[] result = new boolean[top - bottom];
            for (int dz = 0; dz < step; dz++) for (int dx = 0; dx < step; dx++) {
                var column = generator.getBaseColumn(x + dx, z + dz, heights, random);
                if (dx == 0 && dz == 0 && column.getBlock(top - 1).isAir()) return null;
                for (int y = bottom; y < top; y++) result[y - bottom] |= !column.getBlock(y).isAir();
            }
            return result;
        }
    }

    private static Context context(NoiseGeneratorSettings settings, long seed) {
        var biome = ModernDensityFixtures.lookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        return new Context(new NoiseBasedChunkGenerator(new FixedBiomeSource(biome), Holder.direct(settings)),
                ModernDensityFixtures.random(settings, seed),
                LevelHeightAccessor.create(settings.noiseSettings().minY(), settings.noiseSettings().height()));
    }

    @Test void partialFootprintsMatchVanillaColumnsAcrossDimensionsAndCellBorders() {
        int checked = 0;
        for (var key : List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END)) {
            var c = context(ModernDensityFixtures.settings(key), 5052304137288917019L);
            var fast = c.fast();
            assertFalse(fast.ordered());
            assertTrue(fast.reusesContexts());
            for (int[] p : List.of(new int[]{1605, -1456}, new int[]{-1, -5}, new int[]{3, 7}, new int[]{-4588, -1531})) {
                var column = c.generator.getBaseColumn(p[0], p[1], c.heights, c.random);
                int top = c.heights.getMaxY() + 1;
                while (top > c.heights.getMinY() && column.getBlock(top - 1).isAir()) top--;
                if (top == c.heights.getMinY()) continue;
                for (int depth : new int[]{37, 123}) for (int step : new int[]{1, 2, 4}) {
                    int bottom = Math.max(c.heights.getMinY(), top - depth);
                    var expected = c.original(p[0], p[1], step, bottom, top);
                    assertNotNull(expected);
                    assertArrayEquals(expected, fast.sample(p[0], p[1], step, bottom, top, () -> true),
                            key + " x=" + p[0] + " z=" + p[1] + " step=" + step + " bottom=" + bottom);
                    checked++;
                }
            }
        }
        assertTrue(checked >= 24, "oracle must exercise occupied columns rather than only empty space");
    }

    @Test void nestedCachesAndThinLayersPreserveAirIntervals() {
        var y = DensityFunctions.yClampedGradient(-64, 192, -64, 192);
        var layers = DensityFunctions.rangeChoice(y, 160, 161, DensityFunctions.constant(1),
                DensityFunctions.rangeChoice(y, 71, 98, DensityFunctions.constant(1), DensityFunctions.constant(-1)));
        for (var density : List.of(layers, DensityFunctions.cache(layers),
                DensityFunctions.cache(DensityFunctions.interpolated(DensityFunctions.cache(layers), 4, 8)))) {
            var c = context(ModernDensityFixtures.densitySettings(density), 42);
            var fast = c.fast();
            for (int step : new int[]{1, 2, 4}) for (int x : new int[]{-5, 3}) {
                var expected = c.original(x, -1, step, 47, 161);
                for (int repeat = 0; repeat < 2; repeat++) assertArrayEquals(expected,
                        fast.sample(x, -1, step, 47, 161, () -> true));
            }
        }
    }

    @Test void invalidRoofsAndCancelledRequestsCannotPoisonTheNextVolume() {
        var y = DensityFunctions.yClampedGradient(-64, 192, -64, 192);
        var layers = DensityFunctions.rangeChoice(y, 71, 98, DensityFunctions.constant(1), DensityFunctions.constant(-1));
        var c = context(ModernDensityFixtures.densitySettings(DensityFunctions.cache(layers)), 42);
        var fast = c.fast();
        assertNull(fast.sample(3, -1, 4, 47, 110, () -> true));
        assertNull(fast.sample(3, -1, 4, 98, 98, () -> true));
        assertThrows(IllegalArgumentException.class, () -> fast.sample(3, -1, 3, 47, 98, () -> true));
        assertThrows(CancellationException.class, () -> fast.sample(3, -1, 4, 47, 98, () -> false));
        var checks = new AtomicInteger();
        assertThrows(CancellationException.class,
                () -> fast.sample(3, -1, 4, 47, 98, () -> checks.incrementAndGet() < 8));
        assertArrayEquals(c.original(3, -1, 4, 47, 98), fast.sample(3, -1, 4, 47, 98, () -> true));
        assertTrue(fast.contextBuilds() >= 3);
    }
}
