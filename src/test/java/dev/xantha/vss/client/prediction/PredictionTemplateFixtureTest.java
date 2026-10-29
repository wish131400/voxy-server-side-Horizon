package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionTemplateFixtureTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void actualLargeReleasedBuildingTemplatesDecodeInsteadOfBeingSilentlyTruncated() throws Exception {
        String property = System.getProperty("vss.templateJars");
        assumeTrue(property != null, "Supply actual structure mod jars with -PvssTemplateJars");
        int count = 0;
        for (String path : property.split(";")) try (var jar = new JarFile(path)) {
            var largest = jar.stream().filter(entry -> entry.getName().matches("data/[^/]+/structure/.+\\.nbt"))
                    .max(java.util.Comparator.comparingLong(java.util.jar.JarEntry::getSize)).orElse(null);
            if (largest == null || largest.getSize() <= 524_288) continue;
            byte[] data;
            try (var input = jar.getInputStream(largest)) { data = input.readAllBytes(); }
            String[] parts = largest.getName().split("/", 4);
            Identifier id = Identifier.parse(parts[1] + ":" + parts[3].substring(0, parts[3].length() - 4));
            var legacy = new JsonObject();
            legacy.addProperty(id.toString(), java.util.Base64.getEncoder().encodeToString(data));
            var manager = PredictionStructureTemplates.open(legacy);
            assertTrue(manager.get(id).isPresent(), id.toString());
            var field = PredictionStructureTemplates.class.getDeclaredField("decodedBytes"); field.setAccessible(true);
            System.out.println("TEMPLATE_NBT " + id + " compressed=" + data.length + " accounted=" + field.getLong(manager));
            assertTrue(field.getLong(manager) <= PredictionStructureTemplates.MAX_NBT_BYTES);
            count++;
        }
        assertTrue(count >= 2, "Expected WDA and YUNG large templates");
    }
}
