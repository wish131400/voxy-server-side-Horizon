package dev.xantha.vss.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleplayerGenerationConfigTest {
    @Test void generationDefaultsFollowEnvironmentWithoutOverwritingSavedChoices() throws Exception {
        var loader = org.mockito.Mockito.mock(net.fabricmc.loader.api.FabricLoader.class);
        try (var fabric = org.mockito.Mockito.mockStatic(net.fabricmc.loader.api.FabricLoader.class)) {
            fabric.when(net.fabricmc.loader.api.FabricLoader::getInstance).thenReturn(loader);
            for (var environment : net.fabricmc.api.EnvType.values()) {
                org.mockito.Mockito.when(loader.getEnvironmentType()).thenReturn(environment);
                assertGenerationDefaults(environment == net.fabricmc.api.EnvType.SERVER);
            }
        }
    }

    private void assertGenerationDefaults(boolean expectedDefault) throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("vss-generation-toggle-");
        var file = directory.resolve("vss-server-config.json");
        var store = new JsonConfigStore(directory);
        var config = store.load(VSSServerConfig.class, "vss-server-config.json");
        assertEquals(expectedDefault, config.enableChunkGeneration);
        assertTrue(config.enabled);
        assertTrue(config.enablePredictionSync);
        assertEquals(expectedDefault, com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file))
                .getAsJsonObject().get("enableChunkGeneration").getAsBoolean());
        for (String saved : new String[] {"{}", "{\"enableChunkGeneration\":true}",
                "{\"enableChunkGeneration\":false}"}) {
            java.nio.file.Files.writeString(file, saved);
            boolean expected = saved.equals("{}") ? expectedDefault : saved.contains(":true");
            assertEquals(expected, store.load(VSSServerConfig.class, "vss-server-config.json").enableChunkGeneration);
            assertEquals(expected, com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file))
                    .getAsJsonObject().get("enableChunkGeneration").getAsBoolean());
        }
    }

    @TempDir Path directory;
    @Test void defaultsSeparateIntegratedAndDedicatedGeneration() {
        var config=new VSSServerConfig();
        assertEquals(2,config.effectiveGenerationConcurrencyLimitPerPlayer(true));
        assertEquals(4,config.effectiveGenerationConcurrencyLimitPerPlayer(false));
        assertEquals(0,config.chunkyQueueLimit,"explicit pregeneration stays unlimited");
    }
    @Test void oldConfigsKeepTheirDedicatedOverrideAndReceiveTheLocalDefault() {
        var config=new Gson().fromJson("{\"generationConcurrencyLimitPerPlayer\":11}",VSSServerConfig.class);
        assertEquals(2,config.effectiveGenerationConcurrencyLimitPerPlayer(true));
        assertEquals(11,config.effectiveGenerationConcurrencyLimitPerPlayer(false));
        config.singleplayerGenerationConcurrencyLimit=3;
        assertEquals(3,config.effectiveGenerationConcurrencyLimitPerPlayer(true));
        assertEquals(11,config.effectiveGenerationConcurrencyLimitPerPlayer(false));
    }
    @Test void independentValuesAreClampedAndSavedWithAccurateHelp() throws Exception {
        var config=new VSSServerConfig();config.configVersion=VSSServerConfig.CURRENT_CONFIG_VERSION;
        config.singleplayerGenerationConcurrencyLimit=0;config.generationConcurrencyLimitPerPlayer=9;
        var validate=VSSServerConfig.class.getDeclaredMethod("validate");validate.setAccessible(true);validate.invoke(config);
        assertEquals(1,config.singleplayerGenerationConcurrencyLimit);assertEquals(9,config.generationConcurrencyLimitPerPlayer);
        config.singleplayerGenerationConcurrencyLimit=Integer.MAX_VALUE;validate.invoke(config);
        assertEquals(VSSServerConfig.MAX_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER,config.singleplayerGenerationConcurrencyLimit);
        new JsonConfigStore(directory).save(config);
        var json=JsonParser.parseString(Files.readString(directory.resolve("vss-server-config.json"))).getAsJsonObject();
        assertEquals(config.singleplayerGenerationConcurrencyLimit,json.get("singleplayerGenerationConcurrencyLimit").getAsInt());
        assertTrue(json.getAsJsonObject("_help").get("singleplayerGenerationConcurrencyLimit").getAsString().contains("默认 2"));
        assertTrue(json.getAsJsonObject("_help").get("generationConcurrencyLimitPerPlayer").getAsString().contains("独立服务器"));
        assertEquals(9,new Gson().fromJson(json,VSSServerConfig.class).generationConcurrencyLimitPerPlayer);
    }
}
