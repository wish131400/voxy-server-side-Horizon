package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;

import dev.xantha.vss.networking.payloads.LegacyStreamCodec;
import dev.xantha.vss.networking.payloads.LegacyPayload;

public record HandshakeC2SPayload(int protocolVersion, int capabilities) implements LegacyPayload {
    public static final LegacyPayload.Type<HandshakeC2SPayload> TYPE = VSSPayloadCodecs.type("handshake");
    public static final LegacyStreamCodec<FriendlyByteBuf, HandshakeC2SPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(HandshakeC2SPayload::encode, HandshakeC2SPayload::decode);

    @Override
    public Type<? extends LegacyPayload> type() {
        return TYPE;
    }

    public static void encode(HandshakeC2SPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.protocolVersion);
        buf.writeVarInt(payload.capabilities);
    }

    public static HandshakeC2SPayload decode(FriendlyByteBuf buf) {
        return new HandshakeC2SPayload(buf.readVarInt(), buf.readVarInt());
    }
}
