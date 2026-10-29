package dev.xantha.vss.mixin.minecraft;

import dev.xantha.vss.common.BlockEntityTickerCompactor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class LevelChunkClearBlockEntitiesMixin {
    @Shadow
    @Final
    private static TickingBlockEntity NULL_TICKER;

    @Shadow
    @Final
    private Level level;

    @Shadow
    @Final
    private Map<BlockPos, ?> tickersInLevel;

    @Shadow
    public abstract Map<BlockPos, BlockEntity> getBlockEntities();

    @Inject(method = "clearAllBlockEntities", at = @At("HEAD"), cancellable = true)
    private void vss$clearAllBlockEntitiesWithoutConcurrentModification(CallbackInfo ci) {
        Map<BlockPos, BlockEntity> blockEntities = getBlockEntities();
        List<BlockEntity> blockEntitySnapshot = new ArrayList<>(blockEntities.values());
        for (BlockEntity blockEntity : blockEntitySnapshot) {
            if (blockEntity != null) {
                blockEntity.setRemoved();
            }
        }
        blockEntities.clear();

        List<?> tickerSnapshot = new ArrayList<>(tickersInLevel.values());
        for (Object ticker : tickerSnapshot) {
            vss$rebindTicker(ticker);
        }
        if (!tickerSnapshot.isEmpty()) {
            BlockEntityTickerCompactor.request(level);
        }
        tickersInLevel.clear();
        ci.cancel();
    }

    @Unique
    private static void vss$rebindTicker(Object ticker) {
        if (ticker == null) {
            return;
        }
        ((LevelChunkRebindableTickerAccessor) ticker).vss$rebind(NULL_TICKER);
    }
}
