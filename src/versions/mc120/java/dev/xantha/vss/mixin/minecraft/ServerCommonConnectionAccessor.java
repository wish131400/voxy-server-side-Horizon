package dev.xantha.vss.mixin.minecraft;

import dev.xantha.vss.platform.ServerConnectionAccess;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Play connection transport access; identity negotiation uses Fabric login queries in 1.20.1. */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerCommonConnectionAccessor extends ServerConnectionAccess {
    @Override
    @Accessor("connection")
    Connection vss$connection();
}
