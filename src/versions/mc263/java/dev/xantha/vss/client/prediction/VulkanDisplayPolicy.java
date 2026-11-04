package dev.xantha.vss.client.prediction;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Selects backends from complete, consumed cold regions. GPU selection applies
 * only to this same request shape; smaller/warm requests always keep Rust.
 * A bounded GPU share allows both processors to contribute. Per-request latency
 * need not beat an idle CPU by 20% to add throughput under CPU saturation. */
final class VulkanDisplayPolicy {
    private static final int SAMPLES = 3;
    static final int MIN_COLD_COLUMNS = 192;
    private static final int CPU_SENTINEL_REGIONS = 8;
    static final int CPU_RECHECK_REGIONS = 64;
    private final Lane[] lanes = new Lane[31];

    record Trial(int spacing, boolean gpu, boolean measured, boolean exclusive, Lane lane) {}
    private static final class Lane {
        final double[] cpu = new double[SAMPLES], gpu = new double[SAMPLES];
        int cpuSamples, gpuSamples, cpuCursor, gpuCursor, regions, gpuSinceCpu, turn, gpuQuota;
        long cpuColumns, gpuColumns, ignored, checks, gpuFallbacks, onlineFallbacks, cpuSentinels;
        boolean inFlight;
        String reason = "collecting-cold-regions";
        double cpuCost, gpuCost;
    }
    private static int index(int spacing) {
        return spacing > 0 && (spacing & (spacing - 1)) == 0
                ? Integer.numberOfTrailingZeros(spacing) : -1;
    }
    synchronized Trial begin(int spacing, boolean fullRegion) {
        int index = index(spacing);
        if (index < 0) return new Trial(spacing,false,false,false,null);
        Lane lane = lanes[index];
        if (lane == null) lanes[index] = lane = new Lane();
        if (!fullRegion) return new Trial(spacing,false,false,false,lane);
        boolean audition = lane.cpuSamples < SAMPLES || lane.gpuSamples < SAMPLES;
        int recheck = lane.gpuCost > lane.cpuCost * 4 ? 512
                : lane.gpuCost > lane.cpuCost * 2 ? 256 : CPU_RECHECK_REGIONS;
        if (!audition && lane.gpuQuota == 0 && ++lane.regions >= recheck) {
            lane.cpuSamples = lane.gpuSamples = lane.cpuCursor = lane.gpuCursor = lane.regions = 0;
            lane.reason = "rechecking-cold-regions";
            audition = true;
        }
        if (audition) {
            if (lane.inFlight) return new Trial(spacing,false,false,false,lane);
            lane.inFlight = true;
            return new Trial(spacing,lane.cpuSamples > lane.gpuSamples,true,true,lane);
        }
        if (lane.gpuQuota == 0) return new Trial(spacing,false,false,false,lane);
        if (lane.gpuSinceCpu >= CPU_SENTINEL_REGIONS && !lane.inFlight) {
            lane.inFlight = true;
            return new Trial(spacing,false,true,true,lane);
        }
        // Keep at least half of full regions on Rust. The GPU queue has its own
        // bounded admission too, so a busy device never blocks every builder.
        boolean gpu = (lane.turn++ & 7) < lane.gpuQuota;
        return new Trial(spacing,gpu,gpu,false,lane);
    }
    synchronized void finish(Trial trial, long elapsed, int coldColumns,
                             boolean attemptedGpu, boolean gpuResult, boolean complete) {
        if (!trial.measured) return;
        Lane lane = trial.lane;
        if (trial.exclusive) lane.inFlight = false;
        if (!complete || elapsed <= 0 || coldColumns < MIN_COLD_COLUMNS
                || (trial.gpu && !attemptedGpu)) {
            lane.ignored++;
            return;
        }
        boolean audition = lane.cpuSamples < SAMPLES || lane.gpuSamples < SAMPLES;
        double cost = (double) elapsed / coldColumns;
        if (trial.gpu) {
            lane.gpu[lane.gpuCursor] = cost;
            lane.gpuCursor = (lane.gpuCursor+1) % SAMPLES;
            lane.gpuSamples = Math.min(SAMPLES,lane.gpuSamples+1);
            lane.gpuColumns += coldColumns;
            lane.gpuSinceCpu++;
            if (!gpuResult) lane.gpuFallbacks++;
        } else {
            lane.cpu[lane.cpuCursor] = cost;
            lane.cpuCursor = (lane.cpuCursor+1) % SAMPLES;
            lane.cpuSamples = Math.min(SAMPLES,lane.cpuSamples+1);
            lane.cpuColumns += coldColumns;
            lane.gpuSinceCpu = 0;
            if (!audition) lane.cpuSentinels++;
        }
        if (lane.cpuSamples == SAMPLES && lane.gpuSamples == SAMPLES) {
            lane.cpuCost = median(lane.cpu);
            lane.gpuCost = median(lane.gpu);
            // Very cheap adaptive CPU regions should stay there. Comparable
            // GPU regions can still add capacity beside several CPU builders.
            int quota = lane.gpuCost <= lane.cpuCost * 0.9 ? 4
                    : lane.gpuCost <= lane.cpuCost * 1.25 ? 2 : 0;
            if (audition) {
                lane.gpuQuota = quota;
                lane.reason = quota > 0 ? "cpu-gpu-shared-regions" : "cpu-region-faster";
                lane.gpuSinceCpu = lane.turn = 0;
                lane.checks++;
            } else if (lane.gpuQuota > 0) {
                lane.gpuQuota = quota;
                if (quota == 0) {
                    lane.reason = "cpu-faster-recent-regions";
                    lane.regions = 0;
                    lane.onlineFallbacks++;
                }
            }
        }
    }
    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
    synchronized int minimumGpuSpacing() {
        for (int index = 0; index < lanes.length; index++)
            if (lanes[index] != null && lanes[index].gpuQuota > 0) return 1 << index;
        return Integer.MAX_VALUE;
    }
    synchronized Map<String,Object> snapshot() {
        var result = new LinkedHashMap<String,Object>();
        for (int index = 0; index < lanes.length; index++) {
            Lane lane = lanes[index];
            if (lane == null) continue;
            result.put(Integer.toString(1 << index), Map.ofEntries(
                    Map.entry("backend",lane.gpuQuota > 0 ? "hybrid" : "cpu"),
                    Map.entry("gpuRegionShare",lane.gpuQuota / 8.0),
                    Map.entry("reason",lane.reason), Map.entry("cpuSamples",lane.cpuSamples),
                    Map.entry("gpuSamples",lane.gpuSamples), Map.entry("cpuColdColumns",lane.cpuColumns),
                    Map.entry("gpuColdColumns",lane.gpuColumns), Map.entry("cpuNanosPerColumn",lane.cpuCost),
                    Map.entry("gpuNanosPerColumn",lane.gpuCost), Map.entry("ignoredRegions",lane.ignored),
                    Map.entry("gpuFallbackRegions",lane.gpuFallbacks), Map.entry("checks",lane.checks),
                    Map.entry("minimumColdColumns",MIN_COLD_COLUMNS),
                    Map.entry("cpuSentinels",lane.cpuSentinels), Map.entry("onlineFallbacks",lane.onlineFallbacks)));
        }
        return result;
    }
}
