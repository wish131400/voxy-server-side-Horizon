package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.worldgen.BiolithWorldgenContext;
import dev.xantha.vss.common.worldgen.WorldgenRuntimeHooks;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Independent dedicated-server fixture; never included in published VSS jars. */
public final class RuntimeHooksProbe implements ModInitializer {
    @Override public void onInitialize() {
        var ran=new java.util.concurrent.atomic.AtomicBoolean();
        // All SERVER_STARTED listeners must run first: some surface builders bind
        // their world there. The separate missing-world check covers client use.
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(!ran.compareAndSet(false,true))return;
            var report=new JsonObject();
            try {
                var level=server.overworld();var access=server.registryAccess();
                var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
                var random=level.getChunkSource().randomState();
                var ops=RegistryOps.create(JsonOps.INSTANCE,access);
                var snapshot=BiolithWorldgenContext.capture(generator.getBiomeSource(),ops);
                if(snapshot==null)throw new IllegalStateException("Biolith context missing");
                var route=BiolithWorldgenContext.restore(snapshot,ops,access.lookupOrThrow(Registries.BIOME),level.getSeed());
                var climate=new PredictionClimateSampler(random,generator.generatorSettings().value().noiseRouter());
                var source=generator.getBiomeSource().createResolver(climate.sampler());
                int checked=0,modded=0;var mismatch=new JsonArray();
                for(int i=0;i<12000;i++) {
                    int x=(i*7919)%100003-50001,z=(i*3559)%100019-50009,y=i%65-16;
                    var expected=source.getNoiseBiome(x,y,z);
                    var actual=route.sample(x,y,z,climate.sampler().sample(x,y,z));checked++;
                    if(expected.unwrapKey().orElseThrow().identifier().getNamespace().equals("terrestria"))modded++;
                    if(!actual.unwrapKey().equals(expected.unwrapKey()) && mismatch.size()<10)mismatch.add(x+","+y+","+z+":"+expected+" != "+actual);
                }
                climate.close();
                report.addProperty("checked",checked);report.addProperty("terrestriaSamples",modded);report.add("mismatches",mismatch);
                if(mismatch.size()!=0 || modded==0)throw new IllegalStateException("Frozen biome routing differs");
                boolean hooks=WorldgenRuntimeHooks.changed(net.minecraft.world.level.levelgen.material.MaterialSystem.class);
                report.addProperty("surfaceHooksDetected",hooks);
                if(!hooks)throw new IllegalStateException("Transformed surface stage not detected");
                var registryRoot=new JsonObject();
                var profile=new DimensionProfile(level.dimension().identifier(),level.getSeed(),level.getMinY(),level.getHeight(),"noise","overworld",42);
                var heights=LevelHeightAccessor.create(level.getMinY(),level.getHeight());
                var context=new ClientTerrainSampler(level.getSeed(),profile,generator,random,heights,generator.getSeaLevel(),null,access);
                int surfaces=0;
                try(var stage=new PredictionSurfaceStage(context,(pos,chunk)->{
                    for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                        int bx=pos.getMinBlockX()+x,bz=pos.getMinBlockZ()+z;
                        var raw=generator.getBaseColumn(bx,bz,heights,random);
                        for(int y=level.getMinY();y<=level.getMaxY();y++) {
                            var state=raw.getBlock(y);
                            if(!state.isAir())chunk.setBlockState(new BlockPos(bx,y,bz),state,0);
                        }
                    }
                })) {
                    for(int i=0;i<8;i++) {
                        var column=stage.sample(i*4096-12000,i*2713-10000);
                        if(column.hasSurface())surfaces++;
                    }
                    report.addProperty("surfaceChunks",surfaces);report.addProperty("surfaceFailures",stage.failures());
                    if(stage.failures()!=0 || surfaces!=8)throw new IllegalStateException("Surface hooks failed");
                } finally {context.close();}
                var payload=dev.xantha.vss.networking.server.session.WorldgenProfileBuilder.build(server,1,level.dimension());
                report.addProperty("generatorRawBytes",payload.dimensions().getFirst().generatorRawSize());
                var registryJson=dev.xantha.vss.common.processing.LodByteCompression.decompress(payload.registries(),payload.registriesCompression(),payload.registriesRawSize(),100_000_000);
                var registryDoc=JsonParser.parseString(new String(registryJson,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                var synced=ClientWorldgenRegistries.decode(registryDoc,access);
                var wire=payload.dimensions().getFirst();
                var generatorJson=dev.xantha.vss.common.processing.LodByteCompression.decompress(wire.generatorData(),wire.generatorCompression(),wire.generatorRawSize(),10_000_000);
                var generatorDoc=JsonParser.parseString(new String(generatorJson,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                var javaContext=ClientWorldgenProfileDecoder.decodeJavaSampler(wire,generatorDoc,synced,access);
                var nativeDoc=RustWorldgenDocument.snapshot(generatorDoc,registryDoc,javaContext);
                // Dedicated servers have no client texture manager. Use vanilla fixture colormaps.
                for(String name:List.of("grass","foliage"))nativeDoc.add(name+"_colormap",JsonParser.parseString(Files.readString(Path.of("../../tools/rust/vss-native-core/tests/fixtures/worldgen/"+name+".json"))));
                if(!RustTerrainSampler.available())throw new IllegalStateException("Native library unavailable");
                Files.writeString(Path.of("runtime-native-document.json"),nativeDoc.toString());
                var rawReport=new JsonObject();
                long rawWorld=RustWorldgenBackend.create(level.getSeed(),net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(level.getSeed()),nativeDoc.toString());
                try {
                    int height=wire.height(),stride=16+height*4;
                    var raw=java.nio.ByteBuffer.allocateDirect(256*height*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    var positions=java.nio.ByteBuffer.allocateDirect(64*8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    var columns=java.nio.ByteBuffer.allocateDirect(64*stride).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    long fastNanos=0,oldNanos=0;int mismatches=0,states=0;
                    for(int trial=0;trial<8;trial++) {
                        int cx=1500+trial*41,cz=-1500-trial*31;
                        long started=System.nanoTime();
                        if(RustWorldgenBackend.rawChunk(rawWorld,cx,cz,raw)!=256)throw new IllegalStateException("Raw chunk incomplete");
                        fastNanos+=System.nanoTime()-started;
                        for(int first=0;first<256;first+=64) {
                            positions.clear();
                            for(int i=first;i<first+64;i++)positions.putInt(cx*16+(i>>4)).putInt(cz*16+(i&15));
                            started=System.nanoTime();
                            if(RustWorldgenBackend.columns(rawWorld,positions,columns,64)!=64)throw new IllegalStateException("Columns incomplete");
                            oldNanos+=System.nanoTime()-started;
                            for(int i=0;i<64;i++)for(int y=0;y<height;y++) {
                                states++;
                                if(raw.getInt(((first+i)*height+y)*4)!=columns.getInt(i*stride+16+y*4))mismatches++;
                            }
                        }
                    }
                    rawReport.addProperty("chunks",8);rawReport.addProperty("statesCompared",states);
                    rawReport.addProperty("stateMismatches",mismatches);
                    rawReport.addProperty("chunkMs",fastNanos/1_000_000.0);rawReport.addProperty("columnsMs",oldNanos/1_000_000.0);
                    rawReport.addProperty("speedup",(double)oldNanos/fastNanos);report.add("rawChunk",rawReport);
                    if(mismatches!=0)throw new IllegalStateException("Raw chunk differs from raw column occupancy");
                } finally {RustWorldgenBackend.close(rawWorld);}
                var hybrid=new RustTerrainSampler(RustWorldgenBackend.create(level.getSeed(),net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(level.getSeed()),nativeDoc.toString()),wire,javaContext);
                hybrid.configureRuntimeStages(generatorDoc,registryDoc);
                var samplers=Map.<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,ClientTerrainSampler>of(level.dimension(),hybrid);
                try {
                    var predicted=samplers.get(level.dimension());
                    if(!(predicted instanceof RustTerrainSampler))throw new IllegalStateException("Expected hybrid Rust executor: "+predicted);
                    report.addProperty("nativeExecutor",predicted.getClass().getSimpleName());
                    var expectedClimate=new PredictionClimateSampler(random,generator.generatorSettings().value().noiseRouter());
                    var expectedSource=generator.getBiomeSource().createResolver(expectedClimate.sampler());
                    for(int i=0;i<256;i++) {
                        int x=i*173-22000,z=i*79-10000,y=16;
                        var expected=expectedSource.getNoiseBiome(x,y,z);
                        var actual=predicted.noiseBiome(x,y,z);
                        if(!expected.unwrapKey().equals(actual.unwrapKey()))throw new IllegalStateException("Hybrid biome mismatch");
                        if(!javaContext.generatorContext().getBiomeSource().possibleBiomes().contains(actual))
                            throw new IllegalStateException("Routing output missing from possible biomes");
                    }
                    expectedClimate.close();
                    var throughput=new JsonObject();
                    int originX=24000,originZ=-24000,step=64,side=4;
                    long before=hybrid.runtimeSurfaceChunks(),started=System.nanoTime();
                    var sparse=new ClientColumnSample[side*side];
                    hybrid.sampleGridInPlace(originX,originZ,step,side,side,sparse,0,side,true);
                    throughput.addProperty("basicMs",(System.nanoTime()-started)/1_000_000.0);
                    throughput.addProperty("basicHookChunks",hybrid.runtimeSurfaceChunks()-before);
                    if(hybrid.runtimeSurfaceChunks()!=before || !PredictionSurfaceRefinement.pending(sparse))
                        throw new IllegalStateException("Basic coverage entered hooks or lost pending quality");
                    for(var value:sparse)if(value.reusableFor(false))throw new IllegalStateException("Preview became exact");
                    started=System.nanoTime();
                    PredictionSurfaceRefinement.completeGrid(hybrid,originX+step,originZ+step,step,side,sparse,()->true);
                    throughput.addProperty("fullMs",(System.nanoTime()-started)/1_000_000.0);
                    throughput.addProperty("fullHookChunks",hybrid.runtimeSurfaceChunks()-before);
                    if(PredictionSurfaceRefinement.pending(sparse) || hybrid.runtimeSurfaceChunks()-before!=16)
                        throw new IllegalStateException("Runtime refinement did not complete");
                    long fullChunks=hybrid.runtimeSurfaceChunks();
                    PredictionSurfaceRefinement.completeGrid(hybrid,originX+step,originZ+step,step,side,sparse,()->true);
                    if(hybrid.runtimeSurfaceChunks()!=fullChunks)throw new IllegalStateException("Complete grid repeated chunk generation");
                    throughput.addProperty("points",sparse.length);throughput.addProperty("completedGridReused",true);
                    report.add("surfaceThroughput",throughput);
                    var sample=predicted.sampleSurface(128,128);
                    var interior=predicted.sampleInterior(128,128);
                    if(sample.surfaceY()!=interior.surfaceY() || sample.topBlockIndex()!=interior.topBlockIndex())throw new IllegalStateException("Hybrid surface/interior differ");
                    report.addProperty("hybridSurfaceHeight",sample.surfaceY());
                    report.addProperty("hybridDiagnostics",((RustTerrainSampler)predicted).diagnostics());
                    // Dedicated-server state is unavailable in a remote client.
                    // Exercise rollback at a known island chunk without substituting a fake world.
                    var worldField=Class.forName("com.terraformersmc.terrestria.surface.builders.TerrestriaSurfaceBuilders")
                            .getDeclaredField("OVERWORLD");
                    worldField.setAccessible(true);var savedWorld=worldField.get(null);worldField.set(null,null);
                    try(var isolated=new PredictionSurfaceStage(javaContext,(pos,chunk)->{
                        for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                            int bx=pos.getMinBlockX()+x,bz=pos.getMinBlockZ()+z;
                            var raw=generator.getBaseColumn(bx,bz,heights,random);
                            for(int y=level.getMinY();y<=level.getMaxY();y++) {
                                var state=raw.getBlock(y);
                                if(!state.isAir())chunk.setBlockState(new BlockPos(bx,y,bz),state,0);
                            }
                        }
                    })) {
                        for(int i=0;i<8;i++)isolated.sample(i*4096-12000,i*2713-10000);
                        long failures=isolated.failures();
                        for(int i=0;i<8;i++)isolated.sample(i*4096-12000,i*2713-10000);
                        if(failures==0 || isolated.failures()!=failures)throw new IllegalStateException("Missing-world fallback repeats or was not exercised");
                        report.addProperty("missingWorldFallbackChunks",failures);
                        report.addProperty("missingWorldFallbackCached",true);
                    } finally {worldField.set(null,savedWorld);}
                } finally {samplers.values().forEach(ClientTerrainSampler::close);}
                report.addProperty("success",true);
            } catch(Throwable failure) {
                report.addProperty("success",false);report.addProperty("error",failure.toString());failure.printStackTrace();
            } finally {
                try{Files.writeString(Path.of("runtime-hooks-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}
                catch(Exception failure){failure.printStackTrace();}
                server.halt(false);
            }
        });
    }
}
