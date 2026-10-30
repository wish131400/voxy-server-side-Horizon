package dev.xantha.vss.testutil;

import com.mojang.serialization.Codec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** The direct density codec moved independently of the registry API. */
public final class DensityCodecFixtures {
    public static final Codec<DensityFunction> CODEC = DensityFunction.DIRECT_CODEC;
    private DensityCodecFixtures() { }
}
