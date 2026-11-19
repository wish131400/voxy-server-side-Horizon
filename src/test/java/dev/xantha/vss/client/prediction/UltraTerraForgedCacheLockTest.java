package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.testutil.FabricRegistryTestLoader;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import dev.xantha.vss.testutil.UltraTerraForgedBatchLockProbe;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.nio.file.Path;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Both real production redirects, with the release's real cache and full filter continuation. */
@EnabledIfSystemProperty(named = "vss.ultraTerraForgedJar", matches = ".+")
class UltraTerraForgedCacheLockTest {
    private static final String PREFIX = "com.pandaismyname1.ultraterraforged.";

    @Test void provideAndQueueRunFullOwnedBatchesAfterPublishingAndUnlocking(@TempDir Path directory) throws Exception {
        TerrainPredictionFixtures.bootstrap();
        Object fabric = FabricLoader.getInstance();
        var game = fabric.getClass().getDeclaredField("gameDir");
        var config = fabric.getClass().getDeclaredField("configDir");
        game.setAccessible(true); config.setAccessible(true);
        Object previousGame = game.get(fabric), previousConfig = config.get(fabric);
        game.set(fabric, directory.toAbsolutePath());
        config.set(fabric, java.nio.file.Files.createDirectories(directory.resolve("config")).toAbsolutePath());
        String release = System.getProperty("vss.ultraTerraForgedJar");
        String generatorName = PREFIX + "world.worldgen.tile.TileGenerator";
        String cacheName = PREFIX + "world.worldgen.tile.TileCache";
        var harness = new VoxyMixinHarness(release, """
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin.client",
                 "compatibilityLevel":"JAVA_17","client":["UltraTerraForgedPredictionTasksMixin","UltraTerraForgedPredictionTileCacheMixin"],
                 "injectors":{"defaultRequire":1}}
                """);
        byte[] generator = harness.apply(generatorName), cache = harness.apply(cacheName);
        assertRedirect(generator, "generate", "vss$predictionBatch");
        assertRedirect(cache, "provide", "vss$registerBeforePredictionBatches");
        assertRedirect(cache, "queue", "vss$registerBeforePredictionBatches");
        try (var loader = new FabricRegistryTestLoader(Path.of(release).toUri().toURL(), getClass().getClassLoader(),
                Map.of(generatorName, UltraTerraForgedBatchLockProbe.observe(generator), cacheName, cache))) {
            Object preset = loader.loadClass(PREFIX + "data.preset.settings.BuiltinPresets")
                    .getMethod("makeDefault").invoke(null);
            Class<?> contextType = loader.loadClass(PREFIX + "world.worldgen.GeneratorContext");
            var make = contextType.getMethod("makeCached", preset.getClass(), int.class, int.class, int.class, boolean.class);
            Object owned = make.invoke(null, preset, 918273, 1, 1, true);
            Object upstream = make.invoke(null, preset, 918273, 1, 1, true);
            try {
                FreeTerraForgedPredictionTasks.own(owned);
                Object ownedCache = contextType.getField("cache").get(owned);
                Object upstreamCache = contextType.getField("cache").get(upstream);
                var provide = ownedCache.getClass().getMethod("provide", int.class, int.class);
                var queue = ownedCache.getClass().getMethod("queue", int.class, int.class);
                var present = ownedCache.getClass().getMethod("provideIfPresent", int.class, int.class);
                for (boolean queued : new boolean[]{false, true}) {
                    int x = queued ? -2 : 0, z = queued ? 3 : 0;
                    var batches = UltraTerraForgedBatchLockProbe.expect(owned, x, z);
                    Object actual;
                    if (queued) {
                        queue.invoke(ownedCache, x, z);
                        actual = present.invoke(ownedCache, x, z);
                    } else actual = provide.invoke(ownedCache, x, z);
                    assertNotNull(actual);
                    assertTrue(batches.get() > 0, "the real batch observer must execute");
                    assertSame(actual, provide.invoke(ownedCache, x, z), "repeat hits retain the published entry");
                    Object expected = provide.invoke(upstreamCache, x, z);
                    var lookup = actual.getClass().getMethod("lookup", int.class, int.class);
                    for (int[] point : new int[][]{{0,0},{3,7},{17,9},{31,31}}) {
                        Object a = lookup.invoke(actual, point[0], point[1]), b = lookup.invoke(expected, point[0], point[1]);
                        for (var field : a.getClass().getFields()) if (field.getType().isPrimitive())
                            assertEquals(field.get(b), field.get(a), "full filtered field " + field.getName());
                    }
                    System.out.println("Released UTF unlocked " + (queued ? "queue" : "provide") + ": batches=" + batches.get()
                            + ", callerThread=true, originalPendingEntry=true, writeLockFree=true, filteredFields=exact");
                }
            } finally {
                UltraTerraForgedBatchLockProbe.forget(owned);
                FreeTerraForgedCompat.releaseTileCache(contextType.getField("cache").get(owned));
                FreeTerraForgedCompat.releaseTileCache(contextType.getField("cache").get(upstream));
            }
        } finally { game.set(fabric, previousGame); config.set(fabric, previousConfig); }
    }

    private static void assertRedirect(byte[] bytes, String source, String handler) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        assertTrue(node.methods.stream().filter(m -> m.name.equals(source)).anyMatch(m ->
                java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false)
                        .anyMatch(i -> i instanceof MethodInsnNode call && call.name.contains(handler))),
                "production redirect must replace the real " + source + " call");
    }
}
