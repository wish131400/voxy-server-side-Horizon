package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.material.rule.BlockRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Modern material rules operate on every solid state and preserve fluids and air. */
class FloatMaterialPipelineTest {
    @BeforeAll static void bootstrap() { ModernDensityFixtures.bootstrap(); }

    @Test void interiorMaterialRulesCoverNondefaultSolidsAndPreserveNullableResults() {
        var biomeRegistry = new MappedRegistry<Biome>(Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
        ModernDensityFixtures.lookup().lookupOrThrow(Registries.BIOME).listElements().forEach(
                holder -> biomeRegistry.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        biomeRegistry.freeze();
        var base = ModernDensityFixtures.settings(NoiseGeneratorSettings.OVERWORLD);
        var raw = PredictionColumnVolume.ofRuns(new int[]{
                48, 50, id(Blocks.GRANITE), 0,
                50, 52, id(Blocks.NETHERRACK), 0,
                52, 54, id(Blocks.STONE), 0,
                54, 55, id(Blocks.WATER), 1,
                56, 58, id(Blocks.BASALT), 0,
                58, 59, id(Blocks.LAVA), 2});
        for (MaterialRule rule : List.of(new BlockRule(Blocks.GOLD_BLOCK.defaultBlockState()), new SequenceRule(List.of()))) {
            var settings = new NoiseGeneratorSettings(base.noiseSettings(), base.defaultBlock(), base.defaultFluid(),
                    base.noiseRouter(), Holder.direct(rule), base.spawnTarget(), base.seaLevel(), base.disableMobGeneration(),
                    base.aquifers(), base.useLegacyRandomSource(), base.debugFunctions());
            var source = new FixedBiomeSource(biomeRegistry.getOrThrow(Biomes.PLAINS));
            var random = ModernDensityFixtures.random(settings, 42);
            var generator = new NoiseBasedChunkGenerator(source, Holder.direct(settings));
            var cache = new PredictionBiomeCache(source, random.createClimateSampler(
                    net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED));
            var resolver = new ClientSurfaceResolver(generator, random,
                    LevelHeightAccessor.create(-64, 384), biomeRegistry, cache);
            var result = resolver.resolveInterior(raw.asSample(), 3, -5, (x, z) -> 59).volume();
            for (int y = 47; y <= 60; y++) {
                int expected = raw.blockAt(y);
                if (rule instanceof BlockRule && raw.occupied(y, true)) expected = id(Blocks.GOLD_BLOCK);
                assertEquals(expected, result.blockAt(y), "material=" + rule + " y=" + y);
            }
        }
    }

    private static int id(Block block) { return BuiltInRegistries.BLOCK.getId(block); }
}
