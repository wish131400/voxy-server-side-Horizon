package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.junit.jupiter.api.*;

class TerraBlenderRequiredRoutingTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    @AfterEach void unregister() { PredictionTerrainBackends.unregister("terrablender"); }

    @Test void requiredRegionRoutingFailureCannotPublishVanillaJavaPrediction() throws Exception {
        var access = TerrainPredictionFixtures.access();
        var noise = TerrainPredictionFixtures.noise();
        var ops = RegistryOps.create(JsonOps.INSTANCE, access);
        var generator = new JsonObject();
        generator.add("settings", NoiseGeneratorSettings.DIRECT_CODEC
                .encodeStart(ops, noise.generatorSettings().value()).result().orElseThrow());
        generator.add("biome_source", BiomeSource.CODEC
                .encodeStart(ops, noise.getBiomeSource()).result().orElseThrow());
        generator.add("vss_terrablender", new JsonObject());
        generator.addProperty("vss_force_java", true);
        byte[] encoded = generator.toString().getBytes(StandardCharsets.UTF_8);
        var profile = new DimensionProfile(Level.OVERWORLD.identifier(), 42, -64, 384,
                "noise", "minecraft:overworld", 0, 0, encoded.length, encoded);
        var snapshot = TerrainPredictionFixtures.emptyRegistries();
        var noises = snapshot.getAsJsonObject("noises");
        TerrainPredictionFixtures.sourceLookup().lookupOrThrow(Registries.NOISE).listElements().forEach(holder ->
                noises.add(holder.key().identifier().toString(), dev.xantha.vss.testutil.NoiseCodecFixtures.CODEC
                        .encodeStart(ops, holder.value()).result().orElseThrow()));
        byte[] registries = snapshot.toString().getBytes(StandardCharsets.UTF_8);
        var payload = new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION, 42, 1,
                0, registries.length, registries, List.of(profile));
        PredictionTerrainBackends.register(new TerraBlenderBackend());
        var results = ClientWorldgenProfileDecoder.decode(payload, access);
        try {
            assertTrue(results.isEmpty(), "Required mod routing must survive backend selection or reject prediction");
        } finally { results.values().forEach(ClientTerrainSampler::close); }
    }
}
