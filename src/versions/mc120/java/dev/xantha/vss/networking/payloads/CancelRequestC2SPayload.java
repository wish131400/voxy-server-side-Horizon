package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;

import dev.xantha.vss.networking.payloads.LegacyStreamCodec;
import dev.xantha.vss.networking.payloads.LegacyPayload;

public record CancelRequestC2SPayload(int requestId) implements LegacyPayload {
    public static final LegacyPayload.Type<CancelRequestC2SPayload> TYPE = VSSPayloadCodecs.type("cancel_request");
    public static final LegacyStreamCodec<FriendlyByteBuf, CancelRequestC2SPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(CancelRequestC2SPayload::encode, CancelRequestC2SPayload::decode);

    @Override
    public Type<? extends LegacyPayload> type() {
        return TYPE;
    }

    public static void encode(CancelRequestC2SPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.requestId);
    }

    public static CancelRequestC2SPayload decode(FriendlyByteBuf buf) {
        return new CancelRequestC2SPayload(buf.readVarInt());
    }
}
