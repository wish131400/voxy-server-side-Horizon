package dev.xantha.vss.client;

import dev.xantha.vss.common.VSSLogger;

public final class VSSSodiumOptionsEventBridge {
    private VSSSodiumOptionsEventBridge() {
    }

    public static void register() {
        boolean registered = false;

        if (VSSVoxyOptionsIntegration.registerSodium08ConfigBridge()) {
            registered = true;
        }

        if (VSSVoxyOptionsIntegration.registerSodiumOptionsApiBridge()) {
            registered = true;
        }

        if (VSSVoxyOptionsIntegration.hasLegacyOptions()) {
            // The optional constructor hook also covers installs without Options API.
            VSSLogger.info("Using legacy Sodium screen hook for VSS options");
            registered = true;
        }

        if (!registered && VSSVoxyOptionsIntegration.isSodiumPresent()) {
            VSSLogger.info("Sodium is present, but no compatible VSS options UI bridge was found");
        }
    }
}
