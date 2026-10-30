package dev.xantha.vss.compat.ftbchunks;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.net.JarURLConnection;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class FtbMinecraftMixinApplicationTest {
    @Test void onlyTheUnusedEagerLoadIsReplacedInVanillaForcedTicketPersistence() throws Exception {
        var resource = getClass().getClassLoader().getResource("net/minecraft/server/level/ServerLevel.class");
        assertNotNull(resource);
        var connection = (JarURLConnection) resource.openConnection();
        String config = """
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
                 "compatibilityLevel":"JAVA_17",
                 "mixins":["minecraft.FTBChunksForceLoadNoBlockingMixin"],
                 "injectors":{"defaultRequire":1}}
                """;
        var harness = new VoxyMixinHarness(Path.of(connection.getJarFileURL().toURI()).toString(), config);
        var transformed = new ClassNode();
        new ClassReader(harness.apply("net.minecraft.server.level.ServerLevel")).accept(transformed, 0);
        var method = transformed.methods.stream().filter(m -> m.name.equals("setChunkForced")
                && m.desc.equals("(IIZ)Z")).findFirst().orElseThrow();
        int eagerLoads = 0, guardedLoads = 0, persistentTicketCalls = 0;
        for (var insn : method.instructions) if (insn instanceof MethodInsnNode call) {
            if (call.name.equals("getChunk") && call.desc.equals("(II)Lnet/minecraft/world/level/chunk/LevelChunk;")) eagerLoads++;
            if (call.name.contains("vss$useForcedTicket")) guardedLoads++;
            if (call.name.equals("updateChunkForced")) persistentTicketCalls++;
        }
        assertEquals(0, eagerLoads);
        assertEquals(1, guardedLoads);
        assertEquals(1, persistentTicketCalls);
        for (var m : transformed.methods) new Analyzer<>(new BasicVerifier()).analyze(transformed.name, m);
    }
}
