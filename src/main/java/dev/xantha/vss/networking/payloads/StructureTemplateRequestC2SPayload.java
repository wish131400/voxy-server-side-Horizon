package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.worldgen.StructureTemplateDigest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A request is bound to an immutable catalog and exact expected content. */
public record StructureTemplateRequestC2SPayload(String catalog, Identifier id, String hash)
        implements CustomPacketPayload {
    public static final Type<StructureTemplateRequestC2SPayload> TYPE = VSSPayloadCodecs.type("structure_template_request");
    public static final StreamCodec<RegistryFriendlyByteBuf, StructureTemplateRequestC2SPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(StructureTemplateRequestC2SPayload::encode, StructureTemplateRequestC2SPayload::decode);

    public StructureTemplateRequestC2SPayload {
        if (!StructureTemplateDigest.validHash(catalog) || !StructureTemplateDigest.validHash(hash) || id == null)
            throw new IllegalArgumentException("Invalid template request");
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void encode(StructureTemplateRequestC2SPayload value, FriendlyByteBuf buf) {
        buf.writeUtf(value.catalog, 64); buf.writeIdentifier(value.id); buf.writeUtf(value.hash, 64);
    }
    public static StructureTemplateRequestC2SPayload decode(FriendlyByteBuf buf) {
        return new StructureTemplateRequestC2SPayload(buf.readUtf(64), buf.readIdentifier(), buf.readUtf(64));
    }
}
