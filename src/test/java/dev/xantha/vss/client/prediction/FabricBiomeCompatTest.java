package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Execute the actual installed biome API's seed/noise implementation, without launching Minecraft. */
class FabricBiomeCompatTest {
    @Test void vanillaSamplerNeedsNoOptionalApi() {
        assertDoesNotThrow(() -> FabricBiomeCompat.copyClimateContext(new Object(), new Object()));
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.fabricBiomeJar", matches = ".+")
    void copiedSeedRestoresExactEndBiomeNoiseAcrossWorldSeeds() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{
                Path.of(System.getProperty("vss.fabricBiomeJar")).toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type;
            try { type = loader.loadClass("net.fabricmc.fabric.mixin.biome.ClimateSamplerMixin"); }
            catch (ClassNotFoundException legacy) { type = loader.loadClass("net.fabricmc.fabric.mixin.biome.MultiNoiseUtilMultiNoiseSamplerMixin"); }
            var seed = type.getMethod("fabric_setSeed", long.class);
            var noise = type.getMethod("fabric_getEndBiomesSampler");
            for (long value : new long[]{0, 42, -1, Long.MAX_VALUE}) {
                var source = type.getConstructor().newInstance();
                var target = type.getConstructor().newInstance();
                seed.invoke(source, value);
                var unseeded = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> noise.invoke(target));
                assertTrue(unseeded.getCause().getMessage().contains("doesn't have a seed set"));
                FabricBiomeCompat.copyClimateContext(source, target);
                assertEquals(value, type.getMethod("fabric_getSeed").invoke(target));
                Object expected = noise.invoke(source);
                java.lang.reflect.Method sample = java.util.Arrays.stream(expected.getClass().getMethods())
                        .filter(method -> java.util.List.of("noise", "getValue", "get").contains(method.getName())
                                && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{double.class, double.class, double.class}))
                        .findFirst().orElseThrow();
                Object actual = noise.invoke(target);
                assertSame(actual, noise.invoke(target), "The optional API retains its own lazy noise cache");
                for (int i = -64; i < 64; i++)
                    assertEquals(sample.invoke(expected, i * 3.75, i * .13, -i * 11.25),
                            sample.invoke(actual, i * 3.75, i * .13, -i * 11.25));
            }
        }
    }
}
