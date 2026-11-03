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

    @Test void onlyPlayersAdvertisingVssReceiveThePlayHandshake() {
        var player = mock(ServerPlayer.class);
        try (var api = mockStatic(ServerPlayNetworking.class);
             var sending = mockStatic(VSSNetworking.class)) {
            VSSServerNetworking.onPlayerLogin(player);
            sending.verifyNoInteractions();
            api.when(() -> ServerPlayNetworking.canSend(player, HandshakeRequestS2CPayload.TYPE.id())).thenReturn(true);
            VSSServerNetworking.onPlayerLogin(player);
            sending.verify(() -> VSSNetworking.sendToPlayer(eq(player), any(HandshakeRequestS2CPayload.class)));
        }
    }
}
