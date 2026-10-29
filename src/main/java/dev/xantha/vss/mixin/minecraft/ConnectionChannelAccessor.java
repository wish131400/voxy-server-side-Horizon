package dev.xantha.vss.mixin.minecraft;

import dev.xantha.vss.platform.ConnectionChannelAccess;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the vanilla transport for bounded sends without a loader-specific network extension. */
@Mixin(Connection.class)
public interface ConnectionChannelAccessor extends ConnectionChannelAccess {
    @Override
    @Accessor("channel")
    Channel vss$channel();
}
