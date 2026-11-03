package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in actual GPU parity and end-to-end timing; no OpenGL/game context. */
class VulkanDensityTest {
    private static ByteBuffer direct(int n) { return ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN); }
    @Test void seededFloatDensityAndPreviewRecordsMatchRustOnGpu() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("vss.testVulkan"));
        RustWorldgenBackend.load(Path.of(System.getProperty("vss.testNativeLibrary")));
        dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap();
        var complete = ModernNativeDocuments.overworld();
        Path root = Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen263");
        for (String dimension : new String[]{"overworld", "nether", "end"}) {
            var document = JsonParser.parseString(Files.readString(root.resolve(dimension + ".json"))).getAsJsonObject();
            for (var entry : complete.entrySet()) if (!document.has(entry.getKey())) document.add(entry.getKey(), entry.getValue());
            var base = Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen");
            for (String map : new String[]{"grass", "foliage"}) document.add(map + "_colormap", JsonParser.parseString(Files.readString(base.resolve(map + ".json"))));
            // Actual target registries supply surface rules and the block palette.
            if (!document.has("biome_source")) document.add("biome_source", JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:plains\"}"));
            for (long seed : new long[]{42, -917}) {
                System.out.println("Vulkan fixture " + dimension + " seed=" + seed + " creating Rust world");
                long world = RustWorldgenBackend.create(seed, 0, document.toString());
                long displayWorld = RustWorldgenBackend.create(seed, 0, document.toString());
                try {
                    System.out.println("Exporting seeded Vulkan graph");
                    var program = JsonParser.parseString(RustWorldgenBackend.vulkanProgram(world)).getAsJsonObject();
                    System.out.println("Compiling Vulkan graph nodes=" + program.get("nodes") + " sourceBytes=" + program.get("source").getAsString().length());
                    try (var gpu = new VulkanDensityDevice(program)) {
                        int bottom = document.getAsJsonObject("settings").getAsJsonObject("noise").get("min_y").getAsInt();
                        int top = bottom + document.getAsJsonObject("settings").getAsJsonObject("noise").get("height").getAsInt() - 1;
                        var random = new Random(seed);
                        var positions = direct(64 * 12); var xz = direct(64 * 8); var density = direct(64 * 8);
                        var heights = direct(64 * 4); var cpu = direct(64 * 40); var output = direct(64 * 40);
                        long gpuNanos = 0, cpuNanos = 0; int exact = 0, total = 0; double maxError = 0;
                        for (int round = 0; round < 12; round++) {
                            int[] xyz = new int[64 * 3]; positions.clear(); xz.clear();
                            for (int i = 0; i < 64; i++) {
                                int x = random.nextInt(59_000_001) - 29_500_000;
                                int z = random.nextInt(59_000_001) - 29_500_000;
                                int y = bottom + random.nextInt(top - bottom + 1);
                                xyz[i * 3] = x; xyz[i * 3 + 1] = y; xyz[i * 3 + 2] = z;
                                positions.putInt(x).putInt(y).putInt(z); xz.putInt(x).putInt(z);
                            }
                            RustWorldgenBackend.density(world, "final_density", positions, density, 64);
                            int[] values = gpu.dispatch(xyz, 64, false, bottom, top);
                            for (int i = 0; i < 64; i++) {
                                float a = (float) density.getDouble(i * 8), b = Float.intBitsToFloat(values[i]);
                                double error = Math.abs(a - b); maxError = Math.max(maxError, error);
                                if (Float.floatToRawIntBits(a) == values[i]) exact++; total++;
                                assertEquals(a > 0, b > 0, dimension + " density sign " + i);
                                assertTrue(Float.isFinite(b) && error < 0.000002, dimension + " density " + a + " / " + b);
                            }
                            long start = System.nanoTime();
                            RustWorldgenBackend.previewPoints(world, xz, cpu, 64);
                            long cpuTime = System.nanoTime() - start;
                            start = System.nanoTime();
                            int[] result = gpu.dispatch(xyz, 64, true, bottom, top);
                            heights.clear(); for (int h : result) heights.putInt(h);
                            RustWorldgenBackend.previewPointsGpu(world, xz, output, 64, heights);
                            long gpuTime = System.nanoTime() - start;
                            if (round >= 3) { cpuNanos += cpuTime; gpuNanos += gpuTime; }
                            for (int i = 0; i < 640; i++) assertEquals(cpu.getInt(i * 4), output.getInt(i * 4), dimension + " preview record " + i / 10 + " field " + i % 10);
                            verifyDisplayBoundaries(world,gpu,xyz,xz,bottom,top);
                            int[] displayFloors=gpu.dispatch(xyz,64,2,bottom,top);
                            heights.clear();for(int h:displayFloors)heights.putInt(h);
                            assertEquals(64,RustWorldgenBackend.displayPoints(world,xz,cpu,64));
                            assertEquals(64,RustWorldgenBackend.displayPointsGpu(displayWorld,xz,output,64,heights));
                            for(int i=0;i<640;i++)assertEquals(cpu.getInt(i*4),output.getInt(i*4),dimension+" display record "+i/10+" field "+i%10);
                        }
                        if (dimension.equals("overworld") && seed==42) verifyBatchSizes(world,gpu,bottom,top);
                        System.out.println("Vulkan " + gpu.name + " " + dimension + " seed=" + seed + " nodes=" + program.get("nodes")
                                + " exactDensity=" + exact + "/" + total + " maxError=" + maxError
                                + " CPU64ms=" + cpuNanos / 9_000_000.0 + " GPU64ms=" + gpuNanos / 9_000_000.0);
                    }
                    if (seed == 42 && !dimension.equals("end")) {
                        int bottom = document.getAsJsonObject("settings").getAsJsonObject("noise").get("min_y").getAsInt();
                        int height = document.getAsJsonObject("settings").getAsJsonObject("noise").get("height").getAsInt();
                        verifyLifecycle(world, bottom, height, dimension.equals("overworld"));
                        if (dimension.equals("overworld")) { verifyLifecycle(world, bottom, height, true); verifyMergedThroughput(world,bottom,height); }
                    }
                } finally { RustWorldgenBackend.close(displayWorld);RustWorldgenBackend.close(world); }
            }
        }
    }

    /** Exercise the production switch and worker, including a second session.
     * The simpler Nether graph should remain on Rust on this test device. */
    static void verifyDisplayBoundaries(long world,VulkanDensityDevice gpu,int[] xyz,ByteBuffer xz,int bottom,int top) {
        var expected=direct(64*4);
        assertEquals(64,RustWorldgenBackend.displayDensityFloors(world,xz,expected,64));
        int[] result=gpu.dispatch(xyz,64,2,bottom,top);
        for(int i=0;i<64;i++)assertEquals(expected.getInt(i*4),result[i],"Cell boundary at "+xyz[i*3]+","+xyz[i*3+2]);
    }

    private static void verifyLifecycle(long world, int bottom, int height, boolean expectGpu) throws Exception {
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        boolean previous = config.predictionVulkanAcceleration;
        try (var compute = new VulkanPredictionCompute(world, bottom, height)) {
            var xz = direct(64 * 8); var cpu = direct(64 * 40); var out = direct(64 * 40);
            for (int i = 0; i < 64; i++) xz.putInt(i * 17 - 600).putInt(i * 29 - 1000);
            config.predictionVulkanAcceleration = false;
            assertEquals(-1, compute.preview(xz, out, 64));
            assertTrue(compute.diagnostics().contains("state=disabled"));
            config.predictionVulkanAcceleration = true;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            int written;
            do {
                written = compute.preview(xz, out, 64);
                if (written == 64 || compute.diagnostics().contains("state=cpu-faster")) break;
                assertFalse(compute.diagnostics().contains("state=fallback:"), compute.diagnostics());
                assertTrue(System.nanoTime() < deadline, compute.diagnostics());
                Thread.sleep(10);
            } while (true);
            if (expectGpu) {
                assertEquals(64, written, compute.diagnostics());
                RustWorldgenBackend.previewPoints(world, xz, cpu, 64);
                for (int i = 0; i < 640; i++) assertEquals(cpu.getInt(i * 4), out.getInt(i * 4));
                assertEquals(-1, compute.preview(xz, out, 8));
            }
            config.predictionVulkanAcceleration = false;
            assertEquals(-1, compute.preview(xz, out, 64));
            config.predictionVulkanAcceleration = true;
            if (expectGpu) assertEquals(64, compute.preview(xz, out, 64));
            System.out.println("Production Vulkan lifecycle: " + compute.diagnostics());
            compute.close();
            assertEquals(-1, compute.preview(xz, out, 64));
            assertTrue(compute.diagnostics().contains("state=closed"));
        } finally { config.predictionVulkanAcceleration = previous; }
    }

    private static void verifyMergedThroughput(long world,int bottom,int height) throws Exception {
        var config=dev.xantha.vss.config.VSSClientConfig.CONFIG;boolean previous=config.predictionVulkanAcceleration;
        var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
        try(var compute=new VulkanPredictionCompute(world,bottom,height)){
            config.predictionVulkanAcceleration=true;
            var input=direct(64*8);var out=direct(64*40);for(int i=0;i<64;i++)input.putInt(i*17-600).putInt(i*29-1000);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            while(compute.preview(input,out,64)!=64){assertFalse(compute.diagnostics().contains("state=fallback:"),compute.diagnostics());assertTrue(System.nanoTime()<deadline,compute.diagnostics());Thread.sleep(10);}
            for(int concurrency:new int[]{1,4,8})for(boolean accelerated:new boolean[]{false,true}){
                config.predictionVulkanAcceleration=accelerated;
                var start=new java.util.concurrent.CountDownLatch(1);var ready=new java.util.concurrent.CountDownLatch(concurrency);
                var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for(int lane=0;lane<concurrency;lane++){
                    final int index=lane;
                    futures.add(pool.submit(()->{
                        var xz=direct(64*8);var cpu=direct(64*40);var actual=direct(64*40);
                        for(int i=0;i<64;i++)xz.putInt(index*311+i*17-600).putInt(index*179+i*29-1000);
                        RustWorldgenBackend.previewPoints(world,xz,cpu,64);ready.countDown();start.await();
                        for(int round=0;round<12;round++){
                            int written=compute.preview(xz,actual,64);
                            if(written<0)written=RustWorldgenBackend.previewPoints(world,xz,actual,64);
                            assertEquals(64,written);
                            for(int i=0;i<640;i++)assertEquals(cpu.getInt(i*4),actual.getInt(i*4));
                        }
                        return null;
                    }));
                }
                assertTrue(ready.await(30,java.util.concurrent.TimeUnit.SECONDS));long begin=System.nanoTime();start.countDown();
                for(var future:futures)future.get(30,java.util.concurrent.TimeUnit.SECONDS);
                double elapsed=(System.nanoTime()-begin)/1e9;
                System.out.println("Vulkan throughput callers="+concurrency+" enabled="+accelerated+" columnsPerSecond="+(concurrency*12*64/elapsed)+" "+compute.diagnostics());
            }
            // Every accepted request must finish even if its world closes while queued.
            config.predictionVulkanAcceleration=true;
            var gate=new java.util.concurrent.CountDownLatch(1);var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{gate.await();compute.preview(input,direct(64*40),64);return null;}));
            gate.countDown();compute.close();for(var future:futures)future.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }finally{config.predictionVulkanAcceleration=previous;pool.shutdownNow();}
    }

    private static void verifyBatchSizes(long world,VulkanDensityDevice gpu,int bottom,int top){
        for(int size:new int[]{64,128,256,512}){
            int[] xyz=new int[size*3];for(int i=0;i<size;i++){xyz[3*i]=i*71-600;xyz[3*i+2]=i*29-8000;}
            long submitTime=0,totalTime=0;
            for(int round=0;round<6;round++){
                long start=System.nanoTime();int[] hs=gpu.dispatch(xyz,size,true,bottom,top);long submitted=System.nanoTime()-start;
                for(int from=0;from<size;from+=64){
                    var xz=direct(64*8);var heights=direct(64*4);var cpu=direct(64*40);var actual=direct(64*40);
                    for(int i=from;i<from+64;i++){xz.putInt(xyz[3*i]).putInt(xyz[3*i+2]);heights.putInt(hs[i]);}
                    assertEquals(64,RustWorldgenBackend.previewPointsGpu(world,xz,actual,64,heights));
                    if(round==0){RustWorldgenBackend.previewPoints(world,xz,cpu,64);for(int i=0;i<640;i++)assertEquals(cpu.getInt(i*4),actual.getInt(i*4));}
                }
                if(round>0){submitTime+=submitted;totalTime+=System.nanoTime()-start;}
            }
            System.out.println("Vulkan batch columns="+size+" submitMs="+submitTime/5e6+" completeMs="+totalTime/5e6);
        }
    }
}
