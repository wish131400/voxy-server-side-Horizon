package dev.xantha.vss.testutil;

import com.mojang.serialization.Codec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public final class DensityCodecFixtures {
    public static final Codec<DensityFunction> CODEC = DensityFunctions.DIRECT_CODEC;
    private DensityCodecFixtures() { }
}
