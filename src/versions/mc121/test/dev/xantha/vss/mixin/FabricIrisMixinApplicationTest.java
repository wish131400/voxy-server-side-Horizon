package dev.xantha.vss.mixin;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Applies the production hooks to the actual Iris 1.8 release, without a GL context. */
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
        assertCalls(shader, "bindFramebuffer", "shaderApplied", 1);
        assertCompleteProgramBind(shader);
    }

    private static void assertCompleteProgramBind(ClassNode shader) {
        // In this published intermediary JAR, ShaderInstance.apply is
        // method_34586. Assert that the selected Iris-owned boundary is reached
        // once, only by apply, after every part of the draw state is installed.
        var apply = method(shader, "method_34586");
        var stateCalls = java.util.stream.StreamSupport.stream(apply.instructions.spliterator(), false)
                .filter(insn -> insn instanceof MethodInsnNode call && call.owner.equals(shader.name))
                .map(insn -> ((MethodInsnNode) insn).name)
                .filter(name -> List.of("setupTextures", "updateMatrices", "updateUniforms", "applyBlendModes",
                        "bindFramebuffer").contains(name)).toList();
        assertEquals(List.of("setupTextures", "updateMatrices", "updateUniforms", "applyBlendModes",
                "bindFramebuffer"), stateCalls);
        long callers = shader.methods.stream()
                .flatMap(method -> java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false))
                .filter(insn -> insn instanceof MethodInsnNode call && call.owner.equals(shader.name)
                        && call.name.equals("bindFramebuffer") && call.desc.equals("()V")).count();
        assertEquals(1, callers, "Only the complete program-apply path may trigger the binding capture");

        var calls = java.util.stream.StreamSupport.stream(apply.instructions.spliterator(), false)
                .filter(insn -> insn instanceof MethodInsnNode).map(insn -> (MethodInsnNode) insn).toList();
        int program = -1, textures = -1;
        for (int index = 0; index < calls.size(); index++) {
            var call = calls.get(index);
            if (call.owner.equals("net/minecraft/class_285") && call.name.equals("method_22094")) program = index;
            if (call.owner.equals(shader.name) && call.name.equals("setupTextures")) textures = index;
        }
        assertTrue(program >= 0 && textures > program, "The active GL program must be set before sampler setup");

        var framebuffer = method(shader, "bindFramebuffer");
        int framebufferBinds = 0, captureIndex = -1, lastBindIndex = -1, returnIndex = -1;
        for (int index = 0; index < framebuffer.instructions.size(); index++) {
            var instruction = framebuffer.instructions.get(index);
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("net/irisshaders/iris/gl/framebuffer/GlFramebuffer") && call.name.equals("bind")) {
                    framebufferBinds++;
                    lastBindIndex = index;
                }
                if (call.name.contains("vss$shaderApplied")) captureIndex = index;
            }
            if (instruction.getOpcode() == Opcodes.RETURN) returnIndex = index;
        }
        assertEquals(2, framebufferBinds, "Both opaque and translucent framebuffer branches must remain intact");
        assertTrue(captureIndex > lastBindIndex && returnIndex > captureIndex,
                "Capture must run after the selected draw framebuffer is bound and before returning to apply");
    }

    private static ClassNode apply(VoxyMixinHarness harness, String target) throws Exception {
        var node = new ClassNode();
        new ClassReader(harness.apply(target)).accept(node, 0);
        for (var method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        return node;
    }

    private static MethodNode method(ClassNode target, String name) {
        return target.methods.stream().filter(candidate -> candidate.name.equals(name)).findFirst().orElseThrow();
    }

    private static void assertCalls(ClassNode target, String method, String bridge, int count) {
        var node = method(target, method);
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
