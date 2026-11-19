package dev.xantha.vss.client.prediction;

import dev.xantha.vss.api.worldgen.DensityFunctionReplacements;
import dev.xantha.vss.api.worldgen.DensityFunctionTraits;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import java.util.IdentityHashMap;

/** FP32 graphs keep the Minecraft compiler and its per-worker SamplerContext. */
final class PredictionDensityReplacements {
    static DensityFunction apply(DensityFunctionReplacements.Context context, DensityFunction root) {
        if (DensityFunctionReplacements.empty()) return root;
        try { return new Rewrite(context).visit(root); }
        catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException failure) {
            dev.xantha.vss.common.VSSLogger.warn("VSS density optimization rejected; retaining original FP32 functions", failure);
            return root;
        }
    }
    private static final class Rewrite {
        private final DensityFunctionReplacements.Context context;
        private final IdentityHashMap<DensityFunction,DensityFunction> completed = new IdentityHashMap<>();
        private int depth;
        Rewrite(DensityFunctionReplacements.Context context) { this.context=context; }
        DensityFunction visit(DensityFunction node) {
            var cached=completed.get(node); if(cached!=null)return cached;
            var traits=DensityFunctionTraits.find(node);
            if(traits!=null && traits.ownsRuntimeContext())return node;
            if(++depth>512 || completed.size()>65536)throw new IllegalArgumentException("Density replacement graph budget");
            try {
                var result=DensityFunctionReplacements.apply(node.rewriteChildren(this::visit),context);
                completed.put(node,result);return result;
            } finally {depth--;}
        }
    }
    private PredictionDensityReplacements() { }
}
