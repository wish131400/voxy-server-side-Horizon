package dev.xantha.vss.client;

import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.client.prediction.PredictionTerrainBackends;
import dev.xantha.vss.client.prediction.TerraBlenderBackend;
import dev.xantha.vss.networking.client.FarPlayerClientRenderer;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import dev.xantha.vss.networking.client.VSSClientCommands;
import dev.xantha.vss.networking.client.VSSClientPacketHandlers;
import dev.xantha.vss.networking.client.ClientConnectionIdentity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

public final class VSSClientBootstrap implements ClientModInitializer {
    private ClientLevel previousLevel;

    @Override
    public void onInitializeClient() {
        dev.xantha.vss.api.VSSApi.bindClientSession(
                new dev.xantha.vss.networking.client.ClientLodSessionAdapter());
        VSSClientPacketHandlers.registerFabricReceivers();
        VSSSodiumOptionsEventBridge.register();
        VSSEmbeddiumOptionsEventBridge.register();
        ModCompat.init();
        PredictionTerrainBackends.register(new TerraBlenderBackend());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                VSSClientCommands.registerCommands(dispatcher));
        // A login can fail before PLAY exists. Clear both active and pending identity
        // at configuration boundaries so the next server cannot inherit its cache.
        ClientConfigurationConnectionEvents.INIT.register((handler, client) -> ClientConnectionIdentity.endSession());
        ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> ClientConnectionIdentity.endSession());
        ClientPlayConnectionEvents.INIT.register((handler, client) -> VSSClientNetworking.onClientLogin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (previousLevel != null) VSSClientNetworking.onClientLevelUnload(previousLevel);
            previousLevel = null;
            VSSClientNetworking.onClientLogout();
        });
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, world) -> {
            if (previousLevel != null && previousLevel != world) VSSClientNetworking.onClientLevelUnload(previousLevel);
            previousLevel = world;
        });
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (client) VSSClientNetworking.onTagsUpdated();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            VSSClientNetworking.onClientTick();
            FarPlayerClientRenderer.onClientTick();
        });
        LevelRenderEvents.END_EXTRACTION.register(context -> {
            VSSClientNetworking.onRenderLevel(new Matrix4f(context.levelState().cameraRenderState.viewRotationMatrix),
                    new Matrix4f(context.levelState().cameraRenderState.projectionMatrix), context.camera().position());
            FarPlayerClientRenderer.extractRenderStates(context);
        });
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(context -> {
            VSSClientNetworking.onAfterEntities();
        });
        LevelRenderEvents.END_MAIN.register(context -> VSSClientNetworking.onAfterLevel());
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("vss", "performance"),
                (graphics, delta) -> VSSClientNetworking.onPerformanceHud(graphics));
    }
}
