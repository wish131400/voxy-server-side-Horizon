package dev.xantha.vss.client;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class SodiumThreadOptionsMixinTest {
    @Test void productionPendingHooksApplyToActualSodium08() throws Exception {
        String jar = System.getProperty("vss.sodiumJar");
        String target = "net.caffeinemc.mods.sodium.client.config.structure.StatefulOption";
        boolean modern;
        try (var zip = new ZipFile(jar)) {
            modern = zip.getEntry(target.replace('.', '/') + ".class") != null;
            if (!modern) for (String prefix : new String[]{"me.jellysquid.mods.sodium", "net.caffeinemc.mods.sodium"}) {
                String old = prefix + ".client.gui.options.OptionImpl";
                if (zip.getEntry(old.replace('.', '/') + ".class") != null) { target = old; break; }
            }
        }
        String mixin = modern ? "SodiumPredictionThreadOptionsMixin" : "LegacyPredictionThreadOptionsMixin";
        var harness = new VoxyMixinHarness(jar, ("""
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin.client",
                 "compatibilityLevel":"JAVA_17","client":["%s"],
                 "injectors":{"defaultRequire":1}}
                """).formatted(mixin));
        var node = new ClassNode(); new ClassReader(harness.apply(target)).accept(node, 0);
        String[][] hooks = modern ? new String[][]{{"loadValueInitial", "vss$linkPredictionControls", "registerSodium08"},
                {"modifyValue", "vss$predictionThreadsChanged", "changed"}}
                : new String[][]{{"setValue", "vss$predictionThreadsChanged", "changed"}};
        for (String[] hook : hooks) {
            var handler = node.methods.stream().filter(m -> m.name.contains(hook[1])).findFirst().orElseThrow();
            assertTrue(java.util.stream.StreamSupport.stream(handler.instructions.spliterator(), false)
                    .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals("dev/xantha/vss/client/VSSPredictionThreadOptions") && call.name.equals(hook[2])));
            assertTrue(node.methods.stream().filter(m -> m.name.equals(hook[0])).anyMatch(m ->
                    java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false)
                            .anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals(handler.name))));
        }
    }
}
