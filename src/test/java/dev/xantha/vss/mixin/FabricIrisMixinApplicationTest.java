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

/** Actual release bytecode; no OpenGL context or shader pack is created. */
@EnabledIfSystemProperty(named = "vss.irisJar", matches = ".+")
class FabricIrisMixinApplicationTest {
    @Test
    void diagnosticsObservePipelineBoundariesAndTheCurrentEntityProgramBind() throws Exception {
        var harness = new VoxyMixinHarness(System.getProperty("vss.irisJar"), """
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
                 "compatibilityLevel":"JAVA_21","client":["client.IrisPredictionCaptureMixin",
                 "client.IrisPredictionShaderCaptureMixin"]}
                """);
        var pipeline = apply(harness, "net.irisshaders.iris.pipeline.IrisRenderingPipeline");
        assertCalls(pipeline, "beginTranslucents", "checkpoint", 2);
        assertCalls(pipeline, "finalizeLevelRendering", "checkpoint", 2);
        assertCalls(pipeline, "finalizeLevelRendering", "finish", 1);
        var shader = apply(harness, "net.irisshaders.iris.pipeline.programs.ExtendedShader");
        assertCalls(shader, "iris$setupState", "shaderApplied", 1);
    }

    private static ClassNode apply(VoxyMixinHarness harness, String target) throws Exception {
        var node = new ClassNode();
        new ClassReader(harness.apply(target)).accept(node, 0);
        for (var method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        return node;
    }

    private static void assertCalls(ClassNode target, String method, String bridge, int count) {
        var node = target.methods.stream().filter(candidate -> candidate.name.equals(method)).findFirst().orElseThrow();
        long calls = java.util.stream.StreamSupport.stream(node.instructions.spliterator(), false)
                .filter(insn -> insn instanceof MethodInsnNode call && call.name.contains("vss$"))
                .map(insn -> (MethodInsnNode) insn)
                .flatMap(call -> target.methods.stream().filter(handler -> handler.name.equals(call.name)
                        && handler.desc.equals(call.desc)))
                .flatMap(handler -> java.util.stream.StreamSupport.stream(handler.instructions.spliterator(), false))
                .filter(insn -> insn instanceof MethodInsnNode call && call.name.equals(bridge)
                        && call.owner.equals("dev/xantha/vss/client/prediction/PredictionRenderCapture")).count();
        assertEquals(count, calls, target.name + "." + method + " must install every capture point");
    }
}
