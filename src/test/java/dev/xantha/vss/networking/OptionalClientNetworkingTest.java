package dev.xantha.vss.networking;

import static org.mockito.Mockito.*;
import dev.xantha.vss.networking.payloads.HandshakeRequestS2CPayload;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OptionalClientNetworkingTest {
    @BeforeAll static void bootstrap() { dev.xantha.vss.testutil.MinecraftTestBootstrap.initialize(); }

    @Test void configurationIdentityDoesNotCreateATaskForClientsWithoutVss() {
        try (var registry = mockStatic(net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.class, RETURNS_DEEP_STUBS);
             var play = mockStatic(ServerPlayNetworking.class);
             var configuration = mockStatic(net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking.class);
             var identity = mockStatic(dev.xantha.vss.networking.server.ServerIdentityConfigurationTask.class)) {
            VSSNetworking.register();
            var listener = mock(net.minecraft.server.network.ServerConfigurationPacketListenerImpl.class);
            var server = mock(net.minecraft.server.MinecraftServer.class);
            var callback = net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents.CONFIGURE.invoker();
            callback.onSendConfiguration(listener, server);
            identity.verifyNoInteractions();
            configuration.when(() -> net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking.canSend(
                    listener, dev.xantha.vss.networking.payloads.ServerIdentityS2CPayload.TYPE)).thenReturn(true);
            callback.onSendConfiguration(listener, server);
            identity.verify(() -> dev.xantha.vss.networking.server.ServerIdentityConfigurationTask.send(listener));
        }
    }

    @Test void onlyPlayersAdvertisingVssReceiveThePlayHandshake() {
        var player = mock(ServerPlayer.class);
        try (var api = mockStatic(ServerPlayNetworking.class);
             var sending = mockStatic(VSSNetworking.class)) {
            VSSServerNetworking.onPlayerLogin(player);
            sending.verifyNoInteractions();
            api.when(() -> ServerPlayNetworking.canSend(player, HandshakeRequestS2CPayload.TYPE)).thenReturn(true);
            VSSServerNetworking.onPlayerLogin(player);
            sending.verify(() -> VSSNetworking.sendToPlayer(eq(player), any(HandshakeRequestS2CPayload.class)));
        }
    }
}
