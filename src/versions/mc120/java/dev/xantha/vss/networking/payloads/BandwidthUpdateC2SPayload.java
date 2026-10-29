package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;

import dev.xantha.vss.networking.payloads.LegacyStreamCodec;
import dev.xantha.vss.networking.payloads.LegacyPayload;

public record BandwidthUpdateC2SPayload(long desiredRate) implements LegacyPayload {
    public static final LegacyPayload.Type<BandwidthUpdateC2SPayload> TYPE = VSSPayloadCodecs.type("bandwidth_update");
    public static final LegacyStreamCodec<FriendlyByteBuf, BandwidthUpdateC2SPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(BandwidthUpdateC2SPayload::encode, BandwidthUpdateC2SPayload::decode);

    @Override
    public Type<? extends LegacyPayload> type() {
        return TYPE;
    }

    public static void encode(BandwidthUpdateC2SPayload payload, FriendlyByteBuf buf) {
        buf.writeVarLong(payload.desiredRate);
    }

    public static BandwidthUpdateC2SPayload decode(FriendlyByteBuf buf) {
        return new BandwidthUpdateC2SPayload(buf.readVarLong());
    }
}
