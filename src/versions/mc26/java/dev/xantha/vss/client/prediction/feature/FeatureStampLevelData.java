package dev.xantha.vss.client.prediction.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelData;

final class FeatureStampLevelData implements LevelData {
    @Override public LevelData.RespawnData getRespawnData() { return LevelData.RespawnData.DEFAULT; }
    @Override public long getGameTime() { return 0L; }
    @Override public boolean isHardcore() { return false; }
    @Override public Difficulty getDifficulty() { return Difficulty.NORMAL; }
    @Override public boolean isDifficultyLocked() { return false; }
}