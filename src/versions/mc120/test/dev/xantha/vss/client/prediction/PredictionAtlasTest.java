package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exercise the real 1.20 atlas upload and lookup while replacing GPU calls only. */
class PredictionAtlasTest {
    @BeforeAll static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void legacyAtlasTokenIsStableBetweenReloadsAndChangesOnActualUpload() {
        try (var render = mockStatic(RenderSystem.class); var gpu = mockStatic(TextureUtil.class)) {
            render.when(RenderSystem::isOnRenderThreadOrInit).thenReturn(true);
            render.when(RenderSystem::maxSupportedTextureSize).thenReturn(4096);
            var atlas = new TextureAtlas(TextureAtlas.LOCATION_BLOCKS);
            var client = mock(Minecraft.class);
            when(client.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)).thenReturn(atlas::getSprite);
            var first = sprite();
            var reloaded = sprite();
            upload(atlas, first);
            assertNotNull(ClientPredictionState.currentResourceAtlasToken(client));
            assertSame(first, ClientPredictionState.currentResourceAtlasToken(client));
            assertSame(first, ClientPredictionState.currentResourceAtlasToken(client));
            upload(atlas, reloaded);
            assertSame(reloaded, ClientPredictionState.currentResourceAtlasToken(client));
            assertNotSame(first, ClientPredictionState.currentResourceAtlasToken(client));
            verify(first.contents()).close();
        }
    }

    private static TextureAtlasSprite sprite() {
        var sprite = mock(TextureAtlasSprite.class);
        when(sprite.contents()).thenReturn(mock(SpriteContents.class));
        return sprite;
    }

    private static void upload(TextureAtlas atlas, TextureAtlasSprite missing) {
        atlas.upload(new SpriteLoader.Preparations(16, 16, 0, missing,
                Map.of(MissingTextureAtlasSprite.getLocation(), missing), CompletableFuture.completedFuture(null)));
    }
}
