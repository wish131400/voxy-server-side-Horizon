package dev.xantha.vss.mixin;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.testutil.VoxyMixinHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class FreeTerraForgedMixinApplicationTest {
    @Test
    @EnabledIfSystemProperty(named = "vss.freeTerraForgedJar", matches = ".+")
    void nativeFilterHookAppliesToActualFabricWorldFilters() throws Exception {
        var harness = new VoxyMixinHarness(System.getProperty("vss.freeTerraForgedJar"),
                "{\"required\":true,\"minVersion\":\"0.8\",\"package\":\"dev.xantha.vss.mixin.client\","
                        + "\"client\":[\"FreeTerraForgedPredictionFiltersMixin\"],\"injectors\":{\"defaultRequire\":1}}");
        String target;
        try (var jar = new java.util.jar.JarFile(System.getProperty("vss.freeTerraForgedJar"))) {
            if (jar.getJarEntry("etcodehome/freeterraforged/world/worldgen/runtime/TerraForgedChunkGenerator.class") != null) {
                String pipeline = "etcodehome.freeterraforged.world.worldgen.cell.filter.TerrainFilters";
                var entry = jar.getJarEntry(pipeline.replace('.', '/') + ".class");
                assertNotNull(entry, "epoch releases must retain their real Java filter pipeline");
                var original = new ClassNode();
                new ClassReader(jar.getInputStream(entry)).accept(original, 0);
                var transformed = new ClassNode();
                new ClassReader(harness.apply(pipeline)).accept(transformed, 0);
                assertEquals(original.methods.size(), transformed.methods.size());
                assertFalse(transformed.methods.stream().anyMatch(method ->
                        java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                                .anyMatch(i -> i instanceof MethodInsnNode call
                                        && call.owner.contains("vss/") && call.name.contains("nativePredictionFilters"))),
                        "the legacy Rust hook must never intercept the new TerrainFilters algorithm");
                return;
            }
            target = jar.getJarEntry("etcodehome/freeterraforged/world/worldgen/WorldFilters.class") != null
                    ? "etcodehome.freeterraforged.world.worldgen.WorldFilters"
                    : "raccoonman.reterraforged.world.worldgen.WorldFilters";
        }
        ClassNode node = new ClassNode();
        new ClassReader(harness.apply(target))
                .accept(node, 0);
        var hooked = node.methods.stream().filter(m -> m.name.equals("apply")
                && java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof MethodInsnNode call && call.name.contains("vss$nativePredictionFilters")))
                .toList();
        assertEquals(1, hooked.size(), "The real tile filter must call the prediction native filter bridge");
        new Analyzer<>(new BasicVerifier()).analyze(node.name, hooked.get(0));
    }
}
