package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ByePregen 1.1.3.5 changed predicate reads to LevelAccessor and one offset vector. */
@Pseudo
@Mixin(targets = "com.moepus.byepregen.worldgen.feature.FastBlockPredicateOptimizer", remap = false)
public abstract class ByePregenPredictionPredicateMixin {
    @Inject(method = "getState(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void vss$virtualTerrainPredicate(LevelAccessor level, BlockPos pos, Vec3i offset,
                                                   CallbackInfoReturnable<BlockState> ci) {
        if (level instanceof FeatureStampLevel stamp) {
            ci.setReturnValue(stamp.predicateState(pos, offset.getX(), offset.getY(), offset.getZ()));
        }
    }
}
