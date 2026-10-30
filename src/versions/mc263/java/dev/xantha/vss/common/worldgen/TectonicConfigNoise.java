package dev.xantha.vss.common.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Applied parameters and the exact scalar/volume contracts of Tectonic 3.0.32 for 26.3. */
public record TectonicConfigNoise(Holder<NormalNoise> noise, DensityFunction shiftX, DensityFunction shiftZ,
                                  float scale, float multiplier, float offset, boolean smootherScaling)
        implements DensityFunction {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("vss", "tectonic_config_noise");
    private static final MapCodec<TectonicConfigNoise> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            NormalNoise.CODEC.fieldOf("noise").forGetter(TectonicConfigNoise::noise),
            DensityFunction.CODEC.fieldOf("shift_x").forGetter(TectonicConfigNoise::shiftX),
            DensityFunction.CODEC.fieldOf("shift_z").forGetter(TectonicConfigNoise::shiftZ),
            Codec.FLOAT.fieldOf("scale").forGetter(TectonicConfigNoise::scale),
            Codec.FLOAT.fieldOf("multiplier").forGetter(TectonicConfigNoise::multiplier),
            Codec.FLOAT.fieldOf("offset").forGetter(TectonicConfigNoise::offset),
            Codec.BOOL.fieldOf("smoother_scaling").forGetter(TectonicConfigNoise::smootherScaling)
    ).apply(instance, TectonicConfigNoise::new));

    public static synchronized void register() {
        if (!BuiltInRegistries.DENSITY_FUNCTION_TYPE.containsKey(ID))
            Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, ID, CODEC);
    }

    @Override public DensitySampler compileSampler(CompileContext context) {
        return new Sampler(context.createNoiseSampler(noise), shiftX.compileSampler(context),
                shiftZ.compileSampler(context), scale, multiplier, offset, smootherScaling);
    }

    @Override public DensityFunction rewriteChildren(DfRewriteRule rule) {
        var x = rule.rewrite(shiftX);
        var z = rule.rewrite(shiftZ);
        return x == shiftX && z == shiftZ ? this
                : new TectonicConfigNoise(noise, x, z, scale, multiplier, offset, smootherScaling);
    }

    @Override public Interval range() { return noise.value().range(); }
    @Override public int domainAxes() { return AXIS_X | AXIS_Z | shiftX.domainAxes() | shiftZ.domainAxes(); }
    @Override public MapCodec<? extends DensityFunction> codec() { return CODEC; }

    private record Sampler(Noise noise, DensitySampler shiftX, DensitySampler shiftZ,
                           float scale, float multiplier, float offset, boolean smootherScaling)
            implements DensitySampler {
        @Override public float sampleValue(SamplerContext context, int x, int y, int z) {
            // Both upstream scalar samplers round the scaled coordinate and shift in float first.
            double shiftedX = x * scale + shiftX.sampleValue(context, x, y, z);
            double shiftedZ = z * scale + shiftZ.sampleValue(context, x, y, z);
            return noise.get(shiftedX, 0.0, shiftedZ) * multiplier + offset;
        }

        @Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            shiftX.sampleVolume(context, output, volume);
            try (var zValues = context.acquireBuffer(volume)) {
                shiftZ.sampleVolume(context, zValues, volume);
                int index = 0;
                for (int z = 0; z < volume.sizeZ(); z++) {
                    double blockZ = volume.blockZ(z);
                    for (int x = 0; x < volume.sizeX(); x++) {
                        double blockX = volume.blockX(x);
                        for (int y = 0; y < volume.sizeY(); y++, index++) {
                            double shiftedX = smootherScaling ? (blockX + output.get(index)) * scale
                                    : blockX * scale + output.get(index);
                            double shiftedZ = smootherScaling ? (blockZ + zValues.get(index)) * scale
                                    : blockZ * scale + zValues.get(index);
                            float value = noise.get(shiftedX, 0.0, shiftedZ) * multiplier;
                            // The released smooth volume sampler omits offset; retain that behavior.
                            output.set(index, smootherScaling ? value : value + offset);
                        }
                    }
                }
            }
        }
    }
}
