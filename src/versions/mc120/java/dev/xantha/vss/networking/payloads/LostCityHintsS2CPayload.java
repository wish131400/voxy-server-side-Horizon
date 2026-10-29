package dev.xantha.vss.networking.payloads;

import java.util.List;
import dev.xantha.vss.common.worldgen.LostCityPreview;
import net.minecraft.network.FriendlyByteBuf;

import dev.xantha.vss.networking.payloads.LegacyStreamCodec;
import dev.xantha.vss.networking.payloads.LegacyPayload;
import net.minecraft.resources.Identifier;

/** Selected exterior templates and city surfaces; real Voxy columns supersede these previews. */
public record LostCityHintsS2CPayload(Identifier dimension, int regionX, int regionZ,
                                      long session, boolean active, List<LostCityPreview.Chunk> chunks) implements LegacyPayload {
    public static final int REGION_CHUNKS = 8;
    public static final int ENTRY_COUNT = REGION_CHUNKS * REGION_CHUNKS;
    public static final Type<LostCityHintsS2CPayload> TYPE = VSSPayloadCodecs.type("lost_city_hints");
    public static final LegacyStreamCodec<FriendlyByteBuf, LostCityHintsS2CPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(LostCityHintsS2CPayload::encode, LostCityHintsS2CPayload::decode);

    public LostCityHintsS2CPayload {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        if (active && chunks.size() != ENTRY_COUNT || !active && !chunks.isEmpty())
            throw new IllegalArgumentException("Invalid Lost Cities hint count");
        if (active) chunks = LostCityPreview.bounded(chunks);
    }

    @Override public Type<? extends LegacyPayload> type() { return TYPE; }

    private static void encode(LostCityHintsS2CPayload payload, FriendlyByteBuf buffer) {
        buffer.writeIdentifier(payload.dimension);
        buffer.writeInt(payload.regionX);
        buffer.writeInt(payload.regionZ);
        buffer.writeLong(payload.session);
        buffer.writeBoolean(payload.active);
        if (payload.active) LostCityPreview.write(buffer, payload.chunks);
    }

    private static LostCityHintsS2CPayload decode(FriendlyByteBuf buffer) {
        Identifier dimension = buffer.readIdentifier();
        int regionX = buffer.readInt(), regionZ = buffer.readInt();
        long session = buffer.readLong();
        boolean active = buffer.readBoolean();
        List<LostCityPreview.Chunk> chunks = active ? LostCityPreview.read(buffer) : List.of();
        return new LostCityHintsS2CPayload(dimension, regionX, regionZ, session, active, chunks);
    }
}
