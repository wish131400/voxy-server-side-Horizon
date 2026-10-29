package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.Test;

class PredictionBiomeCacheTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void failedLookupKeepsItsCauseAndRetriesInsteadOfRememberingEmptyBiome() {
        var source = mock(BiomeSource.class);
        var climate = mock(Climate.Sampler.class);
        var resolver = mock(net.minecraft.world.level.biome.BiomeResolver.class);
        when(source.createResolver(climate)).thenReturn(resolver);
        var biome = Holder.direct(mock(Biome.class));
        var cause = new IllegalStateException("MultiNoiseSampler doesn't have a seed set");
        when(resolver.getNoiseBiome(-7, 20, 31)).thenThrow(cause).thenReturn(biome);
        var cache = new PredictionBiomeCache(source, climate);
        var failure = assertThrows(PredictionBiomeCache.LookupFailure.class, () -> cache.get(-7, 20, 31));
        assertSame(cause, failure.getCause());
        assertSame(biome, cache.get(-7, 20, 31));
        assertSame(biome, cache.get(-7, 20, 31));
        verify(resolver, times(2)).getNoiseBiome(-7, 20, 31);
    }

    @Test void cancellationAndDeferredResourcesKeepTheirControlFlow() {
        var source = mock(BiomeSource.class);
        var climate = mock(Climate.Sampler.class);
        var resolver = mock(net.minecraft.world.level.biome.BiomeResolver.class);
        when(source.createResolver(climate)).thenReturn(resolver);
        var deferred = new PredictionWorkDeferred();
        var cancelled = new java.util.concurrent.CancellationException();
        when(resolver.getNoiseBiome(0, 0, 0)).thenThrow(deferred).thenThrow(cancelled);
        var cache = new PredictionBiomeCache(source, climate);
        assertSame(deferred, assertThrows(PredictionWorkDeferred.class, () -> cache.get(0, 0, 0)));
        assertSame(cancelled, assertThrows(java.util.concurrent.CancellationException.class, () -> cache.get(0, 0, 0)));
    }
}
