package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.api.VoxelColumnData;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionCaptureGroundTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void drain() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }
    private static boolean modern() { return System.getProperty("vss.minecraftTarget", "").startsWith("26."); }
    private static ClientColumnSample sample(int flags) {
        var s = PredictionVoxelFixtures.sample(66);
        return new ClientColumnSample(s.surfaceY(), s.fluidY(), s.biomeIndex(), s.topBlockIndex(),
                0, 0, 0, 0, 0, flags, 0, s.underBlockIndex(), s.deepBlockIndex(),
                s.surfaceBottom(), s.lowerTop(), s.lowerBottom(), s.spanFloor());
    }
    private static final int CURRENT = ClientColumnSample.FLAG_CAPTURED | ClientColumnSample.FLAG_CAPTURE_GROUND_V2;

    @Test void realGrassTopKeepsItsHeightAndMaterialBelowTreesAndBuildings() {
        var section = ClientCaptureExtractorTest.section();
        for (int z=0; z<16; z++) for (int x=0; x<16; x++) {
            section.setBlockState(x,0,z,Blocks.COARSE_DIRT.defaultBlockState());
            section.setBlockState(x,1,z,Blocks.GRASS_BLOCK.defaultBlockState());
        }
        section.setBlockState(8,3,8,Blocks.OAK_LOG.defaultBlockState());
        section.setBlockState(8,5,8,Blocks.OAK_LEAVES.defaultBlockState());
        section.setBlockState(8,7,8,Blocks.STONE_BRICKS.defaultBlockState());
        var data = new VoxelColumnData(new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(4,section,null,null)},1L,true);
        var sampler = new ClientTerrainSampler(42,PredictionDecorationQueryTest.PROFILE) {
            @Override public ClientColumnSample samplePreview(int x,int z,int step) { return PredictionCaptureGroundTest.sample(0); }
        };
        var columns = ClientCaptureExtractor.extractAll(0,0,data,sampler);
        for (var column : columns) {
            assertEquals(66,column.surfaceY());
            assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK),column.topBlockIndex());
            assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.COARSE_DIRT),column.underBlockIndex());
            assertEquals(CURRENT,column.flags() & CURRENT);
        }
        assertFalse(ClientCaptureExtractor.isGround(Blocks.OAK_LEAVES.defaultBlockState()));
        assertFalse(ClientCaptureExtractor.isGround(Blocks.OAK_LOG.defaultBlockState()));
        assertFalse(ClientCaptureExtractor.isGround(Blocks.STONE_BRICKS.defaultBlockState()));
        var capture = new PredictionCapturedTerrain(null);
        capture.put(0,0,columns);
        var mip = capture.sample(0,0,16,sample(0));
        assertEquals(66,mip.surfaceY());
        assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK),mip.topBlockIndex());
        assertFalse(PredictionCaptureGround.obsolete(mip));
    }

    @Test void releaseTagsDemonstrateTheGrassSplitAndModdedSubstratesRemainGround() throws Exception {
        if (!modern()) return;
        try (var in = Blocks.class.getResourceAsStream("/data/minecraft/tags/block/dirt.json")) {
            assertNotNull(in);
            var dirt = com.google.gson.JsonParser.parseReader(new InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(dirt.getAsJsonArray("values").asList().contains(new com.google.gson.JsonPrimitive("minecraft:grass_block")));
        }
        var modded = org.mockito.Mockito.mock(net.minecraft.world.level.block.state.BlockState.class);
        org.mockito.Mockito.when(modded.is(org.mockito.ArgumentMatchers.<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>>any()))
                .thenAnswer(call -> ((net.minecraft.tags.TagKey<?>)call.getArgument(0)).location().getPath().equals("substrate_overworld"));
        assertTrue(PredictionCaptureGround.includes(modded));
    }

    @Test void sampleStoreRemovesOnlyObsoleteCapturesOnReopen() {
        var file = directory.resolve("samples.smp");
        try (var store = new PredictionSampleStore(file,77)) {
            store.put(1,sample(ClientColumnSample.FLAG_CAPTURED));
            store.put(2,sample(CURRENT));
            store.put(3,sample(0));
        }
        for (int reopen=0; reopen<2; reopen++) try (var store = new PredictionSampleStore(file,77)) {
            assertEquals(modern() ? null : sample(ClientColumnSample.FLAG_CAPTURED),store.get(1));
            assertEquals(sample(CURRENT),store.get(2));
            assertEquals(sample(0),store.get(3));
        }
    }

    @Test void leafAndSummaryNodesMigrateWithoutBlessingLegacyMaterial() throws Exception {
        for (int flags : new int[]{ClientColumnSample.FLAG_CAPTURED,CURRENT}) {
            var columns = new ClientColumnSample[256]; Arrays.fill(columns,sample(flags));
            var leaf = new PredictionCapturedTerrain.Node(columns,null);
            var parent = new PredictionCapturedTerrain.Node(null,new PredictionCapturedTerrain.Summary[]{leaf.summary,null,null,null});
            for (var node : List.of(leaf,parent)) {
                var bytes = new ByteArrayOutputStream(); node.write(new DataOutputStream(bytes),null);
                var in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
                if (modern() && flags != CURRENT) assertThrows(IOException.class,()->PredictionCapturedTerrain.Node.read(in,null));
                else {
                    var restored = PredictionCapturedTerrain.Node.read(in,null);
                    var mip = restored.summary.sample(sample(0),256);
                    assertEquals(flags & ClientColumnSample.FLAG_CAPTURE_GROUND_V2,mip.flags() & ClientColumnSample.FLAG_CAPTURE_GROUND_V2);
                    assertFalse(PredictionCaptureGround.obsolete(mip));
                }
            }
        }
    }

    @Test void terrainMigrationAllowsSmallerRepairAndPreservesGeneratedRecords() {
        var key = PredictionDiskCache.Key.terrain(0,0,0);
        try (var disk = new PredictionDiskCache(directory,77); var lease = disk.lease(key)) {
            var oldFine = new ClientColumnSample[66*66]; Arrays.fill(oldFine,sample(ClientColumnSample.FLAG_CAPTURED));
            assertTrue(disk.writeTerrain(lease,oldFine));
            assertEquals(!modern(),disk.readTerrain(lease,0)!=null);
            if (modern()) {
                var repaired = new ClientColumnSample[10*10]; Arrays.fill(repaired,sample(CURRENT));
                assertTrue(disk.writeTerrain(lease,repaired),"obsolete fine grid must not block a coarser repair");
                assertArrayEquals(repaired,disk.readTerrain(lease,0));
            }
            var generated = PredictionDiskCache.Key.terrain(1,0,0);
            try (var other = disk.lease(generated)) {
                assertTrue(disk.writeTerrain(other,new ClientColumnSample[]{sample(0)}));
                assertEquals(sample(0),disk.readTerrain(other,1)[0]);
            }
        }
    }

    @Test void voxelCacheRejectsLegacyCaptureDerivedGeometryButKeepsFreshAndGeneratedGeometry() throws Exception {
        var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
        var diskKey = PredictionDiskCache.Key.voxels(0,0,0);
        for (int flags : new int[]{0,ClientColumnSample.FLAG_CAPTURED,ClientColumnSample.FLAG_PARENT_LOD,
                CURRENT,ClientColumnSample.FLAG_PARENT_LOD | ClientColumnSample.FLAG_CAPTURE_GROUND_V2}) {
            var tile = PredictionVoxelCacheTest.tile(key,8,true);
            Arrays.fill(tile.samples(),sample(flags));
            try (var disk = new PredictionDiskCache(directory.resolve("flags-"+flags),77);var lease = disk.lease(diskKey)) {
                assertTrue(disk.writeVoxelsLater(lease,tile,3,new byte[32]).get(10,TimeUnit.SECONDS));
                boolean obsolete = modern() && (flags==ClientColumnSample.FLAG_CAPTURED || flags==ClientColumnSample.FLAG_PARENT_LOD);
                assertEquals(!obsolete,disk.readVoxels(lease,key,3,new byte[32])!=null);
            }
        }
    }

    @Test void newParentReductionsCarryRevisionEvenWhenTheirInputWasEntirelyGenerated() {
        var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,1);
        var children = PredictionParentLodTest.children(key);
        var input = PredictionParentLod.capture(key,8,PredictionParentLodTest.LAYOUT,children,Set.of(),Map.of());
        var result = PredictionParentLod.reduce(input);
        assertNotNull(result);
        for (var s : result.samples()) {
            assertNotEquals(0,s.flags() & ClientColumnSample.FLAG_CAPTURE_GROUND_V2);
            assertFalse(PredictionCaptureGround.obsolete(s));
        }
    }
}
