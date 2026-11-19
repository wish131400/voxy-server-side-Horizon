package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;
import org.junit.jupiter.api.*;

class PredictionChunkTerrainTest {
    @BeforeAll static void bootstrap(){TerrainPredictionFixtures.bootstrap();}
    @Test void sharedChunkMatchesOriginalColumnsIncludingFluidsAndNegativeBorders() {
        var access=TerrainPredictionFixtures.access();
        for(var dimension:List.of(NoiseGeneratorSettings.OVERWORLD,NoiseGeneratorSettings.NETHER,NoiseGeneratorSettings.END)) {
            var settings=access.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(dimension).value();
            var heights=LevelHeightAccessor.create(settings.noiseSettings().minY(),settings.noiseSettings().height());
            var generator=new NoiseBasedChunkGenerator(new FixedBiomeSource(access.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)),Holder.direct(settings));
            var random=RandomState.create(access.lookupOrThrow(Registries.NOISE),5052304137288917019L,settings);
            var fast=new PredictionChunkTerrain(generator,random,heights);
            for(var pos:List.of(new ChunkPos(-1,-2),new ChunkPos(100,-91))) {
                var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,heights,
                        net.minecraft.world.level.chunk.PalettedContainerFactory.create(access),null);
                fast.fill(pos,chunk);
                for(int[] p:List.of(new int[]{0,0},new int[]{3,7},new int[]{4,4},new int[]{15,15})) {
                    int x=pos.getMinBlockX()+p[0],z=pos.getMinBlockZ()+p[1];
                    var original=generator.getBaseColumn(x,z,heights,random);
                    var cursor=new BlockPos.MutableBlockPos();
                    for(int y=heights.getMinY();y<heights.getMinY()+heights.getHeight();y++)
                        assertSame(original.getBlock(y),chunk.getBlockState(cursor.set(x,y,z)),dimension+" "+x+","+y+","+z);
                }
            }
            assertTrue(fast.diagnostics().contains("mode=chunkBatch"));
            assertTrue(fast.diagnostics().contains("orderedColumns=0"));
        }
    }
    @Test void unknownDensityKeepsOriginalColumnEvaluation() {
        var original=DensityFunctions.yClampedGradient(-64,192,1,-1);
        DensityFunction opaque=new DensityFunction() {
            @Override public DensitySampler compileSampler(CompileContext context){return original.compileSampler(context);}
            @Override public DensityFunction rewriteChildren(DfRewriteRule rule){return this;}
            @Override public net.minecraft.util.Interval range(){return original.range();}
            @Override public int domainAxes(){return original.domainAxes();}
            @Override public com.mojang.serialization.MapCodec<? extends DensityFunction> codec(){throw new UnsupportedOperationException();}
        };
        var settings=ModernDensityFixtures.densitySettings(opaque);
        var access=TerrainPredictionFixtures.access();
        var generator=new NoiseBasedChunkGenerator(new FixedBiomeSource(access.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)),Holder.direct(settings));
        var heights=LevelHeightAccessor.create(settings.noiseSettings().minY(),settings.noiseSettings().height());
        var random=ModernDensityFixtures.random(settings,42);
        var fast=new PredictionChunkTerrain(generator,random,heights);
        assertTrue(fast.diagnostics().contains("mode=orderedColumns"));
        var pos=new ChunkPos(-1,-2);
        var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,heights,
                net.minecraft.world.level.chunk.PalettedContainerFactory.create(access),null);
        fast.fill(pos,chunk);
        var expected=generator.getBaseColumn(pos.getMinBlockX(),pos.getMinBlockZ(),heights,random);
        var cursor=new BlockPos.MutableBlockPos();
        for(int y=heights.getMinY();y<heights.getMinY()+heights.getHeight();y++)
            assertSame(expected.getBlock(y),chunk.getBlockState(cursor.set(pos.getMinBlockX(),y,pos.getMinBlockZ())));
        assertTrue(fast.diagnostics().contains("orderedColumns=256"));
    }
}
