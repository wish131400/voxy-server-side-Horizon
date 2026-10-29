package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.InvocationTargetException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionAtlasTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void predictionUsesTheRealManagersDefinitionIdAndTracksReloadedSprites() throws Exception {
        var first = mock(TextureAtlasSprite.class);
        var reloaded = mock(TextureAtlasSprite.class);
        var sprite = new AtomicReference<>(first);
        // Keep the actual AtlasManager registry and lookup behavior; only GPU
        // atlas allocation and texture registration are replaced for this test.
        try (var textures = mockConstruction(TextureAtlas.class, (atlas, context) ->
                when(atlas.getSprite(Identifier.withDefaultNamespace("block/stone")))
                        .thenAnswer(call -> sprite.get()))) {
            // The manager moved packages in 26.x; discover the real return type
            // without replacing its registry or lookup implementation.
            var managerType = Minecraft.class.getMethod("getAtlasManager").getReturnType();
            var lookup = managerType.getMethod("getAtlasOrThrow", Identifier.class);
            try (var atlases = (AutoCloseable) managerType.getConstructor(TextureManager.class, int.class)
                    .newInstance(mock(TextureManager.class), 0)) {
                var minecraft = mock(Minecraft.class);
                doReturn(atlases).when(minecraft).getAtlasManager();
                assertNotNull(lookup.invoke(atlases, net.minecraft.data.AtlasIds.BLOCKS));
                var wrongId = assertThrows(InvocationTargetException.class,
                        () -> lookup.invoke(atlases, TextureAtlas.LOCATION_BLOCKS));
                assertInstanceOf(IllegalArgumentException.class, wrongId.getCause());
                assertSame(first, ClientPredictionState.currentResourceAtlasToken(minecraft));
                assertSame(first, ClientPredictionState.currentResourceAtlasToken(minecraft));
                sprite.set(reloaded);
                assertSame(reloaded, ClientPredictionState.currentResourceAtlasToken(minecraft));
            }
        }
    }
}
