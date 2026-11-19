package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Applies the actual production hooks directly to the unchanged intermediary UTF release. */
@EnabledIfSystemProperty(named = "vss.ultraTerraForgedRawJar", matches = ".+")
class UltraTerraForgedSurfaceProductionNamesTest {
    @Test void rawFabricComputeAliasesReceiveBothHooks() throws Exception {
        var harness = new VoxyMixinHarness(System.getProperty("vss.ultraTerraForgedRawJar"), """
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin.client",
                 "compatibilityLevel":"JAVA_17","client":["UltraTerraForgedPredictionNearGroundMixin","UltraTerraForgedPredictionCellConditionMixin"],
                 "injectors":{"defaultRequire":1}}
                """);
        try (var game = new ZipFile(System.getProperty("vss.ultraTerraForgedIntermediaryGame"))) {
            for (var entries = game.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                if (entry.getName().startsWith("net/minecraft/") && entry.getName().endsWith(".class"))
                    harness.replaceClass(entry.getName().substring(0, entry.getName().length()-6).replace('/', '.'), game.getInputStream(entry).readAllBytes());
            }
        }
        for (String type : new String[]{"NearGroundCondition", "CellCondition"}) {
            var node = new ClassNode();
            new ClassReader(harness.apply("com.pandaismyname1.ultraterraforged.world.worldgen.surface.condition." + type)).accept(node, 0);
            String handler = type.equals("NearGroundCondition") ? "vss$previewGround" : "vss$previewCell";
            assertTrue(node.methods.stream().filter(m -> m.name.equals("method_39074")).anyMatch(m ->
                    java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false)
                            .anyMatch(i -> i instanceof MethodInsnNode call && call.name.contains(handler))),
                    "raw intermediary method_39074 must invoke the real preview hook");
        }
        System.out.println("Raw Fabric UTF: both production method_39074 hooks applied by Sponge to unchanged release classes");
    }
}
