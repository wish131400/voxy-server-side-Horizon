package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VulkanDisplayTest {
    private static ByteBuffer direct(int n) {return ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN);}
    @Test void productionDisplayRegionsKeepAdaptiveResultsAndMeasureCombinedThroughput() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("vss.testVulkan"));
        RustWorldgenBackend.load(Path.of(System.getProperty("vss.testNativeLibrary")));
        dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap();
        var doc=ModernNativeDocuments.overworld();
        var names=List.copyOf(doc.getAsJsonObject("biomes").keySet());
        var pool=Executors.newFixedThreadPool(8);
        boolean previous=dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionVulkanAcceleration;
        try {
            for(int step:new int[]{4,16}) {
                ClientColumnSample[][] reference=null;
                for(boolean accelerated:new boolean[]{false,true}) {
                    dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionVulkanAcceleration=accelerated;
                    long world=RustWorldgenBackend.create(1,0,doc.toString());
                    var context=new ClientTerrainSampler(1,RustBiomeIdentityTest.PROFILE) {
                        @Override List<String> cacheBiomes(){return names;}
                    };
                    try(var sampler=new RustTerrainSampler(world,RustBiomeIdentityTest.PROFILE,context)) {
                        sampler.sampleDisplayRegion(-511,-733,16,16,new ClientColumnSample[256],()->true);
                        if(accelerated) {
                            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
                            while(!((Map<?,?>)sampler.displayPerformanceSnapshot().get("vulkan")).get("state").toString().startsWith("ready:")) {
                                assertFalse(sampler.diagnostics().contains("fallback:"),sampler.diagnostics());
                                assertTrue(System.nanoTime()<deadline,sampler.diagnostics());Thread.sleep(10);
                            }
                        }
                        var start=new CountDownLatch(1);var next=new java.util.concurrent.atomic.AtomicInteger();
                        var futures=new ArrayList<Future<?>>();
                        int regions=96;var actual=new ClientColumnSample[regions][];
                        for(int lane=0;lane<8;lane++)futures.add(pool.submit(()->{
                            start.await();
                            for(int round;(round=next.getAndIncrement())<regions;) {
                                var samples=new ClientColumnSample[256];
                                sampler.sampleDisplayRegion(100000+round*4096,-200000,step,16,samples,()->true);
                                assertTrue(Arrays.stream(samples).allMatch(Objects::nonNull));
                                actual[round]=samples;
                            }
                            return null;
                        }));
                        long begin=System.nanoTime();start.countDown();
                        for(var f:futures)f.get(2,TimeUnit.MINUTES);
                        double seconds=(System.nanoTime()-begin)/1e9;
                        if(reference==null)reference=actual;
                        else {
                            for(int region=0;region<regions;region++)assertArrayEquals(reference[region],actual[region],"complete production region "+region);
                            var vulkan=(Map<?,?>)sampler.displayPerformanceSnapshot().get("vulkan");
                            var lane=(Map<?,?>)((Map<?,?>)vulkan.get("displayRegionPolicy")).get(Integer.toString(step));
                            assertNotNull(lane,"the production sampler must enter complete-region admission");
                            assertTrue(((Number)lane.get("checks")).longValue()>0,"cold complete regions must finish admission: "+lane);
                            boolean comparable=((Number)lane.get("gpuNanosPerColumn")).doubleValue()<=((Number)lane.get("cpuNanosPerColumn")).doubleValue()*1.25;
                            if(lane.get("backend").equals("hybrid"))assertTrue(comparable,"shared work needs recent full-region evidence: "+lane);
                            else assertTrue(Set.of("cpu-region-faster","cpu-faster-recent-regions").contains(lane.get("reason").toString()),lane.toString());
                            assertTrue(((Number)vulkan.get("displayColumns")).longValue()>0,"GPU audition results must be consumed, even when CPU wins");
                        }
                        System.out.println("Production display region step="+step+" gpu="+accelerated
                                +" columnsPerSecond="+regions*256/seconds+" "+sampler.displayPerformanceSnapshot());
                    }
                }
            }
        } finally {pool.shutdownNow();dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionVulkanAcceleration=previous;}
    }
    @Test void thinDetachedLayersSparseGridsAndColdDisplayThroughput() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("vss.testVulkan"));
        RustWorldgenBackend.load(Path.of(System.getProperty("vss.testNativeLibrary")));
        dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap();
        var thin=ModernNativeDocuments.overworld();
        thin.getAsJsonObject("settings").getAsJsonObject("noise_router").add("final_density",JsonParser.parseString("{\"type\":\"minecraft:range_choice\",\"input\":{\"type\":\"minecraft:gradient\",\"axis\":\"y\",\"from_coordinate\":-64,\"to_coordinate\":320,\"from_value\":-64,\"to_value\":320},\"min_inclusive\":307,\"max_exclusive\":308,\"when_in_range\":1,\"when_out_of_range\":-1}"));
        long cpu=RustWorldgenBackend.create(42,0,thin.toString()),gpuWorld=RustWorldgenBackend.create(42,0,thin.toString());
        try(var gpu=new VulkanDensityDevice(JsonParser.parseString(RustWorldgenBackend.vulkanProgram(cpu)).getAsJsonObject())) {
            for(int step:new int[]{1,2,4}) {
                int[] xyz=new int[64*3];var xz=direct(64*8);var hs=direct(64*4);var expected=direct(64*40);var actual=direct(64*40);
                int x=-571-step*97,z=-283;
                for(int i=0;i<64;i++){xyz[i*3]=x+i%8*step;xyz[i*3+2]=z+i/8*step;xz.putInt(xyz[i*3]).putInt(xyz[i*3+2]);}
                int[] heights=gpu.dispatch(xyz,64,2,-64,319);
                for(int h:heights){assertEquals(308,h);hs.putInt(h);}
                // A per-column invalid result retains the original CPU policy.
                hs.putInt(13*4,Integer.MIN_VALUE);
                long mask=0x7ff7ffffefffffffL;
                assertEquals(64,RustWorldgenBackend.displayGrid(cpu,x,z,step,mask,expected));
                assertEquals(64,RustWorldgenBackend.displayGridGpu(gpuWorld,x,z,step,mask,actual,hs));
                for(int i=0;i<64;i++)if((mask&(1L<<i))!=0)for(int f=0;f<10;f++)assertEquals(expected.getInt(i*40+f*4),actual.getInt(i*40+f*4));
                // A regular adaptive grid keeps only its probes in the point
                // cache. Use a scattered batch to verify the warm-point mask.
                xz.clear();
                for(int i=0;i<64;i++)xz.putInt(x+i*53).putInt(z-i*79);
                RustWorldgenBackend.displayPointsGpu(gpuWorld,xz,actual,64,hs);
                assertEquals(0,RustWorldgenBackend.displayGpuMask(gpuWorld,xz,64));
            }
        } finally {RustWorldgenBackend.close(cpu);RustWorldgenBackend.close(gpuWorld);}
        benchmark();
    }
    private static void benchmark() throws Exception {
        var doc=ModernNativeDocuments.overworld();
        boolean previous=dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionVulkanAcceleration;
        var pool=Executors.newFixedThreadPool(8);
        try {
            dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionVulkanAcceleration=true;
            for(int spacing:new int[]{1,4,16})for(int lanes:new int[]{1,8})for(boolean accelerated:new boolean[]{false,true}) {
                long world=RustWorldgenBackend.create(42,0,doc.toString());
                try(var compute=new VulkanPredictionCompute(world,-64,384)) {
                    int[] warm=new int[128];for(int i=0;i<64;i++){warm[i*2]=i*83-573;warm[i*2+1]=i*211-581;}
                    if(accelerated) {
                        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
                        while(compute.displayHeights(warm)==null) {
                            assertFalse(compute.diagnostics().contains("fallback:"),compute.diagnostics());
                            assertFalse(compute.diagnostics().contains("cpu-faster"),compute.diagnostics());
                            assertTrue(System.nanoTime()<deadline,compute.diagnostics());Thread.sleep(10);
                        }
                    }
                    var start=new CountDownLatch(1);var ready=new CountDownLatch(lanes);var futures=new ArrayList<Future<?>>();
                    var next=new java.util.concurrent.atomic.AtomicInteger();
                    int regions=lanes*16;
                    for(int lane=0;lane<lanes;lane++) {
                        futures.add(pool.submit(()->{
                            ready.countDown();start.await();
                            for(int round;(round=next.getAndIncrement())<regions;) {
                                int[] xz=new int[512];
                                for(int i=0;i<256;i++){xz[i*2]=100000+round*4096+i%16*spacing;xz[i*2+1]=-200000+i/16*spacing;}
                                int[] heights=accelerated?compute.displayHeights(xz,spacing):null;
                                for(int batch=0;batch<4;batch++) {
                                    var positions=direct(64*8);var hs=direct(64*4);var output=direct(64*40);
                                    for(int i=0;i<64;i++){int offset=batch*64+i;positions.putInt(xz[offset*2]).putInt(xz[offset*2+1]);if(heights!=null)hs.putInt(heights[offset]);}
                                    int n=heights==null?RustWorldgenBackend.displayPoints(world,positions,output,64):RustWorldgenBackend.displayPointsGpu(world,positions,output,64,hs);
                                    assertEquals(64,n);
                                }
                            }
                            return null;
                        }));
                    }
                    assertTrue(ready.await(30,TimeUnit.SECONDS));long begin=System.nanoTime();start.countDown();
                    for(var future:futures)future.get(2,TimeUnit.MINUTES);
                    double seconds=(System.nanoTime()-begin)/1e9;
                    System.out.println("Display cold step="+spacing+" lanes="+lanes+" gpu="+accelerated+" columnsPerSecond="+(regions*256/seconds)+" "+compute.diagnostics());
                } finally {RustWorldgenBackend.close(world);}
            }
        } finally {pool.shutdownNow();dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionVulkanAcceleration=previous;}
    }
}
