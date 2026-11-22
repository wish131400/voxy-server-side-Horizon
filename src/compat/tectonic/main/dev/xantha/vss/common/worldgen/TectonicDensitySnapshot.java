package dev.xantha.vss.common.worldgen;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

final class TectonicDensitySnapshot {
    private TectonicDensitySnapshot() { }

    private static final String PACKAGE = "dev.worldgen.tectonic.worldgen.densityfunction.";

    static DensityFunction capture(DensityFunction function) {
        String name = function.getClass().getName();
        try {
            if (name.equals(PACKAGE + "ConfigConstant")) {
                return DensityFunctions.constant((double) function.getClass().getMethod("value").invoke(function));
            }
            if (name.equals(PACKAGE + "ConfigNoise")) {
                var type = function.getClass();
                var noise = (DensityFunction.NoiseHolder) type.getMethod("noise").invoke(function);
                var shiftX = (DensityFunction) type.getMethod("shiftX").invoke(function);
                var shiftZ = (DensityFunction) type.getMethod("shiftZ").invoke(function);
                double scale = (double) type.getMethod("scale").invoke(function);
                double multiplier = (double) type.getMethod("multiplier").invoke(function);
                double offset = (double) type.getMethod("offset").invoke(function);
                boolean smoother = false;
                try { smoother = (boolean) type.getMethod("smootherScaling").invoke(function); }
                catch (NoSuchMethodException legacyRecord) { /* The six-field record has no alternate scaling. */ }
                if (smoother) {
                    // (x + shift) * scale cannot be reassociated into vanilla's
                    // x * scale + shift without changing both terrain and rounding.
                    return new TectonicConfigNoise(noise, shiftX, shiftZ, scale, multiplier, offset, true);
                }
                var shifted = DensityFunctions.shiftedNoise2d(shiftX, shiftZ, scale, noise.noiseData())
                        .mapAll(new DensityFunction.Visitor() {
                            public DensityFunction apply(DensityFunction value) { return value; }
                            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder value) {
                                return value.noiseData() == noise.noiseData() && value.noise() == null ? noise : value;
                            }
                        });
                return DensityFunctions.add(DensityFunctions.mul(shifted, DensityFunctions.constant(multiplier)),
                        DensityFunctions.constant(offset));
            }
            return function;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot capture applied Tectonic density function " + name, failure);
        }
    }
}
