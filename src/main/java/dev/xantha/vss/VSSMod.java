package dev.xantha.vss;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.networking.server.broadcast.FarPlayerBroadcaster;
import dev.xantha.vss.networking.server.command.VSSServerCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Common entry point. Client entry points are loaded separately by Fabric. */
public final class VSSMod implements ModInitializer {
    @Override
    public void onInitialize() {
        dev.xantha.vss.common.worldgen.TectonicConfigNoise.register();
        VSSLogger.configureDebug(() -> dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging
                || dev.xantha.vss.config.VSSServerConfig.CONFIG.debugLogging);
        VSSNetworking.register();
        dev.xantha.vss.compat.ftbchunks.FTBChunksForceLoadCompat.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                VSSServerCommands.register(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                VSSServerNetworking.onPlayerLogin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                VSSServerNetworking.onPlayerLogout(handler.player));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                VSSServerNetworking.onPlayerChangedDimension(player));
        ServerLifecycleEvents.SERVER_STARTING.register(VSSServerNetworking::onServerStarting);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) VSSServerNetworking.onWorldgenDataReload(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            VSSServerNetworking.onServerTick(server);
            FarPlayerBroadcaster.onServerTick(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            FarPlayerBroadcaster.onServerStopping(server);
            VSSServerNetworking.onServerStopping(server);
        });
    }
}
