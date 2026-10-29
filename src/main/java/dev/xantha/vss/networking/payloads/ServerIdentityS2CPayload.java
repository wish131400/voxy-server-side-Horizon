package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.common.processing.ColumnCoverage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ServerIdentityS2CPayload(
        String serverIdentity,
        boolean sharedWorld,
        String nodeIdentity,
        String columnFilterKey) implements CustomPacketPayload {
    public ServerIdentityS2CPayload(String serverIdentity, boolean sharedWorld, String nodeIdentity) {
        this(serverIdentity, sharedWorld, nodeIdentity, "");
    }

    public ServerIdentityS2CPayload { new ColumnCoverage(columnFilterKey); }
    private static final int MAX_SERVER_IDENTITY_LENGTH = 16;
    private static final int MAX_NODE_IDENTITY_LENGTH = 32;

    public static final Type<ServerIdentityS2CPayload> TYPE = VSSPayloadCodecs.type("server_identity");
    public static final StreamCodec<FriendlyByteBuf, ServerIdentityS2CPayload> STREAM_CODEC =
            StreamCodec.ofMember(ServerIdentityS2CPayload::encode, ServerIdentityS2CPayload::decode);

    public static ServerIdentityS2CPayload fromConfig() {
        return fromConfig("");
    }

    public static ServerIdentityS2CPayload fromConfig(String filterKey) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        return new ServerIdentityS2CPayload(config.serverIdentity, config.sharedWorld, config.nodeIdentity, filterKey);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(ServerIdentityS2CPayload payload, FriendlyByteBuf buf) {
        buf.writeUtf(payload.serverIdentity, MAX_SERVER_IDENTITY_LENGTH);
        buf.writeBoolean(payload.sharedWorld);
        buf.writeUtf(payload.nodeIdentity, MAX_NODE_IDENTITY_LENGTH);
        buf.writeUtf(payload.columnFilterKey, 64);
    }

    public static ServerIdentityS2CPayload decode(FriendlyByteBuf buf) {
        return new ServerIdentityS2CPayload(
                buf.readUtf(MAX_SERVER_IDENTITY_LENGTH),
                buf.readBoolean(),
                buf.readUtf(MAX_NODE_IDENTITY_LENGTH), buf.readUtf(64));
    }
}
