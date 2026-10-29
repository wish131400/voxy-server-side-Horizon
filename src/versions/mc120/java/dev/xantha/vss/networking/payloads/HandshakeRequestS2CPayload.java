package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;

import dev.xantha.vss.networking.payloads.LegacyStreamCodec;
import dev.xantha.vss.networking.payloads.LegacyPayload;

public record HandshakeRequestS2CPayload() implements LegacyPayload {
    public static final Type<HandshakeRequestS2CPayload> TYPE = VSSPayloadCodecs.type("handshake_request");
    public static final LegacyStreamCodec<FriendlyByteBuf, HandshakeRequestS2CPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(HandshakeRequestS2CPayload::encode, HandshakeRequestS2CPayload::decode);

    @Override
    public Type<? extends LegacyPayload> type() {
        return TYPE;
    }

    public static void encode(HandshakeRequestS2CPayload payload, FriendlyByteBuf buf) {
    }

    public static HandshakeRequestS2CPayload decode(FriendlyByteBuf buf) {
        return new HandshakeRequestS2CPayload();
    }
}
