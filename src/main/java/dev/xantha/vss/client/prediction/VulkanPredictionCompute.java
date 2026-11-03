package dev.xantha.vss.client.prediction;

/** Version boundary: older density graphs retain their double-precision CPU path. */
public final class VulkanPredictionCompute {
    private VulkanPredictionCompute() { }
    public static boolean supported() { return false; }
}
