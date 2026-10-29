package dev.xantha.vss.platform.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Version boundary for showing a configuration screen. */
public final class ScreenNavigation {
    private ScreenNavigation() {
    }

    public static void show(Minecraft client, Screen screen) {
        client.setScreen(screen);
    }
}
