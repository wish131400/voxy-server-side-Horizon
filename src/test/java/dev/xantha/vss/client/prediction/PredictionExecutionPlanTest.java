package dev.xantha.vss.client.prediction;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionExecutionPlanTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void javaSurfaceIsInstalledOnceAcrossBackendAndDecoderEntryPoints() {
        var generator = JsonParser.parseString("{\"vss_surface_stage\":{}}").getAsJsonObject();
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.world.level.Level.OVERWORLD.identifier(), 42, -64, 384, "noise", "overworld", 1);
        var source = new ClientTerrainSampler(42, profile);
        var plan = PredictionExecutionPlan.resolve(generator, null);
        try (var wrapped = plan.javaSampler(source, generator)) {
            assertInstanceOf(PredictionSurfaceTerrainSampler.class, wrapped);
            assertSame(source, wrapped.decorationContext());
            assertSame(wrapped, plan.javaSampler(wrapped, generator));
        }
    }

    @Test void originalStagesAndTerrainWithoutJavaSurfaceKeepTheirExecutor() {
        var generator = JsonParser.parseString("{\"vss_surface_stage\":{},\"vss_original_stages\":{}}").getAsJsonObject();
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.world.level.Level.OVERWORLD.identifier(), 42, -64, 384, "noise", "overworld", 1);
        try (var source = new ClientTerrainSampler(42, profile)) {
            assertSame(source, PredictionExecutionPlan.resolve(generator, null).javaSampler(source, generator));
            var ordinary = new com.google.gson.JsonObject();
            assertSame(source, PredictionExecutionPlan.resolve(ordinary, null).javaSampler(source, ordinary));
        }
    }

    @Test void unknownSurfaceUsesJavaWhileNativeTerrainInputIsDetached() {
        var input = JsonParser.parseString("""
            {"settings":{"noise_router":{"final_density":0},"default_block":{"Name":"minecraft:stone"},
              "surface_rule":{"type":"unregistered_mod:surface"}},"biome_source":{"type":"minecraft:fixed"}}
            """).getAsJsonObject();
        var registries = new com.google.gson.JsonObject();
        String original = input.toString();
        var plan = PredictionExecutionPlan.resolve(input,registries);
        assertTrue(plan.nativeTerrain()); assertTrue(plan.javaSurface());
        assertEquals("minecraft:block", plan.nativeGenerator(input).getAsJsonObject("settings").getAsJsonObject("surface_rule").get("type").getAsString());
        assertEquals(original,input.toString());
        input.getAsJsonObject("settings").getAsJsonObject("noise_router").add("final_density",JsonParser.parseString("{\"type\":\"unregistered_mod:density\"}"));
        assertFalse(PredictionExecutionPlan.resolve(input,registries).nativeTerrain());
    }
}
