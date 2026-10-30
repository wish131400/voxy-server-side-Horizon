package dev.xantha.vss.compat.ftbchunks;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Sponge applies our production hooks to a real Fabric release remapped only for offline tests. */
class FtbPlatformMixinApplicationTest {
    @Test
    @EnabledIfSystemProperty(named = "vss.ftbChunksNamedJar", matches = ".+")
    void actualPlatformHookIsAppliedAndCannotSilentlySkipItsTarget() throws Exception {
        String path = System.getProperty("vss.ftbChunksNamedJar");
        String legacy = "dev.ftb.mods.ftbchunks.fabric.FTBChunksExpectedImpl";
        boolean old;
        try (var jar = new JarFile(Path.of(path).toFile())) {
            old = jar.getJarEntry(legacy.replace('.', '/') + ".class") != null;
        }
        String target = old ? legacy : "dev.ftb.mods.ftbchunks.fabric.FTBChunksFabric";
        String mixin = old ? "FTBChunksExpectedImplMixin" : "FTBChunksModernForceLoadMixin";
        String config = """
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
                 "compatibilityLevel":"JAVA_17","mixins":["ftbchunks.%s"],
                 "injectors":{"defaultRequire":1}}
                """.formatted(mixin);
        var harness = new VoxyMixinHarness(path, config);
        var transformed = new ClassNode();
        new ClassReader(harness.apply(target)).accept(transformed, 0);
        int bridgeCalls = 0;
        for (var method : transformed.methods) {
            for (var insn : method.instructions) if (insn instanceof MethodInsnNode call
                    && call.owner.equals("dev/xantha/vss/compat/ftbchunks/FTBChunksForceLoadCompat")
                    && call.name.equals(old ? "tryHandle" : "tryHandleVanilla")) bridgeCalls++;
            new Analyzer<>(new BasicVerifier()).analyze(transformed.name, method);
        }
        assertEquals(1, bridgeCalls, "The actual FTB platform must invoke the queue exactly once");
        var targetMethod = transformed.methods.stream().filter(m -> m.name.equals(old
                ? "addChunkToForceLoaded" : "lambda$onInitialize$0")).findFirst().orElseThrow();
        long guarded = 0, original = 0;
        for (var insn : targetMethod.instructions) if (insn instanceof MethodInsnNode call) {
            if (call.name.contains("vss$budgetForceLoad")) guarded++;
            if (call.name.equals("setChunkForced")) original++;
        }
        assertEquals(1, guarded);
        assertEquals(old ? 1 : 0, original,
                "Legacy keeps its entire platform body; modern retains the original through its fallback handler");
    }
}
