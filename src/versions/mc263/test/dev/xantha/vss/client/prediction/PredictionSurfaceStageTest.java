package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionSurfaceStageTest {
    @BeforeAll static void bootstrap(){PredictionNetherDecorationTest.bootstrap();}
    @AfterAll static void restore(){PredictionNetherDecorationTest.restore();}
    @Test void sameChunkCoalescesAndOtherChunksProgressIndependently() throws Exception {
        var context=PredictionNetherDecorationTest.sampler(Biomes.CRIMSON_FOREST,false);
        var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var stage=new PredictionSurfaceStage(context,(pos,chunk)->{
            calls.incrementAndGet();
            if(pos.x()==0){entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new CancellationException();}}
            var cursor=new BlockPos.MutableBlockPos();
            for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=0;y<56;y++)
                chunk.setBlockState(cursor.set(pos.getMinBlockX()+x,y,pos.getMinBlockZ()+z),Blocks.NETHERRACK.defaultBlockState(),0);
        });var workers=Executors.newFixedThreadPool(3)) {
            var one=workers.submit(()->stage.column(0,0));assertTrue(entered.await(5,TimeUnit.SECONDS));
            var duplicate=workers.submit(()->stage.column(0,0));
            var other=workers.submit(()->stage.column(-1,-1));
            assertNotNull(other.get(5,TimeUnit.SECONDS));assertFalse(one.isDone());
            release.countDown();assertSame(one.get(5,TimeUnit.SECONDS),duplicate.get(5,TimeUnit.SECONDS));
            assertEquals(2,calls.get());assertEquals(0,stage.failures());
            assertTrue(stage.sample(0,0).hasSurface());
            assertEquals(56,stage.sample(0,0).surfaceY());
        } finally{release.countDown();context.close();}
    }
    @Test void closeRevokesPendingAndCompletedResults() {
        var context=PredictionNetherDecorationTest.sampler(Biomes.CRIMSON_FOREST,false);
        var stage=new PredictionSurfaceStage(context,(pos,chunk)->{});
        assertFalse(stage.sample(-1,0).hasSurface());stage.close();
        assertThrows(CancellationException.class,()->stage.column(-1,0));context.close();
    }
}
