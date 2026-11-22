package dev.xantha.vss.common.worldgen;

import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;

final class TectonicDensitySnapshot {
    private TectonicDensitySnapshot() { }

    static DensityFunction capture(DensityFunction function, java.util.function.UnaryOperator<DensityFunction> rewrite) {
        String name = function.getClass().getName();
        String tectonic = "dev.worldgen.tectonic.worldgen.densityfunction.";
        try {
            if (name.equals(tectonic + "ConfigConstant")) {
                return DensityFunctions.constant((float) function.getClass().getMethod("value").invoke(function));
            }
            if (name.equals(tectonic + "ConfigNoise")) {
                var type = function.getClass();
                @SuppressWarnings("unchecked")
                var noise = (net.minecraft.core.Holder<net.minecraft.world.level.levelgen.synth.NormalNoise>)
                        type.getMethod("noise").invoke(function);
                return new TectonicConfigNoise(noise,
                        rewrite.apply((DensityFunction) type.getMethod("shiftX").invoke(function)),
                        rewrite.apply((DensityFunction) type.getMethod("shiftZ").invoke(function)),
                        (float) type.getMethod("scale").invoke(function),
                        (float) type.getMethod("multiplier").invoke(function),
                        (float) type.getMethod("offset").invoke(function),
                        (boolean) type.getMethod("smootherScaling").invoke(function));
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot capture applied Tectonic density function " + name, failure);
        }
        return function;
    }
}
