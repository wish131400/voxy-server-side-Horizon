package dev.xantha.vss.networking.server;

import dev.xantha.vss.networking.payloads.ServerIdentityS2CPayload;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

/** Sends the identity during configuration; no acknowledgement or blocking task is needed. */
public final class ServerIdentityConfigurationTask {
    private ServerIdentityConfigurationTask() { }

    public static void send(ServerConfigurationPacketListenerImpl listener) {
        ServerConfigurationNetworking.send(listener, ServerIdentityS2CPayload.fromConfig(
                dev.xantha.vss.api.column.ColumnFilters.snapshot().cacheKey()));
    }
}
