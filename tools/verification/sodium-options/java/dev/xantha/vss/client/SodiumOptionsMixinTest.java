package dev.xantha.vss.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

class SodiumOptionsMixinTest {
    @Test void productionMixinAddsPagesToRealLegacyScreenConstructors() throws Exception {
        String jar = System.getProperty("vss.sodiumJar");
        String target = null;
        try (var zip = new ZipFile(jar)) {
            for (String root : new String[]{"me/jellysquid/mods/sodium", "net/caffeinemc/mods/sodium"}) {
                String name = root + "/client/gui/SodiumOptionsGUI";
                if (zip.getEntry(name + ".class") != null) target = name;
            }
            // New Sodium owns registration through ConfigEntryPoint; it must not get this hook.
            if (target == null) {
                assertNotNull(zip.getEntry("net/caffeinemc/mods/sodium/api/config/ConfigEntryPoint.class"));
                return;
            }
        }
        var resource = getClass().getClassLoader().getResourceAsStream("vss.compat.mixins.json");
        var config = JsonParser.parseString(new String(java.util.Objects.requireNonNull(resource).readAllBytes(),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(config.getAsJsonArray("client").toString().contains("client.SodiumLegacyOptionsMixin"));
        config.add("mixins", JsonParser.parseString("[]"));
        config.add("client", JsonParser.parseString("[\"client.SodiumLegacyOptionsMixin\"]"));
        var harness = new VoxyMixinHarness(jar, config.toString());
        ClassNode node = new ClassNode();
        new ClassReader(harness.apply(target.replace('/', '.'))).accept(node, 0);
        MethodNode handler = node.methods.stream().filter(m -> m.name.contains("vss$addOptions")).findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(handler.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof MethodInsnNode call
                        && call.owner.equals("dev/xantha/vss/client/VSSVoxyOptionsIntegration")
                        && call.name.equals("addPage") && call.desc.equals("(Ljava/lang/Object;)V")));
        for (MethodNode constructor : node.methods) if (constructor.name.equals("<init>")) {
            assertTrue(java.util.stream.StreamSupport.stream(constructor.instructions.spliterator(), false)
                    .anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals(handler.name)));
        }
    }
}
