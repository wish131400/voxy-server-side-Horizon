package dev.xantha.vss.compat.ftbchunks;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Inspects original Fabric releases in either intermediary or named form. */
class FtbActualJarContractTest {
    @Test
    @EnabledIfSystemProperty(named = "vss.ftbChunksJar", matches = ".+")
    void actualFabricChunkBridgeMatchesOneSupportedPlatformContract() throws Exception {
        try (var jar = new JarFile(Path.of(System.getProperty("vss.ftbChunksJar")).toFile())) {
            assertNotNull(jar.getJarEntry("fabric.mod.json"), "A Fabric release is required");
            String old = "dev/ftb/mods/ftbchunks/fabric/FTBChunksExpectedImpl";
            ClassNode node;
            MethodNode method;
            if (jar.getJarEntry(old + ".class") != null) {
                node = read(jar, old);
                method = node.methods.stream().filter(m -> m.name.equals("addChunkToForceLoaded")
                        && m.desc.contains("Ljava/lang/String;Ljava/util/UUID;IIZ)V")).findFirst().orElseThrow();
            } else {
                node = read(jar, "dev/ftb/mods/ftbchunks/fabric/FTBChunksFabric");
                method = node.methods.stream().filter(m -> m.name.equals("lambda$onInitialize$0")
                        && m.desc.endsWith("Ljava/util/UUID;IIZ)V")).findFirst().orElseThrow();
            }
            assertEquals(1, calls(method, Set.of("setChunkForced", "method_17988")),
                    "The hook must retain exactly one original persistent vanilla force-load operation");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.ftbEssentialsJar", matches = ".+")
    void actualRandomTeleportKeepsItsRealQueriesWithoutTheOldFakeTerrainRedirect() throws Exception {
        try (var jar = new JarFile(Path.of(System.getProperty("vss.ftbEssentialsJar")).toFile())) {
            assertNotNull(jar.getJarEntry("fabric.mod.json"), "A Fabric release is required");
            String old = "dev/ftb/mods/ftbessentials/command/TeleportCommands";
            ClassNode node = read(jar, jar.getJarEntry(old + ".class") != null ? old
                    : "dev/ftb/mods/ftbessentials/commands/groups/TeleportingCommands");
            MethodNode method = node.methods.stream().filter(m -> m.name.equals("findBlockPos"))
                    .findFirst().orElseThrow();
            assertEquals(1, calls(method, Set.of("getBiome", "method_23753")));
            assertEquals(1, calls(method, Set.of("getChunkAt", "method_8500")));
            assertEquals(1, calls(method, Set.of("getHeightmapPos", "method_8598")));
            // Verify the real upstream query, rather than synthesizing plains/height zero.
            for (var insn : method.instructions) if (insn instanceof MethodInsnNode call
                    && Set.of("getChunkAt", "method_8500").contains(call.name)) {
                var next = insn.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                assertNotNull(next);
                assertEquals(org.objectweb.asm.Opcodes.POP, next.getOpcode());
            }
        }
        String config = java.nio.file.Files.readString(Path.of("src/main/resources/vss.compat.mixins.json"));
        assertFalse(config.contains("ftbessentials.TeleportCommandsNoBlockingMixin"),
                "RTP must retain the real terrain search in unloaded chunks");
    }

    private static long calls(MethodNode method, Set<String> names) {
        long count = 0;
        for (var insn : method.instructions) if (insn instanceof MethodInsnNode call
                && names.contains(call.name)) count++;
        return count;
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        var node = new ClassNode();
        try (var in = jar.getInputStream(java.util.Objects.requireNonNull(jar.getJarEntry(name + ".class")))) {
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }
}
