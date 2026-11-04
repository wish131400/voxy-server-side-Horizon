package dev.xantha.vss.client.prediction;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VulkanDisplayPolicyTest {
    @SuppressWarnings("unchecked")
    private static Map<String,Object> lane(VulkanDisplayPolicy policy,int spacing) {
        return (Map<String,Object>)policy.snapshot().get(Integer.toString(spacing));
    }
    private static void audition(VulkanDisplayPolicy policy,int spacing,long cpu,long gpu) {
        for(int i=0;i<6;i++) {
            var trial=policy.begin(spacing,true);
            assertTrue(trial.measured());
            assertEquals((i&1)!=0,trial.gpu());
            policy.finish(trial,trial.gpu()?gpu:cpu,256,trial.gpu(),trial.gpu(),true);
        }
    }
    @Test void fullRegionCostRejectsSlowGpuAndKeepsProfitableSpacing() {
        var policy=new VulkanDisplayPolicy();
        audition(policy,4,230_000,390_000);
        audition(policy,16,800_000,300_000);
        assertFalse(policy.begin(4,true).gpu());
        assertTrue(policy.begin(16,true).gpu());
        assertFalse(policy.begin(4,true).measured());
        assertEquals("cpu-region-faster",lane(policy,4).get("reason"));
        assertEquals("cpu-gpu-shared-regions",lane(policy,16).get("reason"));
        assertEquals(768L,lane(policy,4).get("cpuColdColumns"));
        assertEquals(768L,lane(policy,4).get("gpuColdColumns"));
    }
    @Test void warmCancelledPartialAndDeclinedWorkCannotSelectBackend() {
        var policy=new VulkanDisplayPolicy();
        assertFalse(policy.begin(4,false).measured());
        var cancelled=policy.begin(4,true);
        assertFalse(policy.begin(4,true).measured(),"one audition owns this spacing at a time");
        policy.finish(cancelled,10,256,false,false,false);
        var warm=policy.begin(4,true);
        policy.finish(warm,10,64,false,false,true);
        var cpu=policy.begin(4,true);
        policy.finish(cpu,256_000,256,false,false,true);
        var declined=policy.begin(4,true);
        assertTrue(declined.gpu());
        policy.finish(declined,10,256,false,false,true);
        assertEquals(1,lane(policy,4).get("cpuSamples"));
        assertEquals(0,lane(policy,4).get("gpuSamples"));
        assertEquals(3L,lane(policy,4).get("ignoredRegions"));
        var fallback=policy.begin(4,true);
        policy.finish(fallback,512_000,256,true,false,true);
        assertEquals(1,lane(policy,4).get("gpuSamples"));
        assertEquals(1L,lane(policy,4).get("gpuFallbackRegions"));
    }
    @Test void recheckIsBoundedAndNeverDuplicatesACompletedRegion() {
        var policy=new VulkanDisplayPolicy();
        audition(policy,4,100_000,200_000);
        for(int i=0;i<VulkanDisplayPolicy.CPU_RECHECK_REGIONS-1;i++) {
            var normal=policy.begin(4,true);
            assertFalse(normal.measured());
            assertFalse(normal.gpu());
        }
        var sentinel=policy.begin(4,true);
        assertTrue(sentinel.measured());
        policy.finish(sentinel,100_000,256,false,false,true);
        assertTrue(policy.begin(4,true).gpu());
        assertEquals("rechecking-cold-regions",lane(policy,4).get("reason"));
    }
    @Test void partialRegionsCannotUseAProfitableFullRegionDecision() {
        var policy=new VulkanDisplayPolicy();
        audition(policy,4,400_000,100_000);
        assertTrue(policy.begin(4,true).gpu());
        var partial=policy.begin(4,false);
        assertFalse(partial.gpu());
        assertFalse(partial.measured());
    }
    @Test void recentSlowGpuRegionsRetreatWithoutWaitingForRareRecheck() {
        var policy=new VulkanDisplayPolicy();
        audition(policy,4,400_000,100_000);
        for(int i=0;i<2;i++) {
            var region=policy.begin(4,true);
            assertTrue(region.gpu());
            assertTrue(region.measured());
            policy.finish(region,800_000,256,true,true,true);
        }
        assertFalse(policy.begin(4,true).gpu());
        assertEquals("cpu-faster-recent-regions",lane(policy,4).get("reason"));
        assertEquals(1L,lane(policy,4).get("onlineFallbacks"));
        assertEquals(800_000.0/256,(double)lane(policy,4).get("gpuNanosPerColumn"));
    }
    @Test void gpuUseKeepsRefreshingCpuWithConsumedSentinelRegions() {
        var policy=new VulkanDisplayPolicy();
        audition(policy,4,800_000,100_000);
        for(int sentinel=0;sentinel<2;sentinel++) {
            int consumed = 0;
            for(int request=0;request<64;request++) {
                var region=policy.begin(4,true);
                if (region.measured() && !region.gpu()) {
                    assertEquals(8, consumed);
                    policy.finish(region,50_000,256,false,false,true);
                    break;
                }
                if (region.gpu()) consumed++;
                policy.finish(region,region.gpu()?100_000:800_000,256,region.gpu(),region.gpu(),true);
            }
        }
        assertEquals(2L,lane(policy,4).get("cpuSentinels"));
        assertFalse(policy.begin(4,true).gpu(),"faster recent CPU regions must stop GPU use");
    }
    @Test void comparableGpuSharesWorkWithoutDisplacingCheapAdaptiveCpuRegions() {
        var policy=new VulkanDisplayPolicy();
        audition(policy,16,1_000_000,920_000); // A real win previously rejected by the 20% margin.
        int gpu=0,cpu=0;
        for(int i=0;i<8;i++) {
            var region=policy.begin(16,true);
            if(region.gpu())gpu++;else cpu++;
            policy.finish(region,region.gpu()?920_000:1_000_000,256,region.gpu(),region.gpu(),true);
        }
        assertEquals(2,gpu);assertEquals(6,cpu);
        assertEquals(0.25,lane(policy,16).get("gpuRegionShare"));
        audition(policy,1,100_000,1_100_000);
        assertFalse(policy.begin(1,true).gpu());
        assertEquals(16,policy.minimumGpuSpacing());
    }

}
