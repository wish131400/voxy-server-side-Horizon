package dev.xantha.vss.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Optional Mod Menu integration, instantiated only by Mod Menu's entrypoint. */
public final class VSSModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return VSSClientConfigScreens::createConfigScreen;
    }
}
