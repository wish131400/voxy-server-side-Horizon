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

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
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
        ClientLoginConnectionEvents.INIT.register((handler, client) -> ClientConnectionIdentity.endSession());
        ClientLoginConnectionEvents.DISCONNECT.register((handler, client) -> ClientConnectionIdentity.endSession());
        ClientPlayConnectionEvents.INIT.register((handler, client) -> VSSClientNetworking.onClientLogin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (previousLevel != null) VSSClientNetworking.onClientLevelUnload(previousLevel);
            previousLevel = null;
            VSSClientNetworking.onClientLogout();
        });
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (client) VSSClientNetworking.onTagsUpdated();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (previousLevel != client.level) {
                if (previousLevel != null) VSSClientNetworking.onClientLevelUnload(previousLevel);
                previousLevel = client.level;
            }
            VSSClientNetworking.onClientTick();
            FarPlayerClientRenderer.onClientTick();
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            VSSClientNetworking.onRenderLevel(new Matrix4f(context.matrixStack().last().pose()),
                    new Matrix4f(context.projectionMatrix()), context.camera().getPosition());

        });
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            VSSClientNetworking.onAfterEntities();
            FarPlayerClientRenderer.onRenderLevel(context.matrixStack(), context.tickDelta(), context.camera().getPosition());
        });
        WorldRenderEvents.LAST.register(context -> VSSClientNetworking.onAfterLevel());
        HudRenderCallback.EVENT.register((graphics, delta) -> VSSClientNetworking.onPerformanceHud(graphics));
    }
}
