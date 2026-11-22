package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Requests one bounded 8x8-chunk city-planning summary. */
public record LostCityHintsC2SPayload(Identifier dimension, int regionX, int regionZ,
                                      long session) implements CustomPacketPayload {
    public static final Type<LostCityHintsC2SPayload> TYPE = VSSPayloadCodecs.type("lost_city_hints_request");
    public static final StreamCodec<RegistryFriendlyByteBuf, LostCityHintsC2SPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(LostCityHintsC2SPayload::encode, LostCityHintsC2SPayload::decode);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void encode(LostCityHintsC2SPayload payload, FriendlyByteBuf buffer) {
        buffer.writeIdentifier(payload.dimension);
        buffer.writeInt(payload.regionX);
        buffer.writeInt(payload.regionZ);
        buffer.writeLong(payload.session);
    }

    private static LostCityHintsC2SPayload decode(FriendlyByteBuf buffer) {
        return new LostCityHintsC2SPayload(buffer.readIdentifier(), buffer.readInt(),
                buffer.readInt(), buffer.readLong());
    }
}
