package dev.xantha.vss.networking.payloads;

import net.minecraft.resources.ResourceLocation;

/** Typed VSS message identity carried by Fabric 1.20.1's byte-buffer channels. */
public interface LegacyPayload {
    record Type<T extends LegacyPayload>(ResourceLocation id) { }
    Type<? extends LegacyPayload> type();
}
