package dev.xantha.vss.compat.ftbchunks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.testutil.MinecraftTestBootstrap;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FtbForceLoadIntegrationTest {
    private static final UUID OWNER = new UUID(1, 2);
    private final ArrayList<MinecraftServer> servers = new ArrayList<>();
    private boolean previousEnabled;
    private int previousBudget;

    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.initialize(); }
    @BeforeEach void configure() {
        previousEnabled = VSSServerConfig.CONFIG.ftbChunksSafeForceLoad;
        previousBudget = VSSServerConfig.CONFIG.ftbChunksForceLoadTicketsPerTick;
        VSSServerConfig.CONFIG.ftbChunksSafeForceLoad = true;
        VSSServerConfig.CONFIG.ftbChunksForceLoadTicketsPerTick = 1;
    }
    @AfterEach void cleanup() {
        servers.forEach(FTBChunksForceLoadCompat::onServerStopping);
        VSSServerConfig.CONFIG.ftbChunksSafeForceLoad = previousEnabled;
        VSSServerConfig.CONFIG.ftbChunksForceLoadTicketsPerTick = previousBudget;
    }

    @Test void controllerUsesOneServerBudgetAndOnlyScopesOriginalTicketApplication() {
        var server = server();
        var a = level(server);
        var b = level(server);
        when(a.setChunkForced(1, 2, true)).thenAnswer(invocation -> {
            assertTrue(FTBChunksForceLoadCompat.skipSynchronousChunkRead(a, 1, 2));
            assertFalse(FTBChunksForceLoadCompat.skipSynchronousChunkRead(b, 1, 2));
            return true;
        });
        assertTrue(FTBChunksForceLoadCompat.tryHandleVanilla(a, OWNER, 1, 2, true));
        assertTrue(FTBChunksForceLoadCompat.tryHandleVanilla(b, OWNER, 3, 4, true));
        verify(a, never()).setChunkForced(1, 2, true);
        FTBChunksForceLoadCompat.onServerTick(server);
        verify(a).setChunkForced(1, 2, true);
        verify(b, never()).setChunkForced(3, 4, true);
        assertFalse(FTBChunksForceLoadCompat.skipSynchronousChunkRead(a, 1, 2));
        FTBChunksForceLoadCompat.onServerTick(server);
        verify(b).setChunkForced(3, 4, true);
    }

    @Test void mainThreadReleaseIsImmediateAndCancelsOldOwnerAdd() {
        var server = server();
        var level = level(server);
        assertTrue(FTBChunksForceLoadCompat.tryHandleVanilla(level, OWNER, 1, 2, true));
        assertTrue(FTBChunksForceLoadCompat.tryHandleVanilla(level, new UUID(3, 4), 1, 2, false));
        verify(level).setChunkForced(1, 2, false);
        FTBChunksForceLoadCompat.onServerTick(server);
        verify(level, never()).setChunkForced(1, 2, true);
    }

    @Test void disablingOptionDoesNotDiscardAlreadyAcceptedTicket() {
        var server = server();
        var level = level(server);
        assertTrue(FTBChunksForceLoadCompat.tryHandleVanilla(level, OWNER, 1, 2, true));
        VSSServerConfig.CONFIG.ftbChunksSafeForceLoad = false;
        FTBChunksForceLoadCompat.onServerTick(server);
        verify(level).setChunkForced(1, 2, true);
    }

    @Test void newDirectRequestAfterDisableSupersedesOlderQueuedOperation() {
        var server = server();
        var level = level(server);
        assertTrue(FTBChunksForceLoadCompat.tryHandleVanilla(level, OWNER, 1, 2, true));
        VSSServerConfig.CONFIG.ftbChunksSafeForceLoad = false;
        assertFalse(FTBChunksForceLoadCompat.tryHandleVanilla(level, OWNER, 1, 2, false));
        // The caller now retains its original direct implementation.
        level.setChunkForced(1, 2, false);
        FTBChunksForceLoadCompat.onServerTick(server);
        verify(level, never()).setChunkForced(1, 2, true);
        verify(level).setChunkForced(1, 2, false);
    }

    @Test void offThreadReleaseRunsOnServerTickAndSupersedesOldAdd() {
        var server = server();
        var level = level(server);
        when(server.isSameThread()).thenReturn(false);
        assertTrue(FTBChunksForceLoadCompat.tryHandleVanilla(level, OWNER, 1, 2, true));
        assertTrue(FTBChunksForceLoadCompat.tryHandleVanilla(level, OWNER, 1, 2, false));
        verify(level, never()).setChunkForced(anyInt(), anyInt(), anyBoolean());
        FTBChunksForceLoadCompat.onServerTick(server);
        verify(level).setChunkForced(1, 2, false);
        verify(level, never()).setChunkForced(1, 2, true);
    }

    @Test void stoppingOneSessionDropsItsLevelButKeepsAnotherServersPendingTicket() {
        var oldServer = server();
        var newServer = server();
        var oldLevel = level(oldServer);
        var newLevel = level(newServer);
        assertTrue(FTBChunksForceLoadCompat.tryHandleVanilla(oldLevel, OWNER, 1, 2, true));
        assertTrue(FTBChunksForceLoadCompat.tryHandleVanilla(newLevel, OWNER, 1, 2, true));
        FTBChunksForceLoadCompat.onServerStopping(oldServer);
        FTBChunksForceLoadCompat.onServerTick(newServer);
        verify(oldLevel, never()).setChunkForced(anyInt(), anyInt(), anyBoolean());
        verify(newLevel).setChunkForced(1, 2, true);
    }

    private MinecraftServer server() {
        var server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        servers.add(server);
        return server;
    }

    private static ServerLevel level(MinecraftServer server) {
        var level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        return level;
    }
}
