package dev.xantha.vss.networking.server.generation;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.xantha.vss.config.VSSServerConfig;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationBudgetConfigTest {
    @TempDir Path directory;
    @BeforeAll
    static void initializeConfigDirectory() {

        System.setProperty("vss.configDir", "build/tmp/fabric-test-config");
    }

    @Test
    void oldConfigKeepsNewBudgetsDisabled() {
        var config = new Gson().fromJson("{\"enableChunkGeneration\":true}", VSSServerConfig.class);
        assertEquals(0, config.generationSnapshotsPerTickLimit);
        assertEquals(0, config.generationSnapshotBudgetMillis);
        assertEquals(0, config.generationPauseAboveMspt);
    }

    @Test
    void removedStartLimitIsIgnoredAndDroppedFromSavedConfig() throws Exception {
        var config = new Gson().fromJson("""
                {"generationStartsPerTickLimit":1,"generationSnapshotsPerTickLimit":6,
                 "generationSnapshotBudgetMillis":3,"generationPauseAboveMspt":45}
                """, VSSServerConfig.class);
        assertEquals(new VSSServerConfig().automaticGenerationStartsPerTick(), config.automaticGenerationStartsPerTick());
        assertEquals(6, config.generationSnapshotsPerTickLimit);
        assertEquals(3, config.generationSnapshotBudgetMillis);
        assertEquals(45, config.generationPauseAboveMspt);
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
        assertFalse(saved.has("generationStartsPerTickLimit"));
        assertFalse(saved.getAsJsonObject("_help").has("generationStartsPerTickLimit"));
        assertEquals(6, saved.get("generationSnapshotsPerTickLimit").getAsInt());
    }

    @Test
    void invalidBudgetsAreClampedDuringConfigValidation() throws Exception {
        var config = new VSSServerConfig();
        var validate = VSSServerConfig.class.getDeclaredMethod("validate");
        validate.setAccessible(true);
        config.generationSnapshotsPerTickLimit = -1;
        config.generationSnapshotBudgetMillis = config.generationPauseAboveMspt = -1;
        validate.invoke(config);
        assertEquals(0, config.generationSnapshotsPerTickLimit);
        assertEquals(0, config.generationSnapshotBudgetMillis);
        assertEquals(0, config.generationPauseAboveMspt);
        config.generationSnapshotsPerTickLimit = Integer.MAX_VALUE;
        config.generationSnapshotBudgetMillis = config.generationPauseAboveMspt = Integer.MAX_VALUE;
        validate.invoke(config);
        assertEquals(128, config.generationSnapshotsPerTickLimit);
        assertEquals(50, config.generationSnapshotBudgetMillis);
        assertEquals(1000, config.generationPauseAboveMspt);
    }
}
