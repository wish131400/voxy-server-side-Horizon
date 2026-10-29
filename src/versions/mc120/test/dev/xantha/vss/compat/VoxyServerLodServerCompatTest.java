package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import org.junit.jupiter.api.Test;

class VoxyServerLodServerCompatTest {
    private static final class State implements VoxyServerLodServerCompat.RetirableState {
        int retirements;
        @Override public void vss$retire() { retirements++; }
    }
    private record Outgoing(io.netty.buffer.ByteBuf buf) { }

    @Test void ownershipRetiresOnlyVssPlayersAndRestoresNativeEnableWhenOwnershipEnds() {
        UUID vss=UUID.randomUUID(),nativeOnly=UUID.randomUUID();
        State a=new State(),b=new State();
        Map<UUID,State> states=new HashMap<>(Map.of(vss,a,nativeOnly,b));
        Set<UUID> pending=new HashSet<>(List.of(vss,nativeOnly)),suppressed=new HashSet<>();
        Set<UUID> online=Set.of(vss,nativeOnly);
        VoxyServerLodServerCompat.reconcile(states,pending,suppressed,online,vss::equals);
        assertEquals(Map.of(nativeOnly,b),states);
        assertEquals(Set.of(nativeOnly),pending);
        assertEquals(Set.of(vss),suppressed);
        assertEquals(1,a.retirements);assertEquals(0,b.retirements);
        VoxyServerLodServerCompat.reconcile(states,pending,suppressed,online,vss::equals);
        assertEquals(1,a.retirements);
        VoxyServerLodServerCompat.reconcile(states,pending,suppressed,online,id->false);
        assertEquals(Set.of(vss,nativeOnly),pending);
        assertTrue(suppressed.isEmpty());
        suppressed.add(vss);
        VoxyServerLodServerCompat.reconcile(states,pending,suppressed,Set.of(nativeOnly),id->false);
        assertTrue(suppressed.isEmpty());
    }

    @Test void retiredBuffersReleaseTheirNativeReference() {
        var buffer=Unpooled.buffer();
        buffer.writeByte(1);
        VoxyServerLodServerCompat.releaseOutgoing(new Outgoing(buffer));
        assertEquals(0,buffer.refCnt());
    }
}
