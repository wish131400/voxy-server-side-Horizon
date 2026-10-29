package dev.xantha.vss.client;

import dev.xantha.vss.config.VSSServerConfig;
import net.minecraft.client.gui.screens.Screen;

public final class VSSClientConfigScreens {
    private VSSClientConfigScreens() { }

    public static Screen createConfigScreen(Screen parent) {
        Screen sodiumScreen = VSSVoxyOptionsIntegration.createSodiumConfigScreen(parent);
        return sodiumScreen != null ? sodiumScreen : new VSSServerConfigScreen(parent, VSSServerConfig.CONFIG);
    }
}
