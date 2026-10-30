package dev.xantha.vss.mixin;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.testutil.VoxyMixinHarness;
import dev.xantha.vss.testutil.ReleasedNbtTreeFeatures;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Real optional release bytecode; no synthetic feature classes or game constructors. */
class OptionalWorldgenMixinApplicationTest {
    @Test
    @EnabledIfSystemProperty(named = "vss.nbtTreeRuntimeJar", matches = ".+")
    void rawFabricTreeMethodIsSelectedInProductionNamespace() throws Exception {
        ClassNode mixin = new ClassNode();
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "dev/xantha/vss/mixin/client/NbtTreePredictionTemplatesMixin.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(mixin, 0);
        }
        try (var jar = new JarFile(System.getProperty("vss.nbtTreeRuntimeJar"))) {
            for (String name : ReleasedNbtTreeFeatures.classNames(jar)) {
                ClassNode actual = read(jar, name);
                var place = actual.methods.stream().filter(m -> (m.access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0
                        && m.desc.endsWith(")Z") && (m.name.equals("place") || m.name.equals("method_13151")))
                        .findFirst().orElseThrow();
                for (MethodNode handler : mixin.methods) {
                    if (!handler.name.startsWith("vss$")) continue;
                    assertEquals((place.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0,
                            (handler.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0,
                            name + " redirect static modifier: " + handler.name);
                    AnnotationNode redirect = handler.visibleAnnotations.stream()
                            .filter(a -> a.desc.endsWith("/Redirect;")).findFirst().orElseThrow();
                    assertTrue(((List<?>) value(redirect, "method")).contains(place.name),
                            name + " release method " + place.name + " is not selected by " + handler.name);
                }
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.nbtTreeJar", matches = ".+")
    void redirectsRealTreeFeaturesToPredictionTemplateManager() throws Exception {
        var harness = harness("vss.nbtTreeJar", "NbtTreePredictionTemplatesMixin");
        try (var jar = new JarFile(System.getProperty("vss.nbtTreeJar"))) {
            for (String name : ReleasedNbtTreeFeatures.classNames(jar)) {
                ClassNode node = transformed(harness, name);
                MethodNode place = node.methods.stream().filter(m -> m.name.equals("place"))
                        .findFirst().orElseThrow();
                assertEquals(1, calls(place, "vss$avoidLiveWorld"), name);
                assertEquals(1, calls(place, "vss$verifiedTreeTemplates"), name);
                new Analyzer<>(new BasicVerifier()).analyze(node.name, place);
            }
        }
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        throw new AssertionError("Missing annotation value " + name);
    }

    private static VoxyMixinHarness harness(String property, String mixin) throws Exception {
        return new VoxyMixinHarness(Path.of(System.getProperty(property)).toString(),
                "{\"required\":true,\"minVersion\":\"0.8\",\"package\":\"dev.xantha.vss.mixin.client\","
                        + "\"client\":[\"" + mixin + "\"],\"injectors\":{\"defaultRequire\":1}}");
    }

    private static ClassNode transformed(VoxyMixinHarness harness, String name) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(harness.apply(name)).accept(node, 0);
        return node;
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        try (var input = jar.getInputStream(java.util.Objects.requireNonNull(
                jar.getJarEntry(name.replace('.', '/') + ".class")))) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static long calls(MethodNode method, String part) {
        return java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                .filter(i -> i instanceof MethodInsnNode call && call.name.contains(part)).count();
    }
}
