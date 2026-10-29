package dev.xantha.vss.platform.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;

/** Keeps the Minecraft lighting entry point outside the far-player state machine. */
public final class EntityLighting {
    private EntityLighting() {
    }

    public static int at(Entity entity) {
        return LevelRenderer.getLightCoords(entity.level(), entity.blockPosition());
    }
}
