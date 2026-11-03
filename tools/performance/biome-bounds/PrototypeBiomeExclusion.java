package dev.xantha.vss.client.prediction;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.placement.*;
import com.mojang.serialization.JsonOps;

/** Frozen-benchmark-only whole-chain absence experiment; not registered in production. */
final class PrototypeBiomeExclusion {
    private static final int XZ=4, Y=32, POINTS=XZ*XZ*Y, MAX_PAGES=512, MAX_QUERY_PAGES=64;
    // At most 4096 palette bits/page: < 0.5 MiB for 512 complete summaries,
    // including page keys/map entries. Scratch buffers are reused, not retained/page.
    private static final int MAX_BIOMES=4096, MAX_PLANS=256;
    private final ClientTerrainSampler terrain, context;
    private final LinkedHashMap<PlacedFeature, Candidate> candidates=new LinkedHashMap<>(32,.75f,true);
    private final LinkedHashMap<PageKey,Summary> pages=new LinkedHashMap<>(64,.75f,true);
    private final ThreadLocal<int[]> positions=ThreadLocal.withInitial(()->new int[256*3]);
    private final ThreadLocal<int[]> results=ThreadLocal.withInitial(()->new int[256]);
    private long checks,skips,hits,points,nanos,abandoned;
    private record Candidate(PredictionFeatureBiomeBounds.Plan bounds,BitSet allowed) { }
    private static final Candidate UNKNOWN=new Candidate(null,null);
    private record PageKey(int x,int y,int z) { }
    private record Summary(BitSet found) { }

    PrototypeBiomeExclusion(ClientTerrainSampler terrain,ClientTerrainSampler context) {
        this.terrain=terrain;this.context=context;
    }
    boolean excludes(PlacedFeature feature,WorldGenLevel level,BlockPos origin,BooleanSupplier current) {
        long start=System.nanoTime();
        try {
            active(current);
            Candidate candidate;
            synchronized(candidates) {
                candidate=candidates.get(feature);
                if(candidate==null) {
                    candidate=compile(feature);candidates.put(feature,candidate);
                    if(candidates.size()>MAX_PLANS)candidates.remove(candidates.keySet().iterator().next());
                }
            }
            if(candidate==UNKNOWN)return false;
            checks++;
            var bounds=candidate.bounds.bounds(origin,new WorldGenerationContext(context.generatorContext(),level),level.getMinY(),level.getMaxY());
            if(bounds==null)return false;
            var q=PredictionFeatureBiomeBounds.quartEnvelope(bounds);
            int minX=Math.floorDiv(q.minX(),XZ),maxX=Math.floorDiv(q.maxX(),XZ);
            int minY=Math.floorDiv(q.minY(),Y),maxY=Math.floorDiv(q.maxY(),Y);
            int minZ=Math.floorDiv(q.minZ(),XZ),maxZ=Math.floorDiv(q.maxZ(),XZ);
            long nx=(long)maxX-minX+1,ny=(long)maxY-minY+1,nz=(long)maxZ-minZ+1;
            if(nx<=0 || ny<=0 || nz<=0 || nx>MAX_QUERY_PAGES || ny>MAX_QUERY_PAGES || nz>MAX_QUERY_PAGES)
                return false;
            long count=nx*ny*nz;
            if(count>MAX_QUERY_PAGES)return false;
            for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++) {
                active(current);
                Summary page=page(new PageKey(x,y,z),candidate.allowed,current);
                if(page==null || page.found.intersects(candidate.allowed))return false;
            }
            skips++;return true;
        } finally {nanos+=System.nanoTime()-start;}
    }
    private Candidate compile(PlacedFeature feature) {
        if(context.generatorContext()==null || context.cacheBiomes().size()>MAX_BIOMES)return UNKNOWN;
        boolean highCount=false;
        for(var modifier:feature.placement())if(modifier.getClass()==CountPlacement.class) {
            try {
                var value=PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE,modifier).getOrThrow().getAsJsonObject().get("count");
                if(value.isJsonPrimitive()) highCount|=value.getAsInt()>=128;
                else {
                    var object=value.getAsJsonObject();String type=object.get("type").getAsString();
                    if(type.equals("minecraft:constant"))highCount|=object.get("value").getAsInt()>=128;
                    else if(type.equals("minecraft:uniform")) {
                        var range=object.has("value")?object.getAsJsonObject("value"):object;
                        highCount|=range.get("min_inclusive").getAsInt()>=128;
                    }
                }
            } catch(RuntimeException unsupported){return UNKNOWN;}
        }
        if(!highCount)return UNKNOWN;
        var plan=PredictionFeatureBiomeBounds.compile(feature);
        if(!plan.known())return UNKNOWN;
        BitSet allowed=new BitSet();
        for(int index=0;index<context.cacheBiomes().size();index++) {
            var biome=context.cacheBiomeHolder(index);
            if(biome==null)return UNKNOWN;
            if(context.generatorContext().getBiomeGenerationSettings(biome).hasFeature(feature))allowed.set(index);
        }
        return new Candidate(plan,allowed);
    }
    private Summary page(PageKey key,BitSet allowed,BooleanSupplier current) {
        synchronized(pages) {
            var cached=pages.get(key);
            if(cached!=null){hits++;return cached;}
        }
        BitSet found=new BitSet();int[] xyz=positions.get(),indices=results.get();
        for(int start=0;start<POINTS;start+=256) {
            active(current);int count=Math.min(256,POINTS-start);
            for(int i=0;i<count;i++) {
                int cell=start+i;
                xyz[i*3]=key.x*XZ+cell%XZ;
                xyz[i*3+1]=key.y*Y+cell/(XZ*XZ);
                xyz[i*3+2]=key.z*XZ+cell/XZ%XZ;
            }
            java.util.Arrays.fill(indices,-1);
            terrain.sampleBiomeIndices(xyz,count,indices);points+=count;active(current);
            for(int i=0;i<count;i++) {
                int biome=indices[i];
                if(biome<0 || biome>=MAX_BIOMES || allowed.get(biome)) {abandoned++;return null;}
                found.set(biome);
            }
        }
        var summary=new Summary(found);
        synchronized(pages) {
            pages.put(key,summary);
            if(pages.size()>MAX_PAGES)pages.remove(pages.keySet().iterator().next());
        }
        return summary;
    }
    Map<String,Object> snapshot() {
        synchronized(pages){return Map.of("checks",checks,"skips",skips,"pages",pages.size(),"pageHits",hits,
                "points",points,"nanos",nanos,"abandoned",abandoned);}
    }
    private static void active(BooleanSupplier current) {
        if(Thread.currentThread().isInterrupted() || !current.getAsBoolean())
            throw new java.util.concurrent.CancellationException("biome absence experiment cancelled");
    }
}
