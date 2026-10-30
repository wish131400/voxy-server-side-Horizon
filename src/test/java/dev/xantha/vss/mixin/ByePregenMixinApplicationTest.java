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

/** Isolated Sponge state for ByePregen's real released overloads. */
class ByePregenMixinApplicationTest {
    @Test
    @EnabledIfSystemProperty(named = "vss.byepregenJar", matches = ".+")
    void predicateHookAppliesToTheActualReleasedOverload() throws Exception {
        var harness = new VoxyMixinHarness(System.getProperty("vss.byepregenJar"),
                "{\"required\":true,\"minVersion\":\"0.8\",\"package\":\"dev.xantha.vss.mixin.client\","
                        + "\"client\":[\"ByePregenPredictionPredicateMixin\"],\"injectors\":{\"defaultRequire\":1}}");
        ClassNode node = new ClassNode();
        new ClassReader(harness.apply("com.moepus.byepregen.worldgen.feature.FastBlockPredicateOptimizer"))
                .accept(node, 0);
        var hooked = node.methods.stream().filter(m -> m.name.equals("getState")
                && java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof MethodInsnNode call && call.name.contains("vss$virtualTerrainPredicate")))
                .toList();
        assertEquals(1, hooked.size(), "Exactly the supported predicate overload must be intercepted");
        new Analyzer<>(new BasicVerifier()).analyze(node.name, hooked.get(0));
    }
}
