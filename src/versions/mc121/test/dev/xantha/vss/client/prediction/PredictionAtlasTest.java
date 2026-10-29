package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionAtlasTest {
    @BeforeAll static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void predictionUsesTheRealManagersTextureLocationAndTracksReloadedSprites() throws Exception {
        var first = mock(TextureAtlasSprite.class);
        var reloaded = mock(TextureAtlasSprite.class);
        var sprite = new AtomicReference<>(first);
        // Keep ModelManager's real atlas registry and Minecraft's lookup function.
        // Only GPU allocations and texture registration are replaced.
        try (var textures = mockConstruction(TextureAtlas.class, (atlas, context) ->
                when(atlas.getSprite(ResourceLocation.withDefaultNamespace("block/stone")))
                        .thenAnswer(call -> sprite.get()));
             var models = new ModelManager(mock(TextureManager.class), mock(BlockColors.class), 0)) {
            var minecraft = mock(Minecraft.class);
            var field = Minecraft.class.getDeclaredField("modelManager");
            field.setAccessible(true);
            field.set(minecraft, models);
            when(minecraft.getTextureAtlas(any())).thenCallRealMethod();
            assertNotNull(models.getAtlas(TextureAtlas.LOCATION_BLOCKS));
            assertThrows(NullPointerException.class, () -> models.getAtlas(ResourceLocation.withDefaultNamespace("blocks")),
                    "the legacy API indexes texture locations, not modern atlas definition IDs");
            assertSame(first, ClientPredictionState.currentResourceAtlasToken(minecraft));
            assertSame(first, ClientPredictionState.currentResourceAtlasToken(minecraft));
            sprite.set(reloaded);
            assertSame(reloaded, ClientPredictionState.currentResourceAtlasToken(minecraft));
        }
    }
}
