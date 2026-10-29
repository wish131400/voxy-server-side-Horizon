package dev.xantha.vss.networking.server.generation;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xantha.vss.config.VSSServerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkyQueueLimitConfigTest {
    @TempDir Path directory;
    @BeforeAll
    static void initializeConfigDirectory() {

        System.setProperty("vss.configDir", "build/tmp/fabric-test-config");
    }

    @Test
    void missingSettingKeepsOldAndNewConfigsUnlimited() {
        assertEquals(0, new VSSServerConfig().chunkyQueueLimit);
        var old = new Gson().fromJson("{\"enableChunkGeneration\":true}", VSSServerConfig.class);
        assertEquals(0, old.chunkyQueueLimit);
    }

    @Test
    void queueLimitIsValidatedAndSavedWithHelp() throws Exception {
        var config = new Gson().fromJson("{\"chunkyQueueLimit\":32}", VSSServerConfig.class);
        var validate = VSSServerConfig.class.getDeclaredMethod("validate");
        validate.setAccessible(true);
        validate.invoke(config);
        assertEquals(32, config.chunkyQueueLimit);
        String previousDirectory = System.getProperty("vss.configDir");
        JsonObject saved;
        try {
            System.setProperty("vss.configDir", directory.toString());
            config.save();
            saved = JsonParser.parseString(Files.readString(directory.resolve("vss-server-config.json"))).getAsJsonObject();
        } finally {
            if (previousDirectory == null) System.clearProperty("vss.configDir");
            else System.setProperty("vss.configDir", previousDirectory);
        }
        assertEquals(32, saved.get("chunkyQueueLimit").getAsInt());
        assertTrue(saved.getAsJsonObject("_help").get("chunkyQueueLimit").getAsString().contains("默认 0"));
        assertEquals(32, new Gson().fromJson(saved, VSSServerConfig.class).chunkyQueueLimit);
        config.chunkyQueueLimit = -1;
        validate.invoke(config);
        assertEquals(0, config.chunkyQueueLimit);
        config.chunkyQueueLimit = Integer.MAX_VALUE;
        validate.invoke(config);
        assertEquals(Integer.MAX_VALUE, config.chunkyQueueLimit);
    }
}
