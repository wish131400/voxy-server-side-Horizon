package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionNetherDecorationTest {
    @AfterEach void finishBackgroundCloseBeforeTempCleanup() throws Exception {
        PredictionCacheTestFiles.awaitBackgroundClose();
    }

    static HolderLookup.Provider lookup;
    static Registry<Biome> biomes;
    static RegistryAccess access;
    static Map<TagKey<Block>, List<Holder<Block>>> previousTags;
    @TempDir java.nio.file.Path directory;

    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(h -> registry.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        biomes = registry.freeze();
        access = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
        previousTags = BuiltInRegistries.BLOCK.getTags().collect(Collectors.toMap(p -> p.key(), p -> p.stream().toList()));
        var tags = new HashMap<>(previousTags);
        tags.put(BlockTags.NYLIUM, holders(Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM));
        tags.put(BlockTags.REPLACEABLE_BY_TREES, holders(Blocks.AIR, Blocks.CRIMSON_ROOTS, Blocks.WARPED_ROOTS, Blocks.NETHER_SPROUTS));
        tags.put(BlockTags.SOUL_FIRE_BASE_BLOCKS, holders(Blocks.SOUL_SAND, Blocks.SOUL_SOIL));
        PredictionTestTags.bind(tags);
    }
    @AfterAll static void restore() { PredictionTestTags.bind(previousTags); }
    static List<Holder<Block>> holders(Block... blocks) {
        return Arrays.stream(blocks).map(b -> (Holder<Block>)BuiltInRegistries.BLOCK.get(id(b)).orElseThrow()).toList();
    }
    static int id(Block block) { return BuiltInRegistries.BLOCK.getId(block); }
    static com.google.gson.JsonObject nativeDocument(String biome) throws Exception {
        var root=java.nio.file.Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen");
        var doc=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve("nether.json"))).getAsJsonObject();
        var ops = lookup.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        doc.add("settings", NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops,
                lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER).value()).getOrThrow());
        doc.add("density_functions", encodeRegistry(Registries.DENSITY_FUNCTION,
                net.minecraft.world.level.levelgen.DensityFunctions.DIRECT_CODEC));
        doc.add("noises", encodeRegistry(Registries.NOISE,
                net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters.DIRECT_CODEC));
        doc.add("biomes", encodeRegistry(Registries.BIOME, Biome.DIRECT_CODEC));
        doc.add("configured_features", encodeRegistry(Registries.CONFIGURED_FEATURE,
                net.minecraft.world.level.levelgen.feature.ConfiguredFeature.DIRECT_CODEC));
        doc.add("placed_features", encodeRegistry(Registries.PLACED_FEATURE,
                net.minecraft.world.level.levelgen.placement.PlacedFeature.DIRECT_CODEC));
        doc.add("block_definitions", RustWorldgenDocument.blockDefinitions());
        for (String map : List.of("grass", "foliage")) doc.add(map + "_colormap",
                com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve(map + ".json"))));
        doc.add("biome_source",com.google.gson.JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\""+biome+"\"}"));
        var possible = new com.google.gson.JsonArray(); possible.add(biome); doc.add("possible_biomes", possible);
        return doc;
    }
    private static <T> com.google.gson.JsonObject encodeRegistry(ResourceKey<Registry<T>> key,
            com.mojang.serialization.Codec<T> codec) {
        var values = new com.google.gson.JsonObject();
        var ops = lookup.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        lookup.lookupOrThrow(key).listElements().forEach(holder -> values.add(holder.key().identifier().toString(),
                codec.encodeStart(ops, holder.value()).getOrThrow()));
        return values;
    }
    static PredictionColumnVolume cavern() {
        return new PredictionColumnVolume(new int[]{0,56,id(Blocks.NETHERRACK),0,
                80,86,id(Blocks.NETHERRACK),0,110,128,id(Blocks.NETHERRACK),0});
    }
    static ClientTerrainSampler sampler(ResourceKey<Biome> biome, boolean synthetic) {
        return sampler(biome, synthetic, access);
    }
    static ClientTerrainSampler sampler(ResourceKey<Biome> biome, boolean synthetic, RegistryAccess registries) {
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
        var generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(
                lookup.lookupOrThrow(Registries.BIOME).getOrThrow(biome)), settings);
        var random = RandomState.create(settings.value(), lookup.lookupOrThrow(Registries.NOISE), 42);
        var profile = new DimensionProfile(Level.NETHER.identifier(),42,0,256,"noise","minecraft:nether",1);
        return new ClientTerrainSampler(42, profile, generator, random, LevelHeightAccessor.create(0,256),32,null,registries) {
            @Override ClientColumnSample sampleInterior(int x, int z) {
                return synthetic ? resolveInterior(cavern().asSample(),x,z) : super.sampleInterior(x,z);
            }
            @Override public int surfaceY(int x,int z) { return synthetic ? 128 : super.surfaceY(x,z); }
        };
    }

    @Test void caveQueriesUseVolumeAndReuseColumns() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var terrain = new ClientTerrainSampler(42,new DimensionProfile(Level.NETHER.identifier(),42,0,256,"noise","minecraft:nether",1)) {
            @Override ClientColumnSample sampleInterior(int x,int z) { calls.incrementAndGet(); return cavern().asSample(); }
            @Override public ClientColumnSample sampleSurface(int x,int z) { fail("roof heightfield must not supply cave blocks"); return null; }
        };
        var cache = new VssLodSampleCache(512);
        for (int job=0;job<2;job++) {
            var level = new PredictionDecorationLevel(terrain,terrain,access,0,0,cache);
            for(int y=0;y<256;y++) assertEquals(cavern().occupied(y,true),!level.getBlockState(new BlockPos(1,y,1)).isAir());
            assertFalse(level.canSeeSky(new BlockPos(1,150,1)));
            level.useDisplayTerrain(true);
            assertTrue(level.getBlockState(new BlockPos(1,60,1)).isAir());
        }
        assertEquals(1,calls.get(),"multiple block queries and jobs reuse one materialized volume");
    }

    @Test void nativeAndJavaInteriorColumnsUseTheSameSurfaceRules() throws Exception {
        assertTrue(RustTerrainSampler.available());
        var doc=nativeDocument("minecraft:crimson_forest");
        var javaTerrain=sampler(Biomes.CRIMSON_FOREST,false);
        try(var nativeTerrain=new RustTerrainSampler(RustWorldgenBackend.create(42,0,doc.toString()),javaTerrain.profile(),javaTerrain)) {
            long nativeNanos=0,javaNanos=0,bytes=0;
            for(int z=-2;z<2;z++) for(int x=-2;x<2;x++) {
                long start=System.nanoTime(); var nativeColumn=nativeTerrain.sampleInterior(x*47,z*53); nativeNanos+=System.nanoTime()-start;
                start=System.nanoTime(); var javaColumn=javaTerrain.sampleInterior(x*47,z*53); javaNanos+=System.nanoTime()-start;
                assertEquals(javaColumn.volume(),nativeColumn.volume(),"both backends need the same coated cave geometry");
                bytes+=nativeColumn.volume().bytes();
            }
            System.out.println("NETHER_COATED columns=16 nativeMs="+nativeNanos/1e6+" javaMs="+javaNanos/1e6+" retainedBytes="+bytes);
        }
    }

    @Test void actualNetherSurfaceRulesCoatEveryFloorAndKeepCaves() {
        for (var biome : List.of(Biomes.CRIMSON_FOREST,Biomes.WARPED_FOREST)) {
            var terrain = sampler(biome,true);
            Block nylium = biome == Biomes.CRIMSON_FOREST ? Blocks.CRIMSON_NYLIUM : Blocks.WARPED_NYLIUM;
            int floors=0,upperFloors=0;
            long start=System.nanoTime();
            for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                var volume=terrain.sampleInterior(x,z).volume();
                assertEquals(ClientColumnSample.NO_BLOCK,volume.blockAt(60));
                assertEquals(ClientColumnSample.NO_BLOCK,volume.blockAt(90));
                assertFalse(volume.occupied(60,false)); assertFalse(volume.occupied(90,false));
                if(volume.blockAt(55)==id(nylium)) floors++;
                if(volume.blockAt(85)==id(nylium)) upperFloors++;
                assertNotEquals(id(nylium),volume.blockAt(127),"roof is not a fungus floor");
            }
            System.out.println("NETHER_SURFACE biome="+biome.identifier()+" columns=256 ms="+(System.nanoTime()-start)/1e6+" floors="+floors+" upper="+upperFloors);
            assertTrue(floors>0); assertTrue(upperFloors>0);
        }
    }

    @Test void registeredFungiAndRootsGenerateBelowRoofAndPersist() throws Exception {
        for(var biome:List.of(Biomes.CRIMSON_FOREST,Biomes.WARPED_FOREST)) {
            var terrain=sampler(biome,true);
            var path=directory.resolve(biome.identifier().getPath());
            var expected=new HashMap<Integer,Map<BlockPos,BlockState>>();
            long start=System.nanoTime();
            int stems=0,plants=0;
            try(var cache=new PredictionDiskCache(path,42)) {
                var vegetation=new PredictionVegetation(terrain,cache);
                for(int x=0;x<4;x++) {
                    var blocks=vegetation.chunk(x,0); expected.put(x,blocks);
                    for(var e:blocks.entrySet()) {
                        assertTrue(e.getKey().getY()<128,"interior decorations must not migrate to the roof");
                        if(e.getValue().is(Blocks.CRIMSON_STEM)||e.getValue().is(Blocks.WARPED_STEM)) stems++;
                        if(e.getValue().is(Blocks.CRIMSON_ROOTS)||e.getValue().is(Blocks.WARPED_ROOTS)||e.getValue().is(Blocks.NETHER_SPROUTS)) plants++;
                    }
                }
                System.out.println("NETHER_DECORATION biome="+biome.identifier()+" chunks=4 ms="+(System.nanoTime()-start)/1e6+" stems="+stems+" plants="+plants+" "+vegetation.diagnostics());
                var types=new java.util.TreeSet<String>();
                expected.values().forEach(m->m.values().forEach(s->types.add(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString())));
                System.out.println("NETHER_FEATURE_BLOCKS "+types);
                cache.flush();
            }
            assertTrue(stems>0,"registered huge fungi must survive placement and extraction");
            assertTrue(plants>0,"registered ground vegetation must survive extraction");
            try(var cache=new PredictionDiskCache(path,42)) {
                var restored=new PredictionVegetation(terrain,cache);
                for(int x=0;x<4;x++) assertEquals(expected.get(x),restored.chunk(x,0));
                assertTrue(restored.diagnostics().contains(",chunks=0,blocks=0,"));
            }
        }
    }

    @Test void interiorMaterialsMatchVanillaSurfacePassOnTheSameGeometry() {
        var settings=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
        var noStructures=new DensityFunctions.BeardifierOrMarker() {
            public double compute(DensityFunction.FunctionContext c){return 0;}
            public double minValue(){return 0;} public double maxValue(){return 0;}
        };
        for(var biome:List.of(Biomes.CRIMSON_FOREST,Biomes.WARPED_FOREST,Biomes.SOUL_SAND_VALLEY,Biomes.BASALT_DELTAS)) {
            var terrain=sampler(biome,true);
            var generator=terrain.generatorContext(); var random=terrain.randomStateContext();
            var heights=LevelHeightAccessor.create(0,256);
            var chunk=new net.minecraft.world.level.chunk.ProtoChunk(new ChunkPos(0,0),net.minecraft.world.level.chunk.UpgradeData.EMPTY,heights,net.minecraft.world.level.chunk.PalettedContainerFactory.create(new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(List.of(biomes))),null) {
                @Override public int getHeight(Heightmap.Types type,int x,int z){return 127;}
            };
            for(int x=0;x<16;x++) for(int z=0;z<16;z++) for(int y=0;y<128;y++)
                if(cavern().occupied(y,true)) chunk.setBlockState(new BlockPos(x,y,z),Blocks.NETHERRACK.defaultBlockState(),0);
            var fluid=new Aquifer.FluidStatus(32,Blocks.LAVA.defaultBlockState());
            var noise=NoiseChunk.forChunk(chunk,random,noStructures,settings.value(),(x,y,z)->fluid,net.minecraft.world.level.levelgen.blending.Blender.empty());
            random.surfaceSystem().buildSurface(random,new BiomeManager(
                    (x,y,z)->lookup.lookupOrThrow(Registries.BIOME).getOrThrow(biome),0),false,
                    new WorldGenerationContext(generator,heights),chunk,noise,settings.value().surfaceRule(),
                    generator.getBiomeSource().possibleBiomes());
            for(int x=0;x<16;x+=3) for(int z=0;z<16;z+=3) {
                var volume=terrain.sampleInterior(x,z).volume();
                for(int y=0;y<128;y++) {
                    var expected=chunk.getBlockState(new BlockPos(x,y,z));
                    assertEquals(expected.isAir()?ClientColumnSample.NO_BLOCK:id(expected.getBlock()),volume.blockAt(y),
                            "surface mismatch biome="+biome.identifier()+" xyz="+x+","+y+","+z);
                }
            }
        }
    }



    @Test void chunkFacingDecorationReadsAndWritesShareTransactions() {
        var terrain=sampler(Biomes.CRIMSON_FOREST,true);
        var level=new PredictionDecorationLevel(terrain,terrain,access,0,0);
        var chunk=level.getChunk(0,0,net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES,true);
        var pos=new BlockPos(2,60,2);
        level.beginStructure();
        chunk.setBlockState(pos,Blocks.NETHER_BRICKS.defaultBlockState(),0);
        assertEquals(chunk.getBlockState(pos),level.getBlockState(pos));
        chunk.markPosForPostProcessing(pos);
        level.endFeature(false);
        assertTrue(chunk.getBlockState(pos).isAir());
        assertTrue(level.placed().isEmpty());
        assertThrows(PredictionDecorationLevel.LocalPlacementFailure.class,
                ()->level.getChunk(3,0,net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES,true));
    }

    @Test void nativeRegisteredNetherDecorationsUseTheInteriorStage() throws Exception {
        assertTrue(RustTerrainSampler.available());
        var registry=new MappedRegistry<net.minecraft.world.level.levelgen.placement.PlacedFeature>(Registries.PLACED_FEATURE,Lifecycle.stable());
        lookup.lookupOrThrow(Registries.PLACED_FEATURE).listElements().forEach(h->registry.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));
        var registries=new RegistryAccess.ImmutableRegistryAccess(List.of(biomes,registry.freeze()));
        var states=new com.google.gson.JsonArray();
        for(var block:BuiltInRegistries.BLOCK) for(var state:block.getStateDefinition().getPossibleStates())
            states.add(RustWorldgenDocument.encodeState(state));
        var features=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
                "tools/rust/vss-native-core/tests/fixtures/worldgen/features.json"))).getAsJsonObject();
        for(var biome:List.of(Biomes.CRIMSON_FOREST,Biomes.WARPED_FOREST)) {
            var context=sampler(biome,false,registries);
            var doc=nativeDocument(biome.identifier().toString());
            doc.add("input_states",states);
            doc.add("configured_features",features.get("configured_features"));
            doc.add("placed_features",features.get("placed_features"));
            var possible=new com.google.gson.JsonArray();possible.add(biome.identifier().toString());doc.add("possible_biomes",possible);
            try(var nativeTerrain=new RustTerrainSampler(RustWorldgenBackend.create(42,0,doc.toString()),context.profile(),context)) {
                var vegetation=new PredictionVegetation(nativeTerrain,null,false);
                long start=System.nanoTime();
                var placed=new HashMap<BlockPos,BlockState>();
                for(int x=0;x<4;x++) placed.putAll(vegetation.chunk(x,0));
                long nativeNanos=System.nanoTime()-start;
                var javaVegetation=new PredictionVegetation(context,null,false);
                var expected=new HashMap<BlockPos,BlockState>();
                start=System.nanoTime();
                for(int x=0;x<4;x++) expected.putAll(javaVegetation.chunk(x,0));
                long javaNanos=System.nanoTime()-start;
                var positions=new HashSet<>(expected.keySet());positions.addAll(placed.keySet());
                for(var at:positions) assertEquals(expected.get(at),placed.get(at),"mixed native/Java stream at "+at+" biome="+biome.identifier());
                assertTrue(placed.entrySet().stream().anyMatch(e->e.getKey().getY()<120&&PredictionVegetation.renderable(e.getValue())),vegetation.diagnostics());
                assertFalse(nativeTerrain.diagnostics().contains("nativeFeatures=0,"),nativeTerrain.diagnostics());
                System.out.println("NETHER_NATIVE_DECORATION biome="+biome.identifier()+" chunks=4 nativeMs="+nativeNanos/1e6+" javaMs="+javaNanos/1e6
                        +" "+vegetation.diagnostics()+" "+nativeTerrain.diagnostics());
            }
        }
    }
}
