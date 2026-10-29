package dev.xantha.vss.testutil;

import com.mojang.serialization.Codec;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Minecraft 26.3 stores the complete NormalNoise value in the noise registry. */
public final class NoiseCodecFixtures {
    public static final Codec<NormalNoise> CODEC = NormalNoise.DIRECT_CODEC;
    private NoiseCodecFixtures() { }
}
