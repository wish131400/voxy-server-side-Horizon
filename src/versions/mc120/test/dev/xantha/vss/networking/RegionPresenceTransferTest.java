package dev.xantha.vss.networking;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RegionPresenceTransferTest {
    @BeforeAll static void bootstrap() {
        dev.xantha.vss.testutil.MinecraftTestBootstrap.initialize();
    }
    @Test void incompressibleFullRegionsStayWithinServerboundLimitAndResetOnlyOnce() {
        List<RegionPresenceC2SPayload.RegionEntry> input = new ArrayList<>();
        Random random = new Random(47L);
        for (int r = 0; r < 8; r++) {
            int[] slots = new int[1024]; long[] timestamps = new long[1024]; long[] bitmap = new long[16];
            java.util.Arrays.fill(bitmap, -1L);
            for (int i = 0; i < slots.length; i++) { slots[i] = i; timestamps[i] = random.nextLong(); }
            input.add(new RegionPresenceC2SPayload.RegionEntry(r, -r, bitmap, slots, timestamps, slots.length));
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("horizon", "x".repeat(240)));
        List<RegionPresenceC2SPayload> packets = new ArrayList<>();
        RegionPresenceTransfer.send(dimension, true, input, false, packets::add);
        assertTrue(packets.size() > 1);
        List<RegionPresenceC2SPayload.RegionEntry> restored = new ArrayList<>();
        for (int i = 0; i < packets.size(); i++) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                RegionPresenceC2SPayload.encode(packets.get(i), buffer);
                assertTrue(buffer.readableBytes() < 32767, "Vanilla serverbound payload limit");
                var decoded = RegionPresenceC2SPayload.decode(buffer);
                assertEquals(i == 0, decoded.reset());
                assertEquals(dimension, decoded.dimension());
                restored.addAll(decoded.entries());
                assertFalse(buffer.isReadable());
            } finally { buffer.release(); }
        }
        assertEquals(input.size(), restored.size());
        for (int i = 0; i < input.size(); i++) {
            assertEquals(input.get(i).regionX(), restored.get(i).regionX());
            assertArrayEquals(input.get(i).slots(), restored.get(i).slots());
            assertArrayEquals(input.get(i).timestamps(), restored.get(i).timestamps());
            assertArrayEquals(input.get(i).bitmap(), restored.get(i).bitmap());
        }
    }

    @Test void emptyResetIsSentAndEmptyIncrementalUpdateIsOmitted() {
        List<RegionPresenceC2SPayload> packets = new ArrayList<>();
        RegionPresenceTransfer.send(Level.OVERWORLD, true, List.of(), false, packets::add);
        assertEquals(1, packets.size()); assertTrue(packets.get(0).reset());
        RegionPresenceTransfer.send(Level.OVERWORLD, false, List.of(), false, packets::add);
        assertEquals(1, packets.size());
    }
}
