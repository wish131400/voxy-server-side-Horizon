package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "vss.tectonicJar", matches = ".+")
class TectonicNativeTest {
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        dev.xantha.vss.testutil.TectonicCodecFixtures.register();
        String library = System.getProperty("vss.testNativeLibrary");
        if (library != null) RustWorldgenBackend.load(Path.of(library));
        else assertTrue(RustTerrainSampler.available());
    }

    @Test void releasedConfiguredNoiseAndOverkillSnapshotsRunInRustWithExactFloatBits(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        try (var loader = TectonicPresetSnapshotTest.releasedLoader(directory)) {
            var lookup = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
            var noises = lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE);
            var noise = noises.getOrThrow(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.NOISE,
                    net.minecraft.resources.Identifier.parse("minecraft:continentalness")));
            var source = new java.util.LinkedHashMap<String, DensityFunction>();
            for (boolean smoother : new boolean[]{false, true}) {
                source.put("config_" + smoother,
                        TectonicPresetSnapshotTest.releasedNoise(loader, noise, 0.13f, 1.25f, -0.8f, smoother));
            }
            for (String key : List.of("continents", "erosion"))
                source.put("overkill_" + key, TectonicPresetSnapshotTest.presetNoise(loader, key, noise));
            var doc = LithostitchedNativeTest.document();
            var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
            var ops = lookup.createSerializationContext(JsonOps.INSTANCE);
            for (var entry : source.entrySet()) router.add(entry.getKey(), DensityFunctions.DIRECT_CODEC.encodeStart(
                    ops, dev.xantha.vss.common.worldgen.DensityFunctionSnapshot.applied(entry.getValue())).getOrThrow());
            var generator = new JsonObject();
            generator.add("settings", doc.get("settings"));
            generator.add("biome_source", doc.get("biome_source"));
            assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, doc),
                    "an applied smoother-scaling preset must remain eligible for Rust");
            int[][] positions = {{-17, -96, 31}, {0, 0, 0}, {8191, 128, -8193},
                    {-29_999_999, 383, 29_999_999}, {1, -1, -1}, {29_999_999, 129, -29_999_999}};
            var points = ByteBuffer.allocateDirect(positions.length * 12).order(ByteOrder.LITTLE_ENDIAN);
            for (int[] point : positions) for (int coordinate : point) points.putInt(coordinate);
            var output = ByteBuffer.allocateDirect(positions.length * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (long seed : new long[]{0, 78123L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                long world = RustWorldgenBackend.create(seed, 0, doc.toString());
                try {
                    for (var entry : source.entrySet()) {
                        var zero = DensityFunctions.zero();
                        var graph = new net.minecraft.world.level.levelgen.NoiseRouter(zero, zero, zero, zero,
                                zero, zero, zero, entry.getValue());
                        var random = net.minecraft.world.level.levelgen.RandomState.create(noises, seed, false,
                                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 63, graph);
                        var expected = random.getSampler(entry.getValue());
                        assertEquals(positions.length, RustWorldgenBackend.density(world, entry.getKey(), points, output,
                                positions.length));
                        for (int i = 0; i < positions.length; i++) {
                            int[] point = positions[i];
                            float value = expected.sampleValue(
                                    net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED,
                                    point[0], point[1], point[2]);
                            assertEquals(Double.doubleToRawLongBits((double) value),
                                    Double.doubleToRawLongBits(output.getDouble(i * 8)),
                                    entry.getKey() + " seed=" + seed + " pos=" + java.util.Arrays.toString(point));
                        }
                    }
                } finally { RustWorldgenBackend.close(world); }
            }
        }
    }

    @Test void noiseSeedsMatchTheReleasedTectonicMixinAndKeepOriginalParameters() throws Exception {
        var url=Path.of(System.getProperty("vss.tectonicJar")).toUri().toURL();
        try(var loader=new URLClassLoader(new java.net.URL[]{url},getClass().getClassLoader())) {
            var remap=loader.loadClass("dev.worldgen.tectonic.mixin.NoisesMixin")
                    .getDeclaredMethod("tectonic$fixTectonicNoiseSeeds",net.minecraft.resources.Identifier.class);
            remap.setAccessible(true);
            var export=Class.forName("dev.xantha.vss.networking.server.session.WorldgenCodecSnapshot")
                    .getDeclaredMethod("noiseSeedAliases",java.util.Set.class,boolean.class);
            export.setAccessible(true);
            var names=java.util.stream.Stream.of("tectonic:parameter/continentalness","tectonic:parameter/erosion",
                    "tectonic:parameter/offset","tectonic:island/continents_a","minecraft:continentalness")
                    .map(net.minecraft.resources.Identifier::parse).toList();
            var aliases=(JsonObject)export.invoke(null,new java.util.HashSet<>(names),true);
            var doc=LithostitchedNativeTest.document();
            doc.add("noise_seed_aliases",aliases);
            for(int i=0;i<names.size();i++) {
                var parameters=net.minecraft.world.level.levelgen.synth.NormalNoise.createParity(-6-i,
                        new it.unimi.dsi.fastutil.doubles.DoubleArrayList(new double[]{1,0,0.25+i*0.125}));
                doc.getAsJsonObject("noises").add(names.get(i).toString(),
                        net.minecraft.world.level.levelgen.synth.NormalNoise.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE,parameters).getOrThrow());
                var node=new JsonObject();node.addProperty("type","minecraft:noise");node.addProperty("noise",names.get(i).toString());
                node.addProperty("xz_scale",0.13);node.addProperty("y_scale",0.25);
                doc.getAsJsonObject("settings").getAsJsonObject("noise_router").add("seed_probe_"+i,node);
            }
            for(long seed:new long[]{0,731,4200473513645810264L}) {
                long world=RustWorldgenBackend.create(seed,0,doc.toString());
                try {
                    var factory=new net.minecraft.world.level.levelgen.XoroshiroRandomSource(seed).forkPositional();
                    var input=ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
                    var output=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
                    for(int i=0;i<names.size();i++) {
                        var key=(net.minecraft.resources.Identifier)remap.invoke(null,names.get(i));
                        var parameters=net.minecraft.world.level.levelgen.synth.NormalNoise.DIRECT_CODEC
                                .parse(JsonOps.INSTANCE,doc.getAsJsonObject("noises").get(names.get(i).toString())).getOrThrow();
                        var expected=parameters.create(factory.fromHashOf(key));
                        for(int[] pos:new int[][]{{-186,128,-50},{0,0,0},{777,-17,-919},{10000,256,-10000}}) {
                            input.putInt(0,pos[0]).putInt(4,pos[1]).putInt(8,pos[2]);
                            RustWorldgenBackend.density(world,"seed_probe_"+i,input,output,1);
                            assertEquals(expected.get(pos[0]*0.13,pos[1]*0.25,pos[2]*0.13),output.getDouble(0),1e-12,
                                    names.get(i)+" seed="+seed+" pos="+java.util.Arrays.toString(pos));
                        }
                    }
                } finally {RustWorldgenBackend.close(world);}
            }
        }
    }

    @Test @SuppressWarnings("unchecked")
    void releasedInvertCodecRunsInRustWithMatchingIeeeResults() throws Exception {
        var url = Path.of(System.getProperty("vss.tectonicJar")).toUri().toURL();
        try (var loader = new URLClassLoader(new java.net.URL[]{url}, getClass().getClassLoader())) {
            var type = loader.loadClass("dev.worldgen.tectonic.worldgen.densityfunction.Invert");
            Codec<DensityFunction> codec = ((MapCodec<DensityFunction>) type.getField("DATA_CODEC").get(null)).codec();
            var doc = LithostitchedNativeTest.document();
            var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
            var expected = new java.util.LinkedHashMap<String, DensityFunction>();
            var inputs = List.of(DensityFunctions.yClampedGradient(-128, 128, -2, 2),
                    DensityFunctions.constant(0.0f), DensityFunctions.constant(-0.0f),
                    DensityFunctions.constant(Float.MIN_VALUE), DensityFunctions.constant(-Float.MIN_VALUE),
                    DensityFunctions.constant(0.25f), DensityFunctions.constant(-7.0f));
            for (int i = 0; i < inputs.size(); i++) {
                var function = (DensityFunction) type.getMethod("create", DensityFunction.class).invoke(null, inputs.get(i));
                var json = codec.encodeStart(JsonOps.INSTANCE, function).getOrThrow().getAsJsonObject();
                json.addProperty("type", "tectonic:invert");
                router.add("invert_" + i, json);
                expected.put("invert_" + i, function);
            }
            var generator = new JsonObject();
            generator.add("settings", doc.get("settings"));
            generator.add("biome_source", doc.get("biome_source"));
            assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, doc),
                    "supported reciprocal must not force the entire terrain graph to Java");
            long world = RustWorldgenBackend.create(731, 0, doc.toString());
            try {
                var points = ByteBuffer.allocateDirect(385 * 12).order(ByteOrder.LITTLE_ENDIAN);
                var results = ByteBuffer.allocateDirect(385 * 8).order(ByteOrder.LITTLE_ENDIAN);
                for (int y = -192; y <= 192; y++) points.putInt(-17).putInt(y).putInt(256);
                for (var entry : expected.entrySet()) {
                    assertEquals(385, RustWorldgenBackend.density(world, entry.getKey(), points, results, 385));
                    for (int y = -192; y <= 192; y++) {
                        double value = ModernDensityTestSamples.sample(entry.getValue(), -17, y, 256);
                        assertEquals(Double.doubleToRawLongBits(value),
                                Double.doubleToRawLongBits(results.getDouble((y + 192) * 8)), entry.getKey() + " y=" + y);
                    }
                }
            } finally { RustWorldgenBackend.close(world); }
        }
    }
}
