package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Checks the published Fabric implementation without constructing a contraption or renderer. */
class CreateReleaseContractTest {
    @Test
    @EnabledIfSystemProperty(named = "vss.createJar", matches = ".+")
    void actualFabricContraptionExposesTheReadinessContract() throws Exception {
        try (var jar = new JarFile(System.getProperty("vss.createJar"))) {
            var entry = jar.getJarEntry("com/simibubi/create/content/contraptions/AbstractContraptionEntity.class");
            assertNotNull(entry);
            ClassNode node = new ClassNode();
            try (var input = jar.getInputStream(entry)) { new ClassReader(input).accept(node, 0); }
            assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("getContraption")
                    && m.desc.equals("()Lcom/simibubi/create/content/contraptions/Contraption;")
                    && (m.access & Opcodes.ACC_PUBLIC) != 0));
            assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("isReadyForRender")
                    && m.desc.equals("()Z") && (m.access & Opcodes.ACC_PUBLIC) != 0));
        }
    }
}
