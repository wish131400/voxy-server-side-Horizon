package dev.xantha.vss.networking.payloads;

import java.util.function.BiConsumer;
import java.util.function.Function;

/** Old Fabric keeps serialization in the channel receiver and sender. */
public final class LegacyStreamCodec<B, T> {
    private final BiConsumer<T, B> encoder;
    private final Function<B, T> decoder;
    private LegacyStreamCodec(BiConsumer<T, B> encoder, Function<B, T> decoder) {
        this.encoder = encoder;
        this.decoder = decoder;
    }
    public static <B, T> LegacyStreamCodec<B, T> ofMember(BiConsumer<T, B> encoder, Function<B, T> decoder) {
        return new LegacyStreamCodec<>(encoder, decoder);
    }
    public void encode(B buffer, T value) { encoder.accept(value, buffer); }
    public T decode(B buffer) { return decoder.apply(buffer); }
}
