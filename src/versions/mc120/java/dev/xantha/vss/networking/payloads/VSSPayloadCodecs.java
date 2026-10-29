package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import dev.xantha.vss.common.VSSConstants;

import dev.xantha.vss.networking.payloads.LegacyStreamCodec;
import dev.xantha.vss.networking.payloads.LegacyPayload;
import net.minecraft.resources.Identifier;

final class VSSPayloadCodecs {
    private VSSPayloadCodecs() {
    }

    static <T extends LegacyPayload> LegacyPayload.Type<T> type(String path) {
        return new LegacyPayload.Type<>(Identifier.fromNamespaceAndPath(VSSConstants.MOD_ID, path));
    }

    static <T extends LegacyPayload> LegacyStreamCodec<FriendlyByteBuf, T> codec(
            java.util.function.BiConsumer<T, FriendlyByteBuf> encoder,
            java.util.function.Function<FriendlyByteBuf, T> decoder) {
        return LegacyStreamCodec.ofMember(encoder::accept, decoder::apply);
    }
}
