package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;

import dev.xantha.vss.networking.payloads.LegacyStreamCodec;
import dev.xantha.vss.networking.payloads.LegacyPayload;

public record SessionConfigS2CPayload(
        int protocolVersion,
        boolean enabled,
        int lodDistanceChunks,
        int serverCapabilities,
        int nearSyncRateLimitPerTick,
        int midSyncRateLimitPerTick,
        int farSyncRateLimitPerTick,
        int distantSyncRateLimitPerTick,
        int generationConcurrencyLimitPerPlayer,
        boolean generationEnabled,
        long serverBandwidthLimit,
        long configRevision) implements LegacyPayload {
    public static final LegacyPayload.Type<SessionConfigS2CPayload> TYPE = VSSPayloadCodecs.type("session_config");
    public static final LegacyStreamCodec<FriendlyByteBuf, SessionConfigS2CPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(SessionConfigS2CPayload::encode, SessionConfigS2CPayload::decode);

    @Override
    public Type<? extends LegacyPayload> type() {
        return TYPE;
    }

    public static void encode(SessionConfigS2CPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.protocolVersion);
        buf.writeBoolean(payload.enabled);
        buf.writeVarInt(payload.lodDistanceChunks);
        buf.writeVarInt(payload.serverCapabilities);
        buf.writeVarInt(payload.nearSyncRateLimitPerTick);
        buf.writeVarInt(payload.midSyncRateLimitPerTick);
        buf.writeVarInt(payload.farSyncRateLimitPerTick);
        buf.writeVarInt(payload.distantSyncRateLimitPerTick);
        buf.writeVarInt(payload.generationConcurrencyLimitPerPlayer);
        buf.writeBoolean(payload.generationEnabled);
        buf.writeVarLong(payload.serverBandwidthLimit);
        buf.writeVarLong(payload.configRevision);
    }

    public static SessionConfigS2CPayload decode(FriendlyByteBuf buf) {
        return new SessionConfigS2CPayload(
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarLong(),
                buf.readVarLong());
    }
}
