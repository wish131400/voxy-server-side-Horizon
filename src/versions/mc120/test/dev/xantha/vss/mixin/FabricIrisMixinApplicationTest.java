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
                 "compatibilityLevel":"JAVA_17","client":["client.IrisPredictionCaptureMixin",
                 "client.IrisPredictionShaderCaptureMixin"]}
                """);
        var pipeline = apply(harness, "net.irisshaders.iris.pipeline.IrisRenderingPipeline");
        assertCalls(pipeline, "beginTranslucents", "checkpoint", 2);
        assertCalls(pipeline, "finalizeLevelRendering", "checkpoint", 2);
        assertCalls(pipeline, "finalizeLevelRendering", "finish", 1);
        var shader = apply(harness, "net.irisshaders.iris.pipeline.programs.ExtendedShader");
        assertCalls(shader, "method_34586", "shaderApplied", 1);
        var bind = shader.methods.stream().filter(method -> method.name.equals("method_34586")).findFirst().orElseThrow();
        int framebuffer = -1, firstFramebuffer = Integer.MAX_VALUE, capture = -1, index = 0, binds = 0;
        var completeState = new java.util.HashMap<String, Integer>();
        for (var instruction : bind.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("net/irisshaders/iris/gl/framebuffer/GlFramebuffer") && call.name.equals("bind")) {
                    framebuffer = index;
                    firstFramebuffer = Math.min(firstFramebuffer, index);
                    binds++;
                }
                if (call.owner.equals("net/minecraft/class_285") && call.name.equals("method_22094")) completeState.put("program", index);
                if (call.owner.equals("net/irisshaders/iris/gl/program/ProgramSamplers") && call.name.equals("update")) completeState.put("samplers", index);
                if (call.owner.equals("net/irisshaders/iris/gl/program/ProgramUniforms") && call.name.equals("update")) completeState.put("uniforms", index);
                if (call.owner.equals("net/irisshaders/iris/uniforms/custom/CustomUniforms") && call.name.equals("push")) completeState.put("customUniforms", index);
                if (call.owner.equals("net/irisshaders/iris/gl/program/ProgramImages") && call.name.equals("update")) completeState.put("images", index);
                if (call.owner.equals("net/irisshaders/iris/gl/blending/BlendModeOverride") && call.name.equals("apply")) completeState.put("blend", index);
                if (call.name.contains("vss$shaderApplied")) capture = index;
            }
            index++;
        }
        assertEquals(2, binds, "Keep the real before/after translucent framebuffer branches");
        assertEquals(java.util.Set.of("program", "samplers", "uniforms", "customUniforms", "images", "blend"), completeState.keySet());
        for (var state : completeState.entrySet()) assertTrue(state.getValue() < firstFramebuffer,
                state.getKey() + " must be configured before either final framebuffer bind");
        assertTrue(framebuffer >= 0 && capture > framebuffer,
                "Observe the complete Iris 1.7 texture/uniform/framebuffer state after its final bind");
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
