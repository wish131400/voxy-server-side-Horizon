package dev.xantha.vss.platform;

import io.netty.channel.Channel;

/** Access to vanilla connection backpressure without loader networking extensions. */
public interface ConnectionChannelAccess {
    Channel vss$channel();
}
