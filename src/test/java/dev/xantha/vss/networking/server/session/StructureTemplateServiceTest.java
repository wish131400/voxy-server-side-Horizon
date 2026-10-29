package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.common.worldgen.StructureTemplateDigest;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.StructureTemplateRequestC2SPayload;
import dev.xantha.vss.networking.payloads.StructureTemplateFragmentS2CPayload;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StructureTemplateServiceTest {
    @BeforeAll static void bootstrap() { WorldgenOptionalSnapshotTest.bootstrap(); }
    @AfterEach void clear() { StructureTemplateService.clear(); WorldgenTemplateCatalog.clear(); }

    @Test void serverSendsOnlyOneFragmentUntilEncodingCompletesAndHonorsBackpressureAndLogout() throws Exception {
        byte[] data = new byte[2 * StructureTemplateDigest.FRAGMENT_BYTES + 7];
        new java.util.Random(42).nextBytes(data);
        var id = Identifier.parse("mod:house");
        var opened = new CountDownLatch(1);
        var resources = mock(ResourceManager.class);
        when(resources.listResources(eq("structure"), any())).thenReturn(Map.of(
                id.withPath("structure/house.nbt"), new Resource(null, () -> {
                    opened.countDown();
                    return new ByteArrayInputStream(data);
                })));
        var catalog = WorldgenTemplateCatalog.snapshot(resources);
        var request = new StructureTemplateRequestC2SPayload(catalog.identity(), id, StructureTemplateDigest.hash(data));
        var server = mock(MinecraftServer.class);
        var players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        var player = mock(ServerPlayer.class);
        UUID uuid = UUID.randomUUID(); when(player.getUUID()).thenReturn(uuid);
        var serverField = ServerPlayer.class.getField("server"); serverField.setAccessible(true); serverField.set(player, server);
        when(players.getPlayer(uuid)).thenReturn(player);
        var sent = new ArrayList<StructureTemplateFragmentS2CPayload>();
        var encoded = new java.util.ArrayDeque<Runnable>();
        try (var network = mockStatic(VSSNetworking.class)) {
            network.when(() -> VSSNetworking.canSendQueuedToPlayer(player)).thenReturn(false);
            network.when(() -> VSSNetworking.sendQueuedToPlayer(eq(player), any(), any())).thenAnswer(call -> {
                sent.add(call.getArgument(1)); encoded.add(call.getArgument(2)); return null;
            });
            StructureTemplateService.handle(player, request);
            assertTrue(opened.await(2, TimeUnit.SECONDS));
            StructureTemplateService.tick(server);
            assertTrue(sent.isEmpty());
            network.when(() -> VSSNetworking.canSendQueuedToPlayer(player)).thenReturn(true);
            await(() -> { StructureTemplateService.tick(server); return sent.size() == 1; });
            StructureTemplateService.tick(server);
            assertEquals(1, sent.size(), "Unencoded fragments must not accumulate on Netty");
            assertEquals(0, sent.get(0).offset());
            encoded.remove().run(); StructureTemplateService.tick(server);
            assertEquals(2, sent.size());
            encoded.remove().run(); StructureTemplateService.tick(server);
            assertEquals(3, sent.size()); assertEquals(7, sent.get(2).data().length);
            encoded.remove().run(); StructureTemplateService.tick(server);
            assertEquals(3, sent.size());
            byte[] assembled = new byte[data.length];
            sent.forEach(fragment -> System.arraycopy(fragment.data(), 0, assembled, fragment.offset(), fragment.data().length));
            assertArrayEquals(data, assembled);
            StructureTemplateService.handle(player, request);
            await(() -> { StructureTemplateService.tick(server); return sent.size() == 4; });
            StructureTemplateService.forgetPlayer(uuid);
            encoded.remove().run(); StructureTemplateService.tick(server);
            assertEquals(4, sent.size(), "Logout cancels the remaining resource");
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for template I/O");
            Thread.sleep(5);
        }
    }
}
