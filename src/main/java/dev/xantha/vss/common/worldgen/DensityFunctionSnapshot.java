package dev.xantha.vss.common.worldgen;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** Captures the applied graph rather than a modifier's replay/source codec. */
public final class DensityFunctionSnapshot {
    private static final DensityFunction.Visitor UNWRAP = function -> {
        while (function instanceof DensityFunctions.HolderHolder holder) {
            function = holder.function().value();
        }
        return TectonicDensitySnapshot.capture(function);
    };

    private DensityFunctionSnapshot() { }

    public static DensityFunction applied(DensityFunction function) {
        // mapAll visits the effective graph of Lithostitched's merged functions.
        // HolderHolder.mapAll creates direct holders, which must be unwrapped
        // before a nested DIRECT_CODEC attempts to call HolderHolder.codec().
        var records = records();
        var prepared = (DensityFunction) records.capture(function);
        return (DensityFunction) records.capture(prepared.mapAll(UNWRAP));
    }

    private static AppliedDensityRecords records() {
        return new AppliedDensityRecords(
                value -> value instanceof DensityFunction function ? TectonicDensitySnapshot.capture(function) : value,
                value -> value instanceof DensityFunction || value instanceof net.minecraft.world.level.levelgen.NoiseRouter
                        || value.getClass().getName().startsWith("net.minecraft.util.CubicSpline$")
                        || value.getClass().getName().startsWith("net.minecraft.world.level.levelgen.DensityFunctions$"));
    }

    public static NoiseGeneratorSettings applied(NoiseGeneratorSettings settings) {
        var records = records();
        var prepared = (net.minecraft.world.level.levelgen.NoiseRouter) records.capture(settings.noiseRouter());
        return new NoiseGeneratorSettings(settings.noiseSettings(), settings.defaultBlock(),
                settings.defaultFluid(), (net.minecraft.world.level.levelgen.NoiseRouter)
                        records.capture(prepared.mapAll(UNWRAP)),
                settings.surfaceRule(), settings.spawnTarget(), settings.seaLevel(),
                settings.disableMobGeneration(), settings.aquifersEnabled(),
                settings.oreVeinsEnabled(), settings.useLegacyRandomSource());
    }
}
