package dev.xantha.vss.client.prediction;

import dev.xantha.vss.api.worldgen.DensityFunctionTraits;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.densityfunction.generator.*;
import net.minecraft.world.level.levelgen.densityfunction.op.*;

/** One compiled density volume per surface chunk, retaining Minecraft's aquifer. */
final class PredictionChunkTerrain {
    private final NoiseBasedChunkGenerator generator;
    private final RandomState random;
    private final LevelHeightAccessor heights;
    private final NoiseSettings noise;
    private final Aquifer.FluidPicker fluids;
    private final boolean batch;
    private final LongAdder chunks=new LongAdder(),orderedColumns=new LongAdder();

    PredictionChunkTerrain(ClientTerrainSampler source) {
        this(source.generatorContext(),source.randomStateContext(),
                LevelHeightAccessor.create(source.profile().minY(),source.profile().height()));
    }
    PredictionChunkTerrain(NoiseBasedChunkGenerator generator,RandomState random,LevelHeightAccessor heights) {
        this.generator=generator;this.random=random;this.heights=heights;
        noise=generator==null?null:generator.generatorSettings().value().noiseSettings().clampToHeightAccessor(heights);
        batch=noise!=null && new Graph().known(generator.generatorSettings().value().noiseRouter());
        fluids=batch?fluids(generator):null;
    }
    private static Aquifer.FluidPicker fluids(NoiseBasedChunkGenerator generator) {
        try {
            var field=Arrays.stream(NoiseBasedChunkGenerator.class.getDeclaredFields())
                    .filter(f->f.getType()==Supplier.class).findFirst().orElseThrow();
            field.setAccessible(true);
            var getter=MethodHandles.lookup().unreflectGetter(field)
                    .asType(MethodType.methodType(Supplier.class,NoiseBasedChunkGenerator.class));
            return (Aquifer.FluidPicker)((Supplier<?>)getter.invokeExact(generator)).get();
        } catch(RuntimeException | Error failure) { throw failure; }
        catch(Throwable failure) { throw new IllegalStateException("Prediction chunk fluid picker",failure); }
    }
    private static void active() {
        if(Thread.currentThread().isInterrupted())throw new CancellationException();
    }
    void fill(ChunkPos pos,ProtoChunk chunk) {
        active();chunks.increment();
        if(!batch){fillOrdered(pos,chunk);return;}
        var settings=generator.generatorSettings().value();
        var volume=new DensityVolume(16,noise.height(),16,pos.getMinBlockX(),noise.minY(),pos.getMinBlockZ());
        var cursor=new BlockPos.MutableBlockPos();
        try(var work=new NoiseChunk(random,Beardifier.EMPTY,settings,fluids,Blender.empty(),volume);
                var density=work.cachingSamplers().get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
            // Match the full chunk generator's Z/X/descending-Y traversal and volume indices.
            for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                active();int bx=pos.getMinBlockX()+x,bz=pos.getMinBlockZ()+z;
                for(int y=noise.height()-1;y>=0;y--) {
                    int by=noise.minY()+y;
                    var state=work.aquifer().computeSubstance(bx,by,bz,density.get(volume.indexUnchecked(x,y,z)));
                    if(state==null)state=settings.defaultBlock();
                    if(!state.isAir())chunk.setBlockState(cursor.set(bx,by,bz),state,0);
                }
            }
        }
    }
    private void fillOrdered(ChunkPos pos,ProtoChunk chunk) {
        var cursor=new BlockPos.MutableBlockPos();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            active();orderedColumns.increment();
            int bx=pos.getMinBlockX()+x,bz=pos.getMinBlockZ()+z;
            var raw=generator.getBaseColumn(bx,bz,heights,random);
            for(int y=heights.getMinY();y<heights.getMinY()+heights.getHeight();y++) {
                var state=raw.getBlock(y);
                if(!state.isAir())chunk.setBlockState(cursor.set(bx,y,bz),state,0);
            }
        }
    }
    String diagnostics(){return "mode="+(batch?"chunkBatch":"orderedColumns")+",chunks="+chunks.sum()+",orderedColumns="+orderedColumns.sum();}

    /** New caches declare their axes; opaque mod nodes retain the original column request. */
    private static final class Graph {
        private static final Set<Class<?>> VANILLA=Set.of(DensityFunctions.class,
                ConstantFunction.class,DistanceToPointFunction.class,EndIslandFunction.class,GradientFunction.class,
                NoiseFunction.class,ShiftNoiseFunction.class,SimpleDensityFunction.class,
                net.minecraft.world.level.levelgen.synth.BlendedNoise.class,
                BinaryFunction.class,BlendDensityFunction.class,CacheFunction.class,ClampFunction.class,
                FindTopSurfaceFunction.class,InterpolatedFunction.class,IntervalSelectFunction.class,LerpFunction.class,
                PowFunction.class,RangeChoiceFunction.class,RoundFunction.class,SliceFunction.class,SplineFunction.class,UnaryFunction.class);
        private final IdentityHashMap<DensityFunction,Boolean> seen=new IdentityHashMap<>();
        private int remaining=32768;
        boolean known(NoiseRouter router) {
            return known(router.temperature(),0) && known(router.vegetation(),0) && known(router.continents(),0)
                    && known(router.erosion(),0) && known(router.depth(),0) && known(router.ridges(),0)
                    && known(router.chunkSurfaceLevel(),0) && known(router.finalDensity(),0);
        }
        private boolean known(DensityFunction function,int depth) {
            Boolean previous=seen.get(function);if(previous!=null)return previous;
            if(depth>128 || --remaining<0)return false;
            seen.put(function,false);
            var traits=DensityFunctionTraits.find(function);
            if(traits!=null) {
                boolean safe=traits.coordinatePure() && !traits.ownsRuntimeContext();
                seen.put(function,safe);return safe;
            }
            if(function instanceof DensityFunctions.HolderHolder holder) {
                boolean safe=known(holder.function().value(),depth+1);seen.put(function,safe);return safe;
            }
            if(!VANILLA.contains(function.getClass().getNestHost()))return false;
            try {
                boolean[] safe={true};
                function.rewriteChildren(child->{safe[0]&=known(child,depth+1);return child;});
                seen.put(function,safe[0]);return safe[0];
            } catch(RuntimeException unsupported){return false;}
        }
    }
}
