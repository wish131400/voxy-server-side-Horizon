package dev.xantha.vss.client.prediction;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import dev.xantha.vss.api.worldgen.DensityFunctionTraits;
import java.util.IdentityHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.densityfunction.generator.*;
import net.minecraft.world.level.levelgen.densityfunction.op.*;

/** FP32 scalar climate inputs; volume sampling retains the original compiler's path. */
final class PredictionHorizontalClimate implements AutoCloseable {
    private final PredictionClimateCache cache = new PredictionClimateCache();
    private final JsonElement[] expressions = new JsonElement[PredictionClimateCache.MAX_INPUTS];
    private final DensityFunction[] originals = new DensityFunction[PredictionClimateCache.MAX_INPUTS];
    private final IdentityHashMap<DensityFunction, Boolean> pure = new IdentityHashMap<>();
    private final IdentityHashMap<DensityFunction, DensityFunction> mapped = new IdentityHashMap<>();
    private static final java.util.Set<Class<?>> VANILLA = java.util.Set.of(DensityFunctions.class,
            ConstantFunction.class, DistanceToPointFunction.class, EndIslandFunction.class, GradientFunction.class,
            NoiseFunction.class, ShiftNoiseFunction.class, BinaryFunction.class, CacheFunction.class, ClampFunction.class,
            FindTopSurfaceFunction.class, InterpolatedFunction.class, IntervalSelectFunction.class, LerpFunction.class,
            PowFunction.class, RangeChoiceFunction.class, RoundFunction.class, SliceFunction.class, SplineFunction.class, UnaryFunction.class);
    private int remaining = 65536;
    DensityFunction[] wrap(DensityFunction... roots) {
        if ("off".equals(System.getProperty("vss.horizontalClimate"))) return roots;
        var result = roots.clone();
        for (int i = 0; i < roots.length; i++) result[i] = rewrite(roots[i]);
        mapped.clear(); pure.clear();
        return result;
    }
    private boolean pure(DensityFunction function) {
        Boolean known = pure.get(function); if (known != null) return known;
        if (--remaining < 0) return false;
        pure.put(function, false);
        var traits = DensityFunctionTraits.find(function);
        if (traits != null) {
            boolean safe = traits.coordinatePure() && !traits.ownsRuntimeContext();
            pure.put(function, safe); return safe;
        }
        if (function instanceof DensityFunctions.HolderHolder holder) {
            boolean safe = pure(holder.function().value()); pure.put(function, safe); return safe;
        }
        if (!VANILLA.contains(function.getClass().getNestHost())) return false;
        try {
            var id = BuiltInRegistries.DENSITY_FUNCTION_TYPE.getKey(function.codec());
            if (id != null && (id.getPath().equals("beardifier") || id.getPath().startsWith("blend_"))) return false;
            boolean[] safe = {true};
            function.rewriteChildren(child -> { safe[0] &= pure(child); return child; });
            pure.put(function, safe[0]); return safe[0];
        } catch (RuntimeException unsupported) { return false; }
    }
    private DensityFunction rewrite(DensityFunction function) {
        DensityFunction known = mapped.get(function); if (known != null) return known;
        if (!pure(function)) return function;
        DensityFunction result;
        if ((function.domainAxes() & DensityFunction.AXIS_Y) == 0) {
            if (function.domainAxes() == DensityFunction.NO_AXES) return function;
            int lane = cache.reserve(); if (lane < 0) return function;
            originals[lane] = function;
            result = new Cut(function, lane, cache);
        } else {
            try { result = function.rewriteChildren(this::rewrite); }
            catch (RuntimeException unsupported) { return function; }
        }
        mapped.put(function, result); return result;
    }
    private record Cut(DensityFunction original, int lane, PredictionClimateCache cache) implements DensityFunction {
        @Override public DensitySampler compileSampler(CompileContext context) {
            DensitySampler delegate = original.compileSampler(context);
            return new DensitySampler() {
                @Override public float sampleValue(SamplerContext context, int x, int y, int z) {
                    return (float) cache.get(lane, x, z, () -> delegate.sampleValue(context, x, y, z));
                }
                @Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
                    delegate.sampleVolume(context, output, volume);
                }
            };
        }
        @Override public DensityFunction rewriteChildren(DfRewriteRule rule) {
            var rewritten = rule.rewrite(original);
            return rewritten == original ? this : new Cut(rewritten, lane, cache);
        }
        @Override public Interval range() { return original.range(); }
        @Override public int domainAxes() { return original.domainAxes(); }
        @Override public MapCodec<? extends DensityFunction> codec() { throw new UnsupportedOperationException("Runtime climate input cache"); }
    }
    void bind(long world, net.minecraft.world.level.levelgen.RandomState random, com.google.gson.JsonObject document, long seed) {
        var definitions = document.getAsJsonObject("noises");
        if (definitions == null) return;
        for (int lane = 0; lane < cache.inputCount(); lane++) {
            var noises = new java.util.HashMap<JsonElement, JsonElement>();
            boolean[] valid = {true};
            try {
                DfRewriteRule inline = new DfRewriteRule() {
                    @Override public DensityFunction rewrite(DensityFunction node) {
                        if (!VANILLA.contains(node.getClass().getNestHost())) valid[0] = false;
                        net.minecraft.core.Holder<net.minecraft.world.level.levelgen.synth.NormalNoise> holder =
                                node instanceof NoiseFunction noise ? noise.noise()
                                : node instanceof ShiftNoiseFunction shift ? shift.offsetNoise() : null;
                        if (holder != null) {
                            var key = holder.unwrapKey().orElse(null);
                            var parameters = net.minecraft.world.level.levelgen.synth.NormalNoise.DIRECT_CODEC
                                    .encodeStart(JsonOps.INSTANCE,holder.value()).getOrThrow();
                            String name = key == null ? null : key.identifier().toString();
                            if (name == null || !definitions.has(name) || !definitions.get(name).equals(parameters)) valid[0] = false;
                            else {
                                var identity = new com.google.gson.JsonPrimitive(name);
                                var previous = noises.putIfAbsent(parameters,identity);
                                if (previous != null && !previous.equals(identity)) valid[0] = false;
                            }
                        }
                        var value = node.rewriteChildren(this);
                        return value instanceof DensityFunctions.HolderHolder reference ? reference.function().value() : value;
                    }
                };
                var value = inline.rewrite(originals[lane]);
                expressions[lane] = valid[0] ? PredictionClimateIdentity.normalize(
                        DensityFunctions.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE,value).getOrThrow(),noises,0) : null;
            } catch (RuntimeException unsupported) { expressions[lane] = null; }
        }
        cache.bind(world, expressions, seed, true);
    }
    void detach() { cache.detach(); }
    String diagnostics() { return cache.diagnostics(); }
    @Override public void close() { cache.close(); }
}
