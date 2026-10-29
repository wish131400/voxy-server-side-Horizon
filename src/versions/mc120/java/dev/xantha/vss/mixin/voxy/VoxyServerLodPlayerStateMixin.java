package dev.xantha.vss.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.xantha.vss.compat.VoxyServerLodServerCompat;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Discards queued and late worker buffers atomically when VSS takes over this player. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.commonImpl.network.ServerLodStreamer$PlayerState", remap = false)
public abstract class VoxyServerLodPlayerStateMixin implements VoxyServerLodServerCompat.RetirableState {
    @Shadow @Final private ArrayDeque<?> pending;
    @Shadow @Final private ConcurrentLinkedQueue<?> outbox;
    @Shadow @Final private AtomicLong outboxBytes;
    @Shadow @Final private Set<Long> missingSent;
    @Unique private boolean vss$retired;

    @Override public synchronized void vss$retire() {
        vss$retired = true;
        pending.clear();
        missingSent.clear();
        Object outgoing;
        while ((outgoing = outbox.poll()) != null) VoxyServerLodServerCompat.releaseOutgoing(outgoing);
        outboxBytes.set(0);
    }

    @WrapMethod(method = "enqueue(Lme/cortex/voxy/commonImpl/network/ServerLodStreamer$Outgoing;)V")
    private void vss$dropRetiredOutput(@Coerce Object outgoing, Operation<Void> original) {
        synchronized (this) {
            if (vss$retired) VoxyServerLodServerCompat.releaseOutgoing(outgoing);
            else original.call(outgoing);
        }
    }
}
