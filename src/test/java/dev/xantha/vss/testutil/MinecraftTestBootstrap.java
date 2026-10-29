package dev.xantha.vss.testutil;

/** Initializes vanilla registries without starting a game or opening a world. */
public final class MinecraftTestBootstrap {
    private MinecraftTestBootstrap() { }
    public static synchronized void initialize() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
}
