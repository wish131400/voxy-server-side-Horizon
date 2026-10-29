package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionTemplateAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.util.RandomSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** OTTYG/BWG tree templates use the same verified catalog as buildings. */
@Pseudo
@Mixin(targets = {
        "dev.corgitaco.ohthetreesyoullgrow.world.level.levelgen.feature.TreeFromStructureNBTFeature",
        "dev.corgitaco.ohthetreesyoullgrow.world.level.levelgen.feature.TreeFromStructureNBTFeatureV2"
}, remap = false)
public abstract class NbtTreePredictionTemplatesMixin {
    @Redirect(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/WorldGenLevel;getLevel()Lnet/minecraft/server/level/ServerLevel;", remap = true), remap = false)
    private ServerLevel vss$avoidLiveWorld(WorldGenLevel level) {
        // Only the immediately redirected getStructureTemplateManager consumes this receiver.
        return PredictionTemplateAccess.virtual(level) ? null : level.getLevel();
    }

    @Redirect(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getStructureTemplateManager()Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;", remap = true),
            remap = false)
    private StructureTemplateManager vss$verifiedTreeTemplates(ServerLevel level, WorldGenLevel world,
                                                               ChunkGenerator generator, RandomSource random, BlockPos origin) {
        return PredictionTemplateAccess.virtual(world)
                ? PredictionTemplateAccess.manager(world) : level.getStructureTemplateManager();
    }
}
