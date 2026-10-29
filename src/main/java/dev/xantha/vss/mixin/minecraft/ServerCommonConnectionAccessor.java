package dev.xantha.vss.mixin.minecraft;

import dev.xantha.vss.platform.ServerConnectionAccess;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Common packet listener transport access shared by play and configuration phases. */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonConnectionAccessor extends ServerConnectionAccess {
    @Override
    @Accessor("connection")
    Connection vss$connection();
}
