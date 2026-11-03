package dev.xantha.vss.client.prediction;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Selects backends from complete, consumed cold regions. GPU selection applies
 * only to this same request shape; smaller/warm requests always keep Rust. */
final class VulkanDisplayPolicy {
    private static final int SAMPLES = 3;
    static final int MIN_COLD_COLUMNS = 192;
    private static final int CPU_SENTINEL_REGIONS = 8;
    private static final int CPU_RECHECK_REGIONS = 2048;
    private final Lane[] lanes = new Lane[31];

    record Trial(int spacing, boolean gpu, boolean measured, boolean exclusive, Lane lane) {}
    private static final class Lane {
        final double[] cpu = new double[SAMPLES], gpu = new double[SAMPLES];
        int cpuSamples, gpuSamples, cpuCursor, gpuCursor, regions, gpuSinceCpu;
        long cpuColumns, gpuColumns, ignored, checks, gpuFallbacks, onlineFallbacks, cpuSentinels;
        boolean useGpu, inFlight;
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
        if (!audition && !lane.useGpu && ++lane.regions >= CPU_RECHECK_REGIONS) {
            lane.cpuSamples = lane.gpuSamples = lane.cpuCursor = lane.gpuCursor = lane.regions = 0;
            lane.reason = "rechecking-cold-regions";
            audition = true;
        }
        if (audition) {
            if (lane.inFlight) return new Trial(spacing,false,false,false,lane);
            lane.inFlight = true;
            return new Trial(spacing,lane.cpuSamples > lane.gpuSamples,true,true,lane);
        }
        if (!lane.useGpu) return new Trial(spacing,false,false,false,lane);
        if (lane.gpuSinceCpu >= CPU_SENTINEL_REGIONS && !lane.inFlight) {
            lane.inFlight = true;
            return new Trial(spacing,false,true,true,lane);
        }
        // Every consumed cold GPU region participates in the rolling check.
        return new Trial(spacing,true,true,false,lane);
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
            boolean faster = lane.gpuCost < lane.cpuCost * 0.8;
            if (audition) {
                lane.useGpu = faster;
                lane.reason = faster ? "gpu-region-faster" : "cpu-region-faster";
                lane.gpuSinceCpu = 0;
                lane.checks++;
            } else if (lane.useGpu && !faster) {
                lane.useGpu = false;
                lane.reason = "cpu-faster-recent-regions";
                lane.regions = 0;
                lane.onlineFallbacks++;
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
            if (lanes[index] != null && lanes[index].useGpu) return 1 << index;
        return Integer.MAX_VALUE;
    }
    synchronized Map<String,Object> snapshot() {
        var result = new LinkedHashMap<String,Object>();
        for (int index = 0; index < lanes.length; index++) {
            Lane lane = lanes[index];
            if (lane == null) continue;
            result.put(Integer.toString(1 << index), Map.ofEntries(
                    Map.entry("backend",lane.useGpu ? "gpu" : "cpu"),
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
