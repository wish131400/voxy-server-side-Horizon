package dev.xantha.vss.common.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Applied Tectonic parameters; its source codec deliberately replays the current mod config. */
public record TectonicConfigNoise(DensityFunction.NoiseHolder noise, DensityFunction shiftX, DensityFunction shiftZ,
                                  double scale, double multiplier, double offset, boolean smootherScaling)
        implements DensityFunction {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("vss", "tectonic_config_noise");
    private static final MapCodec<TectonicConfigNoise> DATA_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            NoiseHolder.CODEC.fieldOf("noise").forGetter(TectonicConfigNoise::noise),
            DensityFunction.CODEC.fieldOf("shift_x").forGetter(TectonicConfigNoise::shiftX),
            DensityFunction.CODEC.fieldOf("shift_z").forGetter(TectonicConfigNoise::shiftZ),
            Codec.DOUBLE.fieldOf("scale").forGetter(TectonicConfigNoise::scale),
            Codec.DOUBLE.fieldOf("multiplier").forGetter(TectonicConfigNoise::multiplier),
            Codec.DOUBLE.fieldOf("offset").forGetter(TectonicConfigNoise::offset),
            Codec.BOOL.fieldOf("smoother_scaling").forGetter(TectonicConfigNoise::smootherScaling)
    ).apply(instance, TectonicConfigNoise::new));
    private static final KeyDispatchDataCodec<TectonicConfigNoise> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    public static synchronized void register() {
        if (!BuiltInRegistries.DENSITY_FUNCTION_TYPE.containsKey(ID))
            Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, ID, CODEC.codec());
    }

    @Override public double compute(FunctionContext context) {
        double x, z;
        if (smootherScaling) {
            x = (context.blockX() + shiftX.compute(context)) * scale;
            z = (context.blockZ() + shiftZ.compute(context)) * scale;
        } else {
            x = context.blockX() * scale + shiftX.compute(context);
            z = context.blockZ() * scale + shiftZ.compute(context);
        }
        return noise.getValue(x, 0.0, z) * multiplier + offset;
    }

    @Override public void fillArray(double[] values, ContextProvider context) { context.fillAllDirectly(values, this); }
    @Override public DensityFunction mapChildren(Visitor visitor) {
        return new TectonicConfigNoise(visitor.visitNoise(noise), shiftX.mapAll(visitor),
                shiftZ.mapAll(visitor), scale, multiplier, offset, smootherScaling);
    }
    // Match the released ConfigNoise bounds, including its omission of scale/offset.
    @Override public double minValue() { return -noise.maxValue(); }
    @Override public double maxValue() { return noise.maxValue(); }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
