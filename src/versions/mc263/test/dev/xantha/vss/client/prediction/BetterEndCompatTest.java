package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class BetterEndCompatTest {
    @BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test
    void samplesInTheGridReportedByTheTerrainApi() throws Exception {
        var settings = ModernDensityFixtures.settings(NoiseGeneratorSettings.END);
        for (int[] grid : new int[][]{{8, 4}, {16, 8}}) {
            TerrainApi.grid = grid;
            var surface = BetterEndCompat.BetterEndSurface.open(TerrainApi.class, settings);
            assertSame(settings, TerrainApi.settings);
            assertEquals(settings.noiseSettings().minY() + grid[1] * 3,
                    surface.surfaceY(-1, 1027));
            assertArrayEquals(new int[]{-grid[0], Math.floorDiv(1027, grid[0]) * grid[0],
                    grid[0], grid[1], settings.noiseSettings().height()}, TerrainApi.fillArguments);
            assertEquals(settings.noiseSettings().height() / grid[1] + 1, TerrainApi.bufferLength);
        }
    }

    @Test
    void invalidGridFailsAtTheAdapterBoundary() {
        TerrainApi.grid = new int[]{8, 0};
        assertThrows(IllegalStateException.class, () -> BetterEndCompat.BetterEndSurface.open(
                TerrainApi.class, ModernDensityFixtures.settings(NoiseGeneratorSettings.END)));
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.betterendJar", matches = ".+")
    void released263ApiResolvesTheEndInterpolationGrid() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{
                Path.of(System.getProperty("vss.betterendJar")).toUri().toURL()}, getClass().getClassLoader())) {
            var type = loader.loadClass("org.betterx.betterend.world.generator.TerrainGenerator");
            int[] grid = (int[]) type.getMethod("islandCellSize", NoiseGeneratorSettings.class)
                    .invoke(null, ModernDensityFixtures.settings(NoiseGeneratorSettings.END));
            assertArrayEquals(new int[]{8, 4}, grid);
            var custom = ModernDensityFixtures.densitySettings(
                    net.minecraft.world.level.levelgen.densityfunction.DensityFunctions.interpolated(
                            net.minecraft.world.level.levelgen.densityfunction.DensityFunctions.constant(1f), 16, 8));
            assertArrayEquals(new int[]{16, 8}, (int[]) type.getMethod("islandCellSize", NoiseGeneratorSettings.class)
                    .invoke(null, custom));
        }
    }

    public static final class TerrainApi {
        static int[] grid;
        static NoiseGeneratorSettings settings;
        static int[] fillArguments;
        static int bufferLength;
        public static int[] islandCellSize(NoiseGeneratorSettings value) { settings = value; return grid; }
        public static void initNoise(long seed, BiomeSource source, Climate.Sampler sampler) { }
        public static void fillTerrainDensity(double[] buffer, int x, int z, int xz, int y, int height) {
            fillArguments = new int[]{x, z, xz, y, height};
            bufferLength = buffer.length;
            Arrays.fill(buffer, -1.0);
            buffer[3] = 1.0;
        }
    }
}
