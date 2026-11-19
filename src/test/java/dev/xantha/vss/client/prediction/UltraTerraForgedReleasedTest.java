package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.worldgen.FreeTerraForgedVariant;
import dev.xantha.vss.testutil.FabricRegistryTestLoader;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Executes published UTF terrain and filtering, with only Fabric registry allocation isolated. */
@EnabledIfSystemProperty(named = "vss.ultraTerraForgedJar", matches = ".+")
class UltraTerraForgedReleasedTest {
    private static final String PREFIX = "com.pandaismyname1.ultraterraforged.";

    @SuppressWarnings("unchecked")
    @Test void filteredCellsWaterAndIndependentWorldLifetime(@TempDir Path directory) throws Throwable {
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
        var harness = new dev.xantha.vss.testutil.VoxyMixinHarness(release, """
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin.client",
                 "compatibilityLevel":"JAVA_17","client":["UltraTerraForgedPredictionTasksMixin","UltraTerraForgedPredictionTileCacheMixin"],
                 "injectors":{"defaultRequire":1}}
                """);
        byte[] transformed = harness.apply(generatorName);
        var transformedNode = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(transformed).accept(transformedNode, 0);
        assertTrue(transformedNode.methods.stream().anyMatch(m -> m.name.contains("vss$predictionBatch")),
                "the production redirect must apply to the released UTF TileGenerator");
        var overrides = new java.util.HashMap<String, byte[]>();
        overrides.put(generatorName, transformed);
        String cacheName = PREFIX + "world.worldgen.tile.TileCache";
        overrides.put(cacheName, harness.apply(cacheName));
        try {
            var access = Class.forName("dev.xantha.vss.testutil.UltraTerraForgedRouterAccess");
            overrides.put(PREFIX + "data.preset.PresetNoiseRouterData",
                    (byte[]) access.getMethod("bridge", Path.class).invoke(null, Path.of(release)));
        } catch (ClassNotFoundException noBatchedRouterOnThisTarget) { }
        try (var loader = new FabricRegistryTestLoader(Path.of(System.getProperty("vss.ultraTerraForgedJar"))
                .toUri().toURL(), getClass().getClassLoader(), overrides)) {
            var api = new FreeTerraForgedCompat.Api(loader);
            assertEquals(FreeTerraForgedVariant.ULTRA, api.variant);
            assertTrue(api.variant.discoversContextFromMarkers());
            assertFalse(api.variant.usesNativeTileFilters());
            Object preset = loader.loadClass(PREFIX + "data.preset.settings.BuiltinPresets")
                    .getMethod("makeDefault").invoke(null);
            Codec<Object> codec = (Codec<Object>) preset.getClass().getField("CODEC").get(null);
            var json = codec.encodeStart(JsonOps.INSTANCE, preset).result().orElseThrow();
            Object copied = codec.parse(JsonOps.INSTANCE, json).result().orElseThrow();
            assertEquals(json, codec.encodeStart(JsonOps.INSTANCE, copied).result().orElseThrow());
            verifySnapshotAndInitializer(loader, preset, codec);
            var contextType = loader.loadClass(PREFIX + "world.worldgen.GeneratorContext");
            var make = contextType.getMethod("makeCached", preset.getClass(), int.class, int.class, int.class, boolean.class);
            Object first = make.invoke(null, preset, 918273, 1, 1, false);
            Object second = make.invoke(null, copied, 918273, 1, 1, false);
            try {
                FreeTerraForgedPredictionTasks.own(first);
                var fields = loader.loadClass(PREFIX + "world.worldgen.cell.CellField");
                Object height = fields.getField("HEIGHT").get(null);
                var reader = new FreeTerraForgedFieldReader(first, fields, true);
                assertNull(reader.heightmap, "UTF does not expose or need WorldLookup.getHeightmap");
                MethodHandle read = reader.bind(height);
                // This must return without touching the incompatible old filter layout,
                // even when a Rust library is available in the test JVM.
                FreeTerraForgedNativeFilters.register(first);
                var cacheType = contextType.getField("cache").getType();
                var provide = cacheType.getMethod("provideAtChunk", int.class, int.class);
                var lookup = provide.getReturnType().getMethod("lookup", int.class, int.class);
                Object cacheA = contextType.getField("cache").get(first);
                Object cacheB = contextType.getField("cache").get(second);
                Object tileA = provide.invoke(cacheA, 0, 0), tileB = provide.invoke(cacheB, 0, 0);
                verifyDensity(loader, first, tileA);
                for (int[] point : List.of(new int[]{0, 0}, new int[]{3, 7}, new int[]{17, 9}, new int[]{31, 31})) {
                    Object a = lookup.invoke(tileA, point[0], point[1]);
                    Object b = lookup.invoke(tileB, point[0], point[1]);
                    float value = (float) read.invokeExact(a, (Object) null);
                    assertTrue(Float.isFinite(value));
                    assertEquals((float) fields.getMethod("read", a.getClass()).invoke(height, a), value);
                    assertEquals(value, (float) read.invokeExact(b, (Object) null), "preset round trip retains full filtered output");
                }
                int compared = 0;
                for (int x = 0; x < 32; x += 4) for (int z = 0; z < 32; z += 4) {
                    Object a = lookup.invoke(tileA, x, z), b = lookup.invoke(tileB, x, z);
                    for (var field : a.getClass().getFields()) if (field.getType().isPrimitive())
                        assertEquals(field.get(a), field.get(b), "full UTF filtered field " + field.getName() + " at " + x + "," + z);
                    compared++;
                }
                assertEquals(64, compared);
                System.out.println("Released UTF owned batch: " + compared + " cells, every public primitive field matches upstream scheduling");
                var water = new FreeTerraForgedWater(first);
                Object cell = loader.loadClass(PREFIX + "world.worldgen.cell.Cell").getConstructor().newInstance();
                Object levels = contextType.getField("levels").get(first);
                var scale = levels.getClass().getMethod("scale", float.class);
                int seaY = levels.getClass().getField("waterY").getInt(levels);
                var level = cell.getClass().getField("waterLevel");
                assertEquals(Integer.MIN_VALUE, water.surfaceY(cell));
                for (float amount : new float[]{0.01f, 0.3f, 0.8f}) {
                    level.setFloat(cell, amount);
                    int y = (int) scale.invoke(levels, amount);
                    assertEquals(y > seaY ? y + 1 : Integer.MIN_VALUE, water.surfaceY(cell),
                            "match UTF RaisedWater's absolute water level and sea cutoff");
                }
                FreeTerraForgedCompat.releaseTileCache(cacheA);
                Object ownedA = ownedCache(cacheA), ownedB = ownedCache(cacheB);
                assertFalse((boolean) ownedA.getClass().getMethod("isPolling").invoke(ownedA));
                assertTrue((boolean) ownedB.getClass().getMethod("isPolling").invoke(ownedB),
                        "closing one world must not stop a second world's eviction");
                assertEquals(0, cacheSize(ownedA));
                assertTrue(cacheSize(ownedB) > 0);
                assertNotNull(provide.invoke(cacheB, -1, -1), "the reopened world still generates filtered tiles");
                copySpawnContext(loader);
            } finally {
                FreeTerraForgedCompat.releaseTileCache(contextType.getField("cache").get(first));
                FreeTerraForgedCompat.releaseTileCache(contextType.getField("cache").get(second));
            }
        } finally {
            game.set(fabric, previousGame); config.set(fabric, previousConfig);
        }
    }

    private static Object ownedCache(Object tileCache) throws Exception {
        var field = tileCache.getClass().getDeclaredField("cache"); field.setAccessible(true);
        return field.get(tileCache);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void verifySnapshotAndInitializer(ClassLoader loader, Object preset, Codec<Object> presetCodec) throws Exception {
        for (String type : List.of("module.Noises", "domain.Domains", "function.CurveFunctions"))
            loader.loadClass(PREFIX + "world.worldgen.noise." + type).getMethod("bootstrap").invoke(null);
        for (var field : loader.loadClass(PREFIX + "registries.UTFBuiltInRegistries").getFields()) {
            Object value = field.get(null);
            if (value instanceof net.minecraft.core.Registry<?> registry) registry.freeze();
        }
        Class<?> keyType = net.minecraft.resources.ResourceKey.class;
        var registryKey = java.util.Arrays.stream(keyType.getMethods()).filter(m -> m.getName().equals("createRegistryKey"))
                .findFirst().orElseThrow();
        Class<?> identifierType = registryKey.getParameterTypes()[0];
        var parse = identifierType.getMethod("tryParse", String.class);
        Object presetId = parse.invoke(null, "ultraterraforged:worldgen/preset");
        Object noiseId = parse.invoke(null, "ultraterraforged:worldgen/noise");
        var presetKey = (net.minecraft.resources.ResourceKey) registryKey.invoke(null, presetId);
        var noiseKey = (net.minecraft.resources.ResourceKey) registryKey.invoke(null, noiseId);
        var presets = new net.minecraft.core.MappedRegistry(presetKey, com.mojang.serialization.Lifecycle.stable());
        var noises = new net.minecraft.core.MappedRegistry(noiseKey, com.mojang.serialization.Lifecycle.stable());
        var create = keyType.getMethod("create", keyType, identifierType);
        register(presets, create.invoke(null, presetKey, parse.invoke(null, "ultraterraforged:preset")), preset);
        presets.freeze();
        Object registration = noises.getClass().getMethod("createRegistrationLookup").invoke(noises);
        var bootstrapMethod = java.util.Arrays.stream(loader.loadClass(PREFIX + "data.preset.PresetNoiseData").getMethods())
                .filter(m -> m.getName().equals("bootstrap") && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == preset.getClass()).findFirst().orElseThrow();
        Class<?> bootstrapType = bootstrapMethod.getParameterTypes()[1];
        Object bootstrap = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{bootstrapType}, (p, method, args) -> {
            if (method.getName().equals("register")) return register(noises, args[0], args[1]);
            if (method.getName().equals("lookup") && args[0].equals(noiseKey)) return registration;
            throw new UnsupportedOperationException(method.getName());
        });
        bootstrapMethod.invoke(null, preset, bootstrap);
        noises.freeze();
        Class<?> noiseType = loader.loadClass(PREFIX + "world.worldgen.noise.module.Noise");
        Codec<Object> noiseCodec = (Codec<Object>) noiseType.getField("DIRECT_CODEC").get(null);
        var constructor = net.minecraft.resources.RegistryDataLoader.RegistryData.class.getConstructors()[0];
        var catalog = new java.util.ArrayList<net.minecraft.resources.RegistryDataLoader.RegistryData<?>>();
        for (var pair : List.of(new Object[]{presetKey, presetCodec}, new Object[]{noiseKey, noiseCodec})) {
            Object[] args;
            if (constructor.getParameterCount() == 2) args = pair;
            else {
                Class<?> last = constructor.getParameterTypes()[2];
                args = new Object[]{pair[0], pair[1], last == boolean.class ? false : last.getMethod("none").invoke(null)};
            }
            catalog.add((net.minecraft.resources.RegistryDataLoader.RegistryData<?>) constructor.newInstance(args));
        }
        var density = new net.minecraft.core.MappedRegistry(net.minecraft.core.registries.Registries.DENSITY_FUNCTION,
                com.mojang.serialization.Lifecycle.stable()); density.freeze();
        var source = new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(
                List.<net.minecraft.core.Registry<?>>of(presets, noises, density));
        var dependencies = new dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies(source, catalog);
        var require = dependencies.getClass().getMethod("require", identifierType);
        require.invoke(dependencies, presetId); require.invoke(dependencies, noiseId);
        var document = WorldgenRegistryDependenciesTest.root(dependencies.encode());
        document.getAsJsonObject("density_functions").addProperty("vss:test", 0.0);
        var decoded = ClientWorldgenRegistries.decode(document, net.minecraft.core.RegistryAccess.EMPTY, catalog).access();
        Object decodedNoises = java.util.Arrays.stream(decoded.getClass().getMethods())
                .filter(m -> (m.getName().equals("lookupOrThrow") || m.getName().equals("registryOrThrow"))
                        && m.getParameterCount() == 1).findFirst().orElseThrow().invoke(decoded, noiseKey);
        var read = java.util.Arrays.stream(decodedNoises.getClass().getMethods())
                .filter(m -> (m.getName().equals("get") || m.getName().equals("getValue"))
                        && java.util.Arrays.equals(m.getParameterTypes(), new Class<?>[]{keyType}))
                .findFirst().orElseThrow();
        var compute = noiseType.getMethod("compute", float.class, float.class, int.class);
        int entries = 0;
        for (Object entryObject : noises.entrySet()) {
            var entry = (java.util.Map.Entry<?, ?>) entryObject;
            Object value = read.invoke(decodedNoises, entry.getKey());
            if (value instanceof java.util.Optional<?> optional) value = optional.orElseThrow();
            if (value instanceof net.minecraft.core.Holder<?> holder) value = holder.value();
            for (int i = 0; i < 4; i++) assertEquals(compute.invoke(entry.getValue(), i * 317f - 731f, -i * 127f, 918273),
                    compute.invoke(value, i * 317f - 731f, -i * 127f, 918273), entry.getKey().toString());
            entries++;
        }
        assertTrue(entries > 10, "the actual UTF default noise graph must survive network RegistryOps round trip");
        var api = new FreeTerraForgedCompat.Api(loader);
        var contexts = new java.util.ArrayList<Object>();
        try {
            for (var access : List.of(source, decoded)) {
                Object mixin = newStateMixin(loader, preset);
                Class<?> mixinType = mixin.getClass();
                Object state = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{api.stateType}, (p, method, args) -> {
                    var target = mixinType.getDeclaredMethod("ultraterraforged$UTFRandomState$" + method.getName(), method.getParameterTypes());
                    target.setAccessible(true); return target.invoke(mixin, args);
                });
                api.initialize(state, access);
                Object context = api.context.invoke(state); assertNotNull(context);
                assertEquals(FreeTerraForgedVariant.ULTRA, FreeTerraForgedCompat.variant(state));
                contexts.add(context);
                Object cache = context.getClass().getField("cache").get(context);
                Object tile = cache.getClass().getMethod("provideAtChunk", int.class, int.class).invoke(cache, 0, 0);
                verifyDensity(loader, context, tile);
                if (access == source) try {
                    var verifier = Class.forName("dev.xantha.vss.client.prediction.UltraTerraForgedExteriorReleasedTest");
                    var method = verifier.getDeclaredMethod("verify", ClassLoader.class, Object.class, Object.class,
                            net.minecraft.core.RegistryAccess.class);
                    method.setAccessible(true); method.invoke(null, loader, context, mixin, access);
                } catch (ClassNotFoundException noBatchedExteriorOnThisTarget) {
                    // Only the 1.21.1 overlay has this optimizer; other targets retain their own terrain paths.
                }
            }
        } finally {
            for (Object context : contexts) FreeTerraForgedCompat.releaseTileCache(context.getClass().getField("cache").get(context));
        }
    }

    private static Object register(Object registry, Object key, Object value) throws Exception {
        var method = java.util.Arrays.stream(registry.getClass().getMethods()).filter(m -> m.getName().equals("register")
                && m.getParameterCount() == 3 && m.getParameterTypes()[0] == net.minecraft.resources.ResourceKey.class)
                .findFirst().orElseThrow();
        Class<?> info = method.getParameterTypes()[2];
        Object registration = info == com.mojang.serialization.Lifecycle.class ? com.mojang.serialization.Lifecycle.stable()
                : info.getField("BUILT_IN").get(null);
        return method.invoke(registry, key, value, registration);
    }

    private static Object newStateMixin(ClassLoader loader, Object preset) throws Exception {
        var type = loader.loadClass(PREFIX + "mixin.MixinRandomState");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object mixin = constructor.newInstance();
        Class<?> fields = loader.loadClass(PREFIX + "world.worldgen.cell.CellField");
        Object height = fields.getField("HEIGHT").get(null);
        Class<?> routerType = net.minecraft.world.level.levelgen.NoiseRouter.class;
        var components = routerType.getRecordComponents();
        Class<?> densityType = components[0].getType();
        boolean compiled = densityType.getName().contains("densityfunction.");
        Object cell = compiled ? loader.loadClass(PREFIX + "world.worldgen.densityfunction.CellSampler").getConstructor(fields).newInstance(height)
                : loader.loadClass(PREFIX + "world.worldgen.densityfunction.CellSampler$Marker").getConstructor(fields).newInstance(height);
        Object[] values = new Object[components.length]; java.util.Arrays.fill(values, cell);
        var signature = java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        Object router = routerType.getConstructor(signature).newInstance(values);
        if (compiled) {
            var seed = type.getDeclaredField("seed"); seed.setAccessible(true); seed.setLong(mixin, 918273L);
            var field = type.getDeclaredField("router"); field.setAccessible(true); field.set(mixin, router);
            var init = type.getDeclaredMethod("ultraterraforged$init", org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
            init.setAccessible(true); init.invoke(mixin, new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("init", false));
        } else {
            // Execute the published constructor redirect before initialize; CellSampler
            // suppliers must remain unread until its GeneratorContext is installed.
            var redirect = java.util.Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals("RandomState")).findFirst().orElseThrow();
            redirect.setAccessible(true);
            Class<?> visitor = redirect.getParameterTypes()[1];
            Object identity = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{visitor}, (p, method, args) -> args[0]);
            redirect.invoke(mixin, router, identity, null, null, 918273L);
            var samplerField = java.util.Arrays.stream(type.getDeclaredFields())
                    .filter(f -> f.getType() == net.minecraft.world.level.biome.Climate.Sampler.class).findFirst().orElseThrow();
            samplerField.setAccessible(true);
            Class<?> function = densityType;
            Class<?> functions = Class.forName("net.minecraft.world.level.levelgen.DensityFunctions");
            Object zero = functions.getMethod("zero").invoke(null);
            var sampler = net.minecraft.world.level.biome.Climate.Sampler.class.getConstructor(function, function, function, function,
                    function, function, List.class).newInstance(zero, zero, zero, zero, zero, zero, List.of());
            samplerField.set(mixin, sampler);
        }
        return mixin;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void verifyDensity(ClassLoader loader, Object context, Object tile) throws Exception {
        Class<?> fields = loader.loadClass(PREFIX + "world.worldgen.cell.CellField");
        Class<?> sourceType = loader.loadClass(PREFIX + "world.worldgen.densityfunction.CellSampler");
        Class<?> routerType = net.minecraft.world.level.levelgen.NoiseRouter.class;
        var components = routerType.getRecordComponents();
        Class<?> density = components[0].getType();
        Class<?>[] types = java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        boolean compiled = density.getName().contains("densityfunction.");
        var lookup = tile.getClass().getMethod("lookup", int.class, int.class);
        for (String name : List.of("HEIGHT", "TEMPERATURE", "EROSION")) {
            Object field = fields.getField(name).get(null);
            Object source = compiled ? sourceType.getConstructor(fields).newInstance(field)
                    : sourceType.getConstructor(java.util.function.Supplier.class, fields)
                            .newInstance((java.util.function.Supplier<Object>) () -> context, field);
            Object[] values = new Object[components.length]; java.util.Arrays.fill(values, source);
            var router = (net.minecraft.world.level.levelgen.NoiseRouter) routerType.getConstructor(types).newInstance(values);
            Object mapped = FreeTerraForgedDensity.map(context, router).finalDensity();
            Object evaluator = mapped;
            java.lang.reflect.Method sample;
            Class<?> pointType;
            Object empty = null;
            if (compiled) {
                Class<?> compile = Class.forName(density.getName() + "$CompileContext");
                evaluator = density.getMethod("compileSampler", compile).invoke(mapped, org.mockito.Mockito.mock((Class) compile));
                pointType = Class.forName("net.minecraft.world.level.levelgen.densityfunction.SamplerContext");
                empty = pointType.getField("EMPTY_UNCACHED").get(null);
                sample = Class.forName("net.minecraft.world.level.levelgen.densityfunction.DensitySampler")
                        .getMethod("sampleValue", pointType, int.class, int.class, int.class);
            } else {
                pointType = Class.forName(density.getName() + "$FunctionContext");
                sample = density.getMethod("compute", pointType);
            }
            for (int x : new int[]{1, 3, 17, 31}) {
                int z = 31 - x;
                Object cell = lookup.invoke(tile, x, z);
                double expected = ((Number) fields.getMethod("read", cell.getClass()).invoke(field, cell)).doubleValue();
                for (int y : new int[]{-30, 80, 250}) {
                    Number actual = (Number) (compiled ? sample.invoke(evaluator, empty, x, y, z)
                            : sample.invoke(evaluator, Class.forName(density.getName() + "$SinglePointContext")
                                    .getConstructor(int.class, int.class, int.class).newInstance(x, y, z)));
                    assertEquals(expected, actual.doubleValue(), 0.0, name + " exact filtered column " + x + "," + z);
                }
            }
        }
    }
    private static int cacheSize(Object cache) throws Exception {
        var field = cache.getClass().getDeclaredField("map"); field.setAccessible(true);
        return (int) field.getType().getMethod("size").invoke(field.get(cache));
    }
    private static void copySpawnContext(ClassLoader loader) throws Exception {
        Class<?> api;
        try { api = loader.loadClass(PREFIX + "world.worldgen.biome.UTFClimateSampler"); }
        catch (ClassNotFoundException compiledClimateApi) { return; } // 26.3 has no mutable Climate.Sampler API.
        var spawn = new net.minecraft.core.BlockPos(-173, 97, 4097);
        Object source = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{api},
                (p, method, args) -> spawn);
        var copied = new java.util.HashMap<String, Object>();
        Object target = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{api},
                (p, method, args) -> { copied.put(method.getName(), args[0]); return null; });
        FreeTerraForgedCompat.copyClimateContext(source, target);
        assertEquals(spawn, copied.get("setSpawnSearchCenter"));
    }
}
