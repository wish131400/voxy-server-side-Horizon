package dev.xantha.vss.testutil;

import com.mojang.serialization.Codec;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Version boundary for the noise registry's actual value codec. */
public final class NoiseCodecFixtures {
    public static final Codec<NormalNoise.NoiseParameters> CODEC = NormalNoise.NoiseParameters.DIRECT_CODEC;
    private NoiseCodecFixtures() { }
}
