package dev.xantha.vss.platform;

import net.minecraft.network.Connection;

/** Vanilla server listener connection used for network backpressure. */
public interface ServerConnectionAccess {
    Connection vss$connection();
}
