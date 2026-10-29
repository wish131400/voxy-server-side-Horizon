package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.net.URLClassLoader;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Compares the released mod's noise implementation with actual JNI results. */
@EnabledIfSystemProperty(named = "vss.lithostitchedJar", matches = ".+")
class LithostitchedNativeTest {
    @BeforeAll
    static void bootstrap() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        String library = System.getProperty("vss.testNativeLibrary");
        if (library != null) RustWorldgenBackend.load(Path.of(library));
        else assertTrue(RustTerrainSampler.available());
    }

    @Test
    void fastNoiseConfigurationsMatchReleasedJava() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("vss.lithostitchedJar")).toUri().toURL()}, getClass().getClassLoader())) {
            String prefix = "dev.worldgen.lithostitched.api.worldgen.densityfunction.fastnoise.FNL";
            Class<?> fnl = loader.loadClass(prefix);
            List<JsonObject> configs = new ArrayList<>();
            configs.add(JsonParser.parseString("{\"type\":\"lithostitched:perlin\",\"frequency\":0.013,\"salt\":-9123}").getAsJsonObject());
            for (String fractal : List.of("none", "fbm", "ridged", "ping_pong", "domain_warp_progressive", "domain_warp_independent")) {
                var c = JsonParser.parseString("{\"type\":\"lithostitched:simplex\",\"frequency\":0.017,\"salt\":2147483647,\"octaves\":4,\"gain\":0.45,\"lacunarity\":2.1}").getAsJsonObject();
                c.addProperty("fractal_type", fractal); configs.add(c);
            }
            for (String distance : List.of("euclidean", "euclidean_squared", "manhattan", "hybrid")) {
                for (String result : List.of("cell_value", "distance", "distance_2", "distance_2_add", "distance_2_sub", "distance_2_mul", "distance_2_div")) {
                    var c = JsonParser.parseString("{\"type\":\"lithostitched:cellular\",\"frequency\":0.07,\"salt\":137,\"jitter\":0.8}").getAsJsonObject();
                    c.addProperty("distance_function", distance); c.addProperty("return_type", result); configs.add(c);
                }
            }
            JsonObject doc = document();
            var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
            for (int i = 0; i < configs.size(); i++) {
                var node = JsonParser.parseString("{\"type\":\"lithostitched:fast_noise\",\"xz_scale\":1.13,\"y_scale\":0.67,\"shift_x\":0.13,\"shift_y\":-0.27,\"shift_z\":0.31}").getAsJsonObject();
                node.add("config", configs.get(i)); router.add("test_" + i, node);
            }
            var registry = new JsonObject();
            registry.add("test:perlin", configs.getFirst());
            var registries = new JsonObject(); registries.add("lithostitched:fast_noise_config", registry);
            doc.add("custom_registries", registries);
            var referenced = router.getAsJsonObject("test_0").deepCopy(); referenced.addProperty("config", "test:perlin");
            router.add("test_0", referenced);
            int[][] points = new int[72][3];
            var random = new Random(81923);
            for (int i = 0; i < points.length; i++) points[i] = new int[]{random.nextInt(60000001) - 30000000, random.nextInt(576) - 64, random.nextInt(60000001) - 30000000};
            for (int i = 0; i < 8; i++) points[i] = new int[]{i - 4, i * 16 - 64, 4 - i};
            var input = direct(points.length * 12);
            for (int[] p : points) for (int n : p) input.putInt(n);
            for (long seed : new long[]{0, -918273645, 123456789, Long.MIN_VALUE, Long.MAX_VALUE}) {
                long world = RustWorldgenBackend.create(seed, BiomeManager.obfuscateSeed(seed), doc.toString());
                try {
                    for (int i = 0; i < configs.size(); i++) {
                        var c = configs.get(i);
                        Object noise = fnl.getConstructor(int.class).newInstance((int) seed + c.get("salt").getAsInt());
                        fnl.getMethod("SetFrequency", float.class).invoke(noise, c.get("frequency").getAsFloat());
                        String type = c.get("type").getAsString().substring("lithostitched:".length());
                        setEnum(fnl, noise, "SetNoiseType", "NoiseType", Map.of("perlin", "Perlin", "simplex", "OpenSimplex2S", "cellular", "Cellular").get(type));
                        setEnum(fnl, noise, "SetFractalType", "FractalType", "None");
                        if (type.equals("simplex")) {
                            setEnum(fnl, noise, "SetFractalType", "FractalType", Map.of("none", "None", "fbm", "FBm", "ridged", "Ridged", "ping_pong", "PingPong", "domain_warp_progressive", "DomainWarpProgressive", "domain_warp_independent", "DomainWarpIndependent").get(c.get("fractal_type").getAsString()));
                            fnl.getMethod("SetFractalOctaves", int.class).invoke(noise, c.get("octaves").getAsInt());
                            fnl.getMethod("SetFractalGain", float.class).invoke(noise, c.get("gain").getAsFloat());
                            fnl.getMethod("SetFractalLacunarity", float.class).invoke(noise, c.get("lacunarity").getAsFloat());
                        } else if (type.equals("cellular")) {
                            setEnum(fnl, noise, "SetCellularDistanceFunction", "CellularDistanceFunction", Map.of("euclidean", "Euclidean", "euclidean_squared", "EuclideanSq", "manhattan", "Manhattan", "hybrid", "Hybrid").get(c.get("distance_function").getAsString()));
                            setEnum(fnl, noise, "SetCellularReturnType", "CellularReturnType", Map.of("cell_value", "CellValue", "distance", "Distance", "distance_2", "Distance2", "distance_2_add", "Distance2Add", "distance_2_sub", "Distance2Sub", "distance_2_mul", "Distance2Mul", "distance_2_div", "Distance2Div").get(c.get("return_type").getAsString()));
                            fnl.getMethod("SetCellularJitter", float.class).invoke(noise, c.get("jitter").getAsFloat());
                        }
                        var out = direct(points.length * 8);
                        assertEquals(points.length, RustWorldgenBackend.density(world, "test_" + i, input, out, points.length));
                        var sample = fnl.getMethod("GetNoise", double.class, double.class, double.class);
                        for (int p = 0; p < points.length; p++) {
                            int[] xyz = points[p];
                            double expected = ((Number) sample.invoke(noise, xyz[0] * 1.13 + (double) 0.13f, xyz[1] * 0.67 + (double) -0.27f, xyz[2] * 1.13 + (double) 0.31f)).doubleValue();
                            assertEquals(expected, out.getDouble(p * 8), 0.0, "seed=" + seed + " config=" + c + " xyz=" + Arrays.toString(xyz));
                        }
                    }
                } finally { RustWorldgenBackend.close(world); }
            }
        }
    }

    @Test
    void releasedFastNoiseSamplersDefaultsAndShiftsMatchFloatBits() throws Exception {
        try (var loader = releasedLoader()) {
            long seed = -918273645;
            var doc = document();
            var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
            var expected = new LinkedHashMap<String, DensityFunction>();
            for (double yScale : new double[]{0.0, 0.67, 1.0}) {
                for (int shifts = 0; shifts < 3; shifts++) {
                    String name = "fast_" + yScale + "_" + shifts;
                    float sx = shifts == 0 ? 0 : 0.13f;
                    float sy = shifts < 2 ? 0 : -0.27f;
                    float sz = shifts == 0 ? 0 : 0.31f;
                    var function = releasedFastSampler(loader, seed, 1.0, yScale, shifts);
                    expected.put(name, function);
                    var node = JsonParser.parseString("{\"type\":\"lithostitched:fast_noise\",\"config\":{\"type\":\"lithostitched:perlin\",\"frequency\":0.013,\"salt\":-9123}}").getAsJsonObject();
                    if (yScale != 1.0) node.addProperty("y_scale", yScale);
                    if (shifts != 0) { node.addProperty("shift_x", sx); node.addProperty("shift_z", sz); }
                    if (shifts == 2) node.addProperty("shift_y", sy);
                    router.add(name, node);
                }
            }
            compareFunctions(doc, expected, new int[][]{{0, 0, 0}, {-1, -64, 1}, {17, 70, -93},
                    {30000000, 511, -30000000}, {-30000000, -64, 30000000}}, seed);
        }
    }

    @Test
    void densityOperatorsMatchReleasedJava() throws Exception {
        try (var loader = releasedLoader()) {
            String prefix = "dev.worldgen.lithostitched.impl.worldgen.densityfunction.";
            var doc = document();
            var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
            var expected = new LinkedHashMap<String, DensityFunction>();
            var slope = DensityFunctions.yClampedGradient(-100, 100, -3.3f, 3.3f);
            for (String name : List.of("Sin", "Cos")) {
                var function = (DensityFunction) loader.loadClass(prefix + name + "Function")
                        .getConstructor(DensityFunction.class).newInstance(slope);
                expected.put(name, function);
                router.add(name, nodeJson(function, "lithostitched:" + name.toLowerCase(Locale.ROOT)));
            }
            for (float shiftY : new float[]{-1.9f, -0.9f, 0.9f, 1.9f}) {
                String name = "shift_" + shiftY;
                var function = (DensityFunction) loader.loadClass(prefix + "ShiftFunction")
                        .getConstructor(DensityFunction.class, DensityFunction.class, DensityFunction.class, DensityFunction.class)
                        .newInstance(slope, DensityFunctions.constant(0.9f), DensityFunctions.constant(shiftY), DensityFunctions.constant(1.3f));
                expected.put(name, function);
                router.add(name, nodeJson(function, "lithostitched:shift"));
            }
            int[][] points = new int[201][3];
            for (int y = -100; y <= 100; y++) points[y + 100] = new int[]{-13, y, 71};
            compareFunctions(doc, expected, points, 0);
        }
    }

    @Test
    void releasedCellular2d3dAndConditionsMatchFloatBits() throws Exception {
        try (var loader = releasedLoader()) {
            String prefix = "dev.worldgen.lithostitched.impl.worldgen.densityfunction.CellularDensityFunction";
            var type = loader.loadClass(prefix);
            var gridType = loader.loadClass(prefix + "$GridDimensions");
            var conditionType = loader.loadClass(prefix + "$CellCondition");
            var returnType = loader.loadClass("dev.worldgen.lithostitched.api.worldgen.densityfunction.cellular.ReturnType");
            var doc = document();
            var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
            var expected = new LinkedHashMap<String, DensityFunction>();
            for (int vertical : new int[]{0, 13}) {
                for (float jitter : new float[]{0, 0.73f, 1}) {
                    for (String result : List.of("CELL_VALUE", "DISTANCE_1", "DISTANCE_2", "DISTANCE_2_ADD", "DISTANCE_2_SUB", "DISTANCE_2_MUL", "DISTANCE_2_DIV")) {
                        for (int c = 0; c < 3; c++) {
                            var condition = c == 0 ? Optional.empty() : Optional.of(conditionType.getConstructor(
                                    DensityFunction.class, net.minecraft.util.InclusiveRange.class, float.class).newInstance(
                                    c == 1 ? DensityFunctions.yClampedGradient(-64, 128, -1f, 1f) : DensityFunctions.constant(2f),
                                    new net.minecraft.util.InclusiveRange<Float>(-0.5f, 0.5f), -0.375f));
                            var function = (DensityFunction) type.getConstructor(Optional.class, gridType, float.class, returnType, int.class)
                                    .newInstance(condition, gridType.getConstructor(int.class, int.class).newInstance(31, vertical), jitter,
                                            returnType.getField(result).get(null), -917);
                            String name = "cell_" + vertical + "_" + jitter + "_" + result + "_" + c;
                            expected.put(name, function);
                            router.add(name, nodeJson(function, "lithostitched:cellular"));
                        }
                    }
                }
            }
            int[][] points = new int[72][3];
            var random = new Random(81923);
            for (int i = 0; i < points.length; i++) points[i] = new int[]{random.nextInt(60000001) - 30000000, random.nextInt(576) - 64, random.nextInt(60000001) - 30000000};
            for (int i = 0; i < 8; i++) points[i] = new int[]{i - 4, i * 16 - 64, 4 - i};
            var worldSeed = loader.loadClass("dev.worldgen.lithostitched.impl.LithostitchedInternalHooks").getField("WORLD_SEED");
            for (long seed : new long[]{0, -918273645, Long.MIN_VALUE, Long.MAX_VALUE}) {
                worldSeed.setLong(null, seed);
                compareFunctions(doc, expected, points, seed);
            }
        }
    }

    @Test
    void appliedMergedSnapshotExportsExecutableFullGraph() throws Exception {
        try (var loader = releasedLoader()) {
            var original = DensityFunctions.constant(2f);
            var full = (DensityFunction) loader.loadClass("dev.worldgen.lithostitched.impl.worldgen.densityfunction.SinFunction")
                    .getConstructor(DensityFunction.class).newInstance(DensityFunctions.constant(0.7f));
            var type = loader.loadClass("dev.worldgen.lithostitched.impl.worldgen.densityfunction.marker.MergedFunction");
            var merged = (DensityFunction) type.getConstructor(DensityFunction.class, DensityFunction.class, DensityFunction.class)
                    .newInstance(original, DensityFunctions.constant(3f), full);
            var referenced = new DensityFunctions.HolderHolder(net.minecraft.core.Holder.direct(merged));
            var applied = dev.xantha.vss.common.worldgen.DensityFunctionSnapshot.applied(referenced);
            assertEquals(full.getClass(), applied.getClass());
            assertEquals(Float.floatToRawIntBits(ModernDensityTestSamples.sample(full, -7, 61, 93)),
                    Float.floatToRawIntBits(ModernDensityTestSamples.sample(applied, -7, 61, 93)));
            var doc = document();
            doc.getAsJsonObject("settings").getAsJsonObject("noise_router").add("applied_full", nodeJson(applied, "lithostitched:sin"));
            compareFunctions(doc, Map.of("applied_full", full), new int[][]{{-7, 61, 93}, {0, 0, 0}}, 0);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JsonObject nodeJson(DensityFunction function, String type) {
        var value = ((com.mojang.serialization.MapCodec) function.codec()).codec()
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, function).getOrThrow();
        var json = ((JsonElement) value).getAsJsonObject();
        json.addProperty("type", type);
        return json;
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.lithostitchedReferenceOutput", matches = ".+")
    void releasedScalarAndVolumeReferences() throws Exception {
        try (var loader = releasedLoader()) {
            String prefix = "dev.worldgen.lithostitched.impl.worldgen.densityfunction.";
            var doc = JsonParser.parseString("{\"vss_density_semantics\":\"float-26.3\",\"settings\":{\"legacy_random_source\":false,\"noise_router\":{}}}").getAsJsonObject();
            var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
            var expected = new LinkedHashMap<String, DensityFunction>();
            var slope = DensityFunctions.yClampedGradient(-100, 100, -3.3f, 3.3f);
            for (String name : List.of("Sin", "Cos")) {
                var function = (DensityFunction) loader.loadClass(prefix + name + "Function")
                        .getConstructor(DensityFunction.class).newInstance(slope);
                expected.put(name, function);
                router.add(name, nodeJson(function, "lithostitched:" + name.toLowerCase(Locale.ROOT)));
            }
            var shiftedInput = DensityFunctions.interpolated(slope.mul(slope), 4, 8);
            for (float offset : new float[]{-1.9f, -0.9f, 0.9f, 1.9f}) {
                String name = "shift_" + offset;
                var function = (DensityFunction) loader.loadClass(prefix + "ShiftFunction")
                        .getConstructor(DensityFunction.class, DensityFunction.class, DensityFunction.class, DensityFunction.class)
                        .newInstance(shiftedInput, DensityFunctions.constant(offset), DensityFunctions.constant(offset), DensityFunctions.constant(offset));
                expected.put(name, function);
                router.add(name, nodeJson(function, "lithostitched:shift"));
            }
            var cellType = loader.loadClass(prefix + "CellularDensityFunction");
            var gridType = loader.loadClass(prefix + "CellularDensityFunction$GridDimensions");
            var resultType = loader.loadClass("dev.worldgen.lithostitched.api.worldgen.densityfunction.cellular.ReturnType");
            var conditionType = loader.loadClass(prefix + "CellularDensityFunction$CellCondition");
            for (int vertical : new int[]{0, 13}) {
                for (String result : List.of("CELL_VALUE", "DISTANCE_1", "DISTANCE_2", "DISTANCE_2_ADD", "DISTANCE_2_SUB", "DISTANCE_2_MUL", "DISTANCE_2_DIV")) {
                    var condition = conditionType.getConstructor(DensityFunction.class, net.minecraft.util.InclusiveRange.class, float.class)
                            .newInstance(slope, new net.minecraft.util.InclusiveRange<Float>(-0.5f, 0.5f), -0.375f);
                    var function = (DensityFunction) cellType.getConstructor(Optional.class, gridType, float.class, resultType, int.class)
                            .newInstance(Optional.of(condition), gridType.getConstructor(int.class, int.class).newInstance(31, vertical),
                                    0.73f, resultType.getField(result).get(null), -917);
                    String name = "cell_" + vertical + "_" + result;
                    expected.put(name, function);
                    router.add(name, nodeJson(function, "lithostitched:cellular"));
                }
            }
            for (int shifts = 0; shifts < 3; shifts++) {
                String name = "fast_" + shifts;
                float sx = shifts == 0 ? 0 : 0.13f, sy = shifts < 2 ? 0 : -0.27f, sz = shifts == 0 ? 0 : 0.31f;
                var function = releasedFastSampler(loader, 0, 1.13, 0.67, shifts);
                expected.put(name, function);
                var node = JsonParser.parseString("{\"type\":\"lithostitched:fast_noise\",\"xz_scale\":1.13,\"y_scale\":0.67,\"config\":{\"type\":\"lithostitched:perlin\",\"frequency\":0.013,\"salt\":-9123}}").getAsJsonObject();
                if (shifts != 0) { node.addProperty("shift_x", sx); node.addProperty("shift_z", sz); }
                if (shifts == 2) node.addProperty("shift_y", sy);
                router.add(name, node);
            }
            var volumes = new JsonArray();
            var samples = new JsonArray();
            var random = ModernDensityFixtures.random(ModernDensityFixtures.settings(
                    net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD), 0);
            var seedField = loader.loadClass("dev.worldgen.lithostitched.impl.LithostitchedInternalHooks").getField("WORLD_SEED");
            for (long seed : new long[]{0, -918273645, Long.MIN_VALUE, Long.MAX_VALUE}) {
                seedField.setLong(null, seed);
                for (int shifts = 0; shifts < 3; shifts++)
                    expected.put("fast_" + shifts, releasedFastSampler(loader, seed, 1.13, 0.67, shifts));
                for (var entry : expected.entrySet()) {
                    var sampler = random.getSampler(entry.getValue());
                    for (var volume : List.of(
                            new net.minecraft.world.level.levelgen.densityfunction.DensityVolume(3, 7, 4, -17, -5, 31),
                            new net.minecraft.world.level.levelgen.densityfunction.DensityVolume(3, 7, 4, 29999991, 1, -30000000, 3, 2, 5))) {
                        var buffer = net.minecraft.world.level.levelgen.densityfunction.DensityBuffer.createUnpooled(volume.size());
                        sampler.sampleVolume(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED, buffer, volume);
                        var record = new JsonObject();
                        record.addProperty("seed", seed); record.addProperty("root", entry.getKey());
                        record.add("size", new Gson().toJsonTree(new int[]{volume.sizeX(), volume.sizeY(), volume.sizeZ()}));
                        record.add("origin", new Gson().toJsonTree(new int[]{volume.minBlockX(), volume.minBlockY(), volume.minBlockZ()}));
                        record.add("step", new Gson().toJsonTree(new int[]{volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ()}));
                        var bits = new JsonArray();
                        for (int i = 0; i < buffer.size(); i++) bits.add(Integer.toUnsignedLong(Float.floatToRawIntBits(buffer.get(i))));
                        record.add("bits", bits); volumes.add(record);
                        for (int[] p : new int[][]{{volume.minBlockX(), volume.minBlockY(), volume.minBlockZ()},
                                {volume.maxBlockX(), volume.maxBlockY(), volume.maxBlockZ()}}) {
                            var single = new JsonObject(); single.addProperty("seed", seed); single.addProperty("root", entry.getKey());
                            single.add("p", new Gson().toJsonTree(p));
                            single.addProperty("bits", Integer.toUnsignedLong(Float.floatToRawIntBits(sampler.sampleValue(
                                    net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED, p[0], p[1], p[2]))));
                            samples.add(single);
                        }
                    }
                }
            }
            doc.addProperty("reference_implementation", "Lithostitched 2.0.6 Fabric 26.3 / SHA256 31c5fff53420a2af5aa231a7620af089054456d9a0cfc763e571332e0253872b");
            doc.addProperty("reference_generator", "src/versions/mc263/test/dev/xantha/vss/client/prediction/LithostitchedNativeTest.java#releasedScalarAndVolumeReferences; Minecraft 26.3 / Java 25");
            doc.add("reference_samples", samples); doc.add("reference_volumes", volumes);
            Path output = Path.of(System.getProperty("vss.lithostitchedReferenceOutput"));
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, doc.toString());
            assertEquals(184, volumes.size());
            assertEquals(368, samples.size());
        }
    }

    /** Executes the actual released sampler classes without booting Fabric's registry mixins. */
    private static DensityFunction releasedFastSampler(ClassLoader loader, long seed, double xz, double y, int shifts) throws Exception {
        var fnl = loader.loadClass("dev.worldgen.lithostitched.api.worldgen.densityfunction.fastnoise.FNL");
        Object noise = fnl.getConstructor(int.class).newInstance((int) seed + -9123);
        fnl.getMethod("SetFrequency", float.class).invoke(noise, 0.013f);
        setEnum(fnl, noise, "SetNoiseType", "NoiseType", "Perlin");
        setEnum(fnl, noise, "SetFractalType", "FractalType", "None");
        var get2 = fnl.getMethod("GetNoise", double.class, double.class);
        var get3 = fnl.getMethod("GetNoise", double.class, double.class, double.class);
        var input = new net.minecraft.world.level.levelgen.synth.Noise() {
            public net.minecraft.util.Interval range() { return net.minecraft.util.Interval.of(-1, 1); }
            public float get(double x, double z) {
                try { return (float) get2.invoke(noise, x, z); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            }
            public float get(double x, double y, double z) {
                try { return (float) get3.invoke(noise, x, y, z); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            }
        };
        String prefix = "dev.worldgen.lithostitched.impl.worldgen.densityfunction.FastNoiseFunction$";
        var samplerType = net.minecraft.world.level.levelgen.densityfunction.DensitySampler.class;
        var noiseType = net.minecraft.world.level.levelgen.synth.Noise.class;
        net.minecraft.world.level.levelgen.densityfunction.DensitySampler sampler;
        var random = ModernDensityFixtures.random(ModernDensityFixtures.settings(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD), 0);
        var sx = random.getSampler(DensityFunctions.constant(0.13f));
        var sy = random.getSampler(DensityFunctions.constant(-0.27f));
        var sz = random.getSampler(DensityFunctions.constant(0.31f));
        if (shifts == 0) sampler = (net.minecraft.world.level.levelgen.densityfunction.DensitySampler)
                loader.loadClass(prefix + "Sampler").getConstructor(noiseType, double.class, double.class).newInstance(input, xz, y);
        else if (shifts == 1) sampler = (net.minecraft.world.level.levelgen.densityfunction.DensitySampler)
                loader.loadClass(prefix + "ShiftedXzSampler").getConstructor(samplerType, samplerType, noiseType, double.class, double.class)
                        .newInstance(sx, sz, input, xz, y);
        else sampler = (net.minecraft.world.level.levelgen.densityfunction.DensitySampler)
                loader.loadClass(prefix + "ShiftedXyzSampler").getConstructor(samplerType, samplerType, samplerType, noiseType, double.class, double.class)
                        .newInstance(sx, sy, sz, input, xz, y);
        return new ReleasedSampler(sampler);
    }

    private record ReleasedSampler(net.minecraft.world.level.levelgen.densityfunction.DensitySampler sampler) implements DensityFunction {
        public net.minecraft.world.level.levelgen.densityfunction.DensitySampler compileSampler(CompileContext context) { return sampler; }
        public DensityFunction rewriteChildren(net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule rule) { return this; }
        public net.minecraft.util.Interval range() { return net.minecraft.util.Interval.of(-1, 1); }
        public int domainAxes() { return 7; }
        public com.mojang.serialization.MapCodec<? extends DensityFunction> codec() { throw new UnsupportedOperationException("test harness delegates to a released sampler"); }
    }

    private static URLClassLoader releasedLoader() throws Exception {
        return new URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("vss.lithostitchedJar")).toUri().toURL()},
                LithostitchedNativeTest.class.getClassLoader());
    }

    private static void compareFunctions(JsonObject doc, Map<String, DensityFunction> expected, int[][] points, long seed) {
        var input = direct(points.length * 12);
        for (int[] p : points) for (int n : p) input.putInt(n);
        long world = RustWorldgenBackend.create(seed, BiomeManager.obfuscateSeed(seed), doc.toString());
        try {
            var output = direct(points.length * 8);
            for (var entry : expected.entrySet()) {
                assertEquals(points.length, RustWorldgenBackend.density(world, entry.getKey(), input, output, points.length));
                for (int i = 0; i < points.length; i++) {
                    int[] p = points[i];
                    float java = ModernDensityTestSamples.sample(entry.getValue(), p[0], p[1], p[2]);
                    assertEquals(Float.floatToRawIntBits(java), Float.floatToRawIntBits((float) output.getDouble(i * 8)),
                            entry.getKey() + " seed=" + seed + " xyz=" + Arrays.toString(p));
                }
            }
        } finally { RustWorldgenBackend.close(world); }
    }

    private static void setEnum(Class<?> type, Object instance, String method, String enumName, String value) throws Exception {
        Class<?> enumeration = type.getClassLoader().loadClass(type.getName() + "$" + enumName);
        Object constant = enumeration.getField(value).get(null);
        type.getMethod(method, enumeration).invoke(instance, constant);
    }

    private static JsonObject documentTemplate;

    static synchronized JsonObject document() throws Exception {
        // Use the actual production document boundary, including the material
        // registries introduced in 26.3 and its block-state representation.
        if (documentTemplate == null) documentTemplate = ModernNativeDocuments.overworld();
        return documentTemplate.deepCopy();
    }

    private static ByteBuffer direct(int size) { return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN); }
}
