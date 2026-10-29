package dev.xantha.vss.platform.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Minecraft 26.2+ owns screen transitions in its GUI manager. */
public final class ScreenNavigation {
    private ScreenNavigation() {
    }

    public static void show(Minecraft client, Screen screen) {
        client.gui.setScreen(screen);
    }
}
