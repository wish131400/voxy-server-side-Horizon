package dev.xantha.vss.client.prediction;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/** Stable vanilla node identities in both Mojmap development and Fabric intermediary runtime. */
final class PredictionVanillaDensity {
    private PredictionVanillaDensity() { }

    static boolean vanilla(Class<?> type) { return type.getNestHost() == DensityFunctions.class; }
    static boolean spline(Class<?> type) { return type.getNestHost() == CubicSpline.class && type.isRecord(); }

    static String kind(Object value) {
        Class<?> type = value.getClass();
        if (!vanilla(type)) return "opaque";
        if (value instanceof DensityFunctions.HolderHolder) return "HolderHolder";
        if (!(value instanceof DensityFunction function)) return "Coordinate";
        var id = BuiltInRegistries.DENSITY_FUNCTION_TYPE.getKey(function.codec().codec());
        if (id == null || !id.getNamespace().equals("minecraft")) return "opaque";
        return switch (id.getPath()) {
            case "constant" -> "Constant";
            case "add", "mul" -> type.isRecord() && type.getRecordComponents().length == 5
                    && type.getRecordComponents()[2].getType() == double.class ? "MulOrAdd" : "Ap2";
            case "min", "max" -> "Ap2";
            case "abs", "square", "cube", "half_negative", "quarter_negative", "squeeze", "invert" -> "Mapped";
            case "clamp" -> "Clamp";
            case "range_choice" -> "RangeChoice";
            case "interval_select" -> "IntervalSelect";
            case "interpolated", "flat_cache", "cache_2d", "cache_once", "cache_all_in_cell" -> "Marker";
            case "spline" -> "Spline";
            case "y_clamped_gradient" -> "YClampedGradient";
            case "noise" -> "Noise";
            case "shifted_noise" -> "ShiftedNoise";
            case "shift" -> "Shift";
            case "shift_a" -> "ShiftA";
            case "shift_b" -> "ShiftB";
            case "weird_scaled_sampler" -> "WeirdScaledSampler";
            case "blend_density" -> "BlendDensity";
            case "blend_alpha" -> "BlendAlpha";
            case "blend_offset" -> "BlendOffset";
            case "beardifier" -> "BeardifierMarker";
            case "end_islands" -> "EndIslandDensityFunction";
            case "find_top_surface" -> "FindTopSurface";
            default -> "opaque";
        };
    }
}
