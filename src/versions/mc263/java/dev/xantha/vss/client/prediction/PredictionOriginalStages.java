package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.GeneratorStageCapabilities;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LevelHeightAccessor;

/** FP32 merged terrain requires a real region; independent original columns remain usable. */
final class PredictionOriginalStages extends ClientTerrainSampler {
    private final ClientTerrainSampler source;
    private final LevelHeightAccessor heights;
    private final LinkedHashMap<Long,PredictionColumnVolume> columns=new LinkedHashMap<>(256,.75f,true);
    private boolean closed;
    PredictionOriginalStages(ClientTerrainSampler source) {
        super(source,null);this.source=source;
        heights=LevelHeightAccessor.create(profile().minY(),profile().height());
        var capabilities=GeneratorStageCapabilities.inspect(generatorContext().getClass());
        if(!capabilities.isolated() || !capabilities.columns() || capabilities.terrain() || capabilities.surface())
            throw new IllegalArgumentException("Original merged terrain requires an initialized world region; use authoritative LOD");
    }
    @Override public ClientColumnSample sample(int x, int z) {
        var column = volume(x, z);
        var sample = column.asSample();
        if (column.size() == 0) return new ClientColumnSample(profile().minY(),profile().minY(),-1,
                ClientColumnSample.NO_BLOCK,0,0,0,0,0,ClientColumnSample.FLAG_NO_SURFACE,0,
                ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,column);
        var biome = noiseBiome(x >> 2, (sample.surfaceY() - 1) >> 2, z >> 2);
        var key = biome == null ? null : biome.unwrapKey().orElse(null);
        int index = key == null ? -1 : cacheBiomeIndices().getOrDefault(key.identifier().toString(), -1);
        int water = sample.surfaceY(), fluid = 0;
        for (int i = column.size() - 1; i >= 0; i--) if (column.fluid(i) != 0 && column.top(i) > water) {
            water = column.top(i); fluid = column.fluid(i); break;
        }
        return new ClientColumnSample(sample.surfaceY(),water,index,sample.topBlockIndex(),0,0,0,0,fluid,sample.flags(),0,
                column.blockAt(sample.surfaceY()-2),column.blockAt(sample.surfaceY()-7),sample.surfaceBottom(),
                sample.lowerTop(),sample.lowerBottom(),sample.spanFloor(),column);
    }
    private synchronized PredictionColumnVolume volume(int x, int z) {
        if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException("Original generator context closed");
        long key=(long)x<<32|z&0xffffffffL;
        var cached=columns.get(key);if(cached!=null)return cached;
        var column=generatorContext().getBaseColumn(x,z,heights,randomStateContext());
        var registry=BuiltInRegistries.BLOCK;
        var result=PredictionColumnVolume.sample(profile().minY(),profile().height(),y -> {
            var state=column.getBlock(y);return state.isAir() ? -1 : registry.getId(state.getBlock());
        }, id -> {
            var state=registry.byId(id).defaultBlockState();
            return state.getFluidState().isEmpty() ? 0 : state.is(net.minecraft.world.level.block.Blocks.LAVA) ? 2 : 1;
        });
        columns.put(key,result);
        while(columns.size()>4096)columns.remove(columns.keySet().iterator().next());
        return result;
    }
    @Override public ClientColumnSample sampleSurface(int x, int z) { return sample(x, z); }
    @Override public ClientColumnSample samplePreview(int x, int z) { return sample(x, z); }
    @Override public ClientColumnSample samplePreview(int x, int z, int step) { return sample(x, z); }
    @Override int previewSurfaceY(int x, int z) { return surfaceY(x, z); }
    @Override public int surfaceY(int x, int z) { return sample(x, z).surfaceY(); }
    @Override public int groundY(int x, int z) { return surfaceY(x, z); }
    @Override PredictionColumnVolume exteriorColumn(int x, int z) { return volume(x, z); }
    @Override ClientColumnSample sampleInterior(int x, int z) { return sample(x, z); }
    @Override boolean[] exteriorFootprint(int x, int z, int step, int bottom, int top, java.util.function.BooleanSupplier valid) {
        boolean[] occupied = new boolean[top - bottom];
        for (int dz = 0; dz < step; dz++) for (int dx = 0; dx < step; dx++) {
            if (!valid.getAsBoolean()) throw new CancellationException();
            var column = volume(x + dx, z + dz);
            if (dx == 0 && dz == 0 && !column.occupied(top - 1, false)) return null;
            for (int y = bottom; y < top; y++) occupied[y - bottom] |= column.occupied(y, false);
        }
        return occupied;
    }
    @Override synchronized public void close() { if (closed) return; closed=true; columns.clear(); source.close();
        if ((Object) generatorContext() instanceof AutoCloseable owner) try { owner.close(); }
        catch (Exception failure) { dev.xantha.vss.common.VSSLogger.warn("VSS original generator cleanup failed",failure); }
    }
}
