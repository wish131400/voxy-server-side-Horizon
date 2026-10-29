package dev.xantha.vss.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.client.ClientConnectionIdentity;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import dev.xantha.vss.networking.client.VSSClientPacketHandlers;
import dev.xantha.vss.networking.payloads.*;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Calls the actual legacy registration APIs and dispatch callbacks without a live connection. */
class LegacyNetworkingTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void directionsCodecsAndReleasedBuffersKeepTheFullProtocolContract() throws Exception {
        var c2s = new HashMap<ResourceLocation, ServerPlayNetworking.PlayChannelHandler>();
        var s2c = new HashMap<ResourceLocation, ClientPlayNetworking.PlayChannelHandler>();
        var login = new AtomicReference<ClientLoginNetworking.LoginQueryRequestHandler>();
        try (var serverApi = mockStatic(ServerPlayNetworking.class);
             var serverLogin = mockStatic(ServerLoginNetworking.class);
             var clientApi = mockStatic(ClientPlayNetworking.class);
             var clientLogin = mockStatic(ClientLoginNetworking.class);
             var serverHandlers = mockStatic(VSSServerNetworking.class);
             var clientHandlers = mockStatic(VSSClientNetworking.class);
             var identity = mockStatic(ClientConnectionIdentity.class);
             var minecraft = mockStatic(Minecraft.class)) {
            serverApi.when(() -> ServerPlayNetworking.registerGlobalReceiver(any(ResourceLocation.class),
                    any(ServerPlayNetworking.PlayChannelHandler.class))).thenAnswer(call -> {
                assertNull(c2s.put(call.getArgument(0), call.getArgument(1))); return true;
            });
            clientApi.when(() -> ClientPlayNetworking.registerGlobalReceiver(any(ResourceLocation.class),
                    any(ClientPlayNetworking.PlayChannelHandler.class))).thenAnswer(call -> {
                assertNull(s2c.put(call.getArgument(0), call.getArgument(1))); return true;
            });
            clientLogin.when(() -> ClientLoginNetworking.registerGlobalReceiver(eq(ServerIdentityS2CPayload.TYPE.id()),
                    any(ClientLoginNetworking.LoginQueryRequestHandler.class))).thenAnswer(call -> {
                login.set(call.getArgument(1)); return true;
            });
            VSSNetworking.register();
            VSSClientPacketHandlers.registerFabricReceivers();
            assertEquals(Set.of("handshake", "batch_chunk_request", "cancel_request", "bandwidth_update",
                    "region_presence", "lost_city_hints_request", "structure_template_request"), paths(c2s.keySet()));
            assertEquals(Set.of("session_config", "batch_response", "dirty_columns", "voxel_column", "far_players",
                    "handshake_request", "worldgen_profile", "worldgen_profile_fragment", "lost_city_hints",
                    "structure_template_fragment"), paths(s2c.keySet()));
            serverLogin.verify(() -> ServerLoginNetworking.registerGlobalReceiver(eq(ServerIdentityS2CPayload.TYPE.id()),
                    any(ServerLoginNetworking.LoginQueryResponseHandler.class)));
            var codecsField = VSSNetworking.class.getDeclaredField("CODECS");
            codecsField.setAccessible(true);
            var codecs = (Map<?, ?>) codecsField.get(null);
            assertEquals(18, codecs.size());
            var all = new HashSet<>(c2s.keySet()); all.addAll(s2c.keySet()); all.add(ServerIdentityS2CPayload.TYPE.id());
            assertEquals(all, codecs.keySet());

            var server = mock(MinecraftServer.class);
            var player = mock(ServerPlayer.class);
            var serverTask = new AtomicReference<Runnable>();
            doAnswer(call -> { serverTask.set(call.getArgument(0)); return null; }).when(server).execute(any(Runnable.class));
            var bandwidth = new BandwidthUpdateC2SPayload(123456L);
            FriendlyByteBuf request = VSSNetworking.encode(bandwidth);
            c2s.get(bandwidth.type().id()).receive(server, player, null, request, null);
            assertEquals(0, request.readableBytes()); request.release();
            assertNotNull(serverTask.get()); serverTask.get().run();
            serverHandlers.verify(() -> VSSServerNetworking.handleBandwidthUpdate(bandwidth, player));

            var client = mock(Minecraft.class);
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            when(client.isSameThread()).thenReturn(true);
            var clientTask = new AtomicReference<Runnable>();
            doAnswer(call -> { clientTask.set(call.getArgument(0)); return null; }).when(client).execute(any(Runnable.class));
            var dirty = new DirtyColumnsS2CPayload(new long[]{23L}, new long[]{41L});
            FriendlyByteBuf update = VSSNetworking.encode(dirty);
            s2c.get(dirty.type().id()).receive(client, null, update, null);
            assertEquals(0, update.readableBytes()); update.release();
            assertNotNull(clientTask.get()); clientTask.get().run();
            clientHandlers.verify(() -> VSSClientNetworking.handleDirtyColumns(argThat(payload ->
                    Arrays.equals(dirty.dirtyPositions(), payload.dirtyPositions())
                            && Arrays.equals(dirty.dirtyTimestamps(), payload.dirtyTimestamps()))));

            var expected = new ServerIdentityS2CPayload("0123456789abcdef", false, "node", "");
            FriendlyByteBuf query = VSSNetworking.encode(expected);
            var answer = login.get().receive(client, null, query, null);
            assertEquals(0, query.readableBytes()); query.release();
            assertTrue(answer.isDone());
            var ack = answer.join(); assertEquals(0, ack.readableBytes()); ack.release();
            identity.verify(() -> ClientConnectionIdentity.acceptServerIdentity(expected));
        }
    }

    private static Set<String> paths(Set<ResourceLocation> ids) {
        var paths = new HashSet<String>();
        for (var id : ids) { assertEquals("vss", id.getNamespace()); paths.add(id.getPath()); }
        return paths;
    }
}
