package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual GPU comparisons against independently computed Rust densities and
 * complete preview records. Released Lithostitched fixtures also cover Java parity. */
class VulkanModDensityTest {
    private static ByteBuffer direct(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static JsonElement json(String text) { return JsonParser.parseString(text); }
    private static JsonObject interpolate(JsonElement input, int xz, int y) {
        var value = new JsonObject(); value.addProperty("type", "minecraft:interpolated");
        value.addProperty("cell_size_xz", xz); value.addProperty("cell_size_y", y); value.add("input", input); return value;
    }
    @Test void modNodesAndNestedVolumesMatchRust() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("vss.testVulkan"));
        RustWorldgenBackend.load(Path.of(System.getProperty("vss.testNativeLibrary")));
        dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap();
        var base = ModernNativeDocuments.overworld();
        var fixture = json(Files.readString(Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen263/lithostitched-2.0.6.json"))).getAsJsonObject();
        var cases = new LinkedHashMap<String, JsonElement>();
        fixture.getAsJsonObject("settings").getAsJsonObject("noise_router").entrySet().forEach(e -> cases.put(e.getKey(), e.getValue()));
        var gradient = json("{\"type\":\"minecraft:gradient\",\"axis\":\"y\",\"from_coordinate\":-100,\"to_coordinate\":100,\"from_value\":-3.3,\"to_value\":3.3}");
        cases.put("nested-volume", interpolate(interpolate(gradient, 5, 7), 4, 8));
        cases.put("nested-shift", interpolate(cases.get("shift_-1.9"), 7, 5));
        for (String tiling : List.of("repeat", "mirrored_repeat")) {
            var g = gradient.deepCopy().getAsJsonObject(); g.addProperty("tiling", tiling);
            cases.put(tiling, interpolate(g, 4, 7));
        }
        for (String op : List.of("sqrt", "log", "sign", "floor", "round", "ceil", "truncate")) {
            var n = new JsonObject(); n.addProperty("type", "minecraft:" + op); n.add("input", gradient); cases.put(op, n);
        }
        cases.put("pow", json("{\"type\":\"minecraft:pow\",\"base\":1.7,\"exponent\":3.3}"));
        for (String fractal : List.of("none", "fbm", "ridged", "ping_pong", "domain_warp_progressive", "domain_warp_independent")) {
            var n = cases.get("fast_2").deepCopy().getAsJsonObject(); var config = n.getAsJsonObject("config");
            config.addProperty("type", "lithostitched:simplex"); config.addProperty("fractal_type", fractal);
            config.addProperty("octaves", 4); config.addProperty("gain", -0.63); config.addProperty("lacunarity", 1.81);
            cases.put("simplex-" + fractal, interpolate(n, 4, 8));
        }
        for (String metric : List.of("euclidean", "euclidean_squared", "manhattan", "hybrid")) {
            for (String result : List.of("cell_value", "distance", "distance_2", "distance_2_add", "distance_2_sub", "distance_2_mul", "distance_2_div")) {
                var n = cases.get("fast_1").deepCopy().getAsJsonObject(); var config = n.getAsJsonObject("config");
                config.addProperty("type", "lithostitched:cellular"); config.addProperty("distance_function", metric);
                config.addProperty("return_type", result); config.addProperty("jitter", -0.73);
                cases.put("fnl-" + metric + "-" + result, n);
            }
        }
        for (boolean smooth : List.of(false, true)) {
            var n = json("{\"type\":\"vss:tectonic_config_noise\",\"noise\":\"vss:test\",\"shift_x\":0.375,\"shift_z\":-0.8,\"scale\":0.13,\"multiplier\":1.25,\"offset\":-0.375}").getAsJsonObject();
            n.addProperty("smoother_scaling", smooth); cases.put("tectonic-scalar-" + smooth,n);cases.put("tectonic-" + smooth, interpolate(n, 4, 8));
            var shifted=n.deepCopy();shifted.add("shift_x",gradient);cases.put("tectonic-y-dependent-"+smooth,interpolate(interpolate(shifted,5,7),4,8));
        }
        base.getAsJsonObject("noises").add("vss:test", json("{\"base_octave\":-3,\"octave_count\":3,\"amplitude_modifiers\":[1.0,0.25,0.5]}"));
        for (var entry : cases.entrySet()) {
            if (!entry.getKey().matches(System.getProperty("vss.vulkanCase", ".*"))) continue;
            var document = base.deepCopy(); document.getAsJsonObject("settings").getAsJsonObject("noise_router").add("final_density", entry.getValue());
            for (long seed : new long[]{42, -917}) {
                long world = RustWorldgenBackend.create(seed, 0, document.toString());
                var program = json(RustWorldgenBackend.vulkanProgram(world)).getAsJsonObject();
                try (var gpu = new VulkanDensityDevice(program)) {
                    var random = new Random(seed); int total = 0, exact = 0; double maxError = 0;
                    for (int round = 0; round < 4; round++) {
                        int[] xyz = new int[64 * 3]; var positions = direct(64 * 12); var density = direct(64 * 8); var xz = direct(64 * 8);
                        for (int i = 0; i < 64; i++) {
                            int x = i < 12 ? i - 6 : random.nextInt(59_000_001) - 29_500_000;
                            int z = i < 12 ? 6 - i : random.nextInt(59_000_001) - 29_500_000;
                            int y = i < 12 ? i - 6 : random.nextInt(384) - 64;
                            xyz[3*i]=x;xyz[3*i+1]=y;xyz[3*i+2]=z; positions.putInt(x).putInt(y).putInt(z);xz.putInt(x).putInt(z);
                        }
                        assertEquals(64, RustWorldgenBackend.density(world, "final_density", positions, density, 64));
                        int[] values = gpu.dispatch(xyz, 64, false, -64, 319);
                        for (int i = 0; i < 64; i++) {
                            float a = (float)density.getDouble(8*i), b = Float.intBitsToFloat(values[i]);
                            String label = entry.getKey()+" seed="+seed+" index="+i+" xyz="+Arrays.toString(Arrays.copyOfRange(xyz,3*i,3*i+3));
                            if (Float.isNaN(a)) { assertTrue(Float.isNaN(b), label); continue; }
                            if (Float.isInfinite(a)) { assertEquals(a,b,label); continue; }
                            assertEquals(a>0,b>0,label+" values="+a+" / "+b); double error=Math.abs((double)a-b); maxError=Math.max(maxError,error);
                            assertTrue(error <= Math.max(0.000002,Math.abs(a)*0.000002),label+" density="+a+" / "+b);
                            if (Float.floatToRawIntBits(a)==values[i]) exact++; total++;
                        }
                        // NaN/inf density graphs are still valid inputs to the height predicate.
                        var heights = direct(64*4); for (int h:gpu.dispatch(xyz,64,true,-64,319)) { assertNotEquals(Integer.MIN_VALUE,h,entry.getKey());heights.putInt(h); }
                        var cpu=direct(64*40);var output=direct(64*40);
                        assertEquals(64,RustWorldgenBackend.previewPoints(world,xz,cpu,64));
                        assertEquals(64,RustWorldgenBackend.previewPointsGpu(world,xz,output,64,heights));
                        for(int i=0;i<640;i++)assertEquals(cpu.getInt(4*i),output.getInt(4*i),entry.getKey()+" preview "+i);
                    }
                    int[] probe=new int[64*3];var probeXz=direct(64*8);
                    for(int i=0;i<64;i++){probe[i*3]=i*431-13523;probe[i*3+2]=i*177-6721;probeXz.putInt(probe[i*3]).putInt(probe[i*3+2]);}
                    VulkanDensityTest.verifyDisplayBoundaries(world,gpu,probe,probeXz,-64,319);
                    System.out.println("Vulkan mod "+entry.getKey()+" seed="+seed+" exact="+exact+"/"+total+" maxError="+maxError);
                } finally { RustWorldgenBackend.close(world); }
            }
        }
    }
    @Test void unsupportedMathReturnsCpuFallbackMarker() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("vss.testVulkan"));
        RustWorldgenBackend.load(Path.of(System.getProperty("vss.testNativeLibrary")));
        dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap();
        var doc=ModernNativeDocuments.overworld();
        doc.getAsJsonObject("settings").getAsJsonObject("noise_router").add("final_density",json("{\"type\":\"lithostitched:sin\",\"input\":200000000.0}"));
        long world=RustWorldgenBackend.create(42,0,doc.toString());
        try(var gpu=new VulkanDensityDevice(json(RustWorldgenBackend.vulkanProgram(world)).getAsJsonObject())){
            assertTrue(Float.isNaN(Float.intBitsToFloat(gpu.dispatch(new int[]{0,0,0},1,false,-64,319)[0])));
            assertEquals(Integer.MIN_VALUE,gpu.dispatch(new int[]{0,0,0},1,true,-64,319)[0]);
        }finally{RustWorldgenBackend.close(world);}
    }

}
