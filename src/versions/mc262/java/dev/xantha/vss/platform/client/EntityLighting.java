package dev.xantha.vss.platform.client;

import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;

/** Minecraft 26.2+ moved the level lighting query into the shared light utility. */
public final class EntityLighting {
    private EntityLighting() {
    }

    public static int at(Entity entity) {
        return LightCoordsUtil.getLightCoords(entity.level(), entity.blockPosition());
    }
}
