package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.mixin.client.PredictionFogCaptureMixin;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.injection.Inject;

class PredictionEffectFogTest {
    @BeforeAll static void bootstrap() { AlgorithmBootstrap.bootstrapMinecraft(); }

    @Test void statusFogUsesTheViewedEntity() {
        Camera camera = mock(Camera.class);
        LivingEntity viewed = mock(LivingEntity.class);
        when(camera.getFluidInCamera()).thenReturn(FogType.ATMOSPHERIC);
        when(camera.entity()).thenReturn(viewed);
        assertFalse(PredictionFogBridge.environmental(camera, false));
        when(viewed.hasEffect(MobEffects.BLINDNESS)).thenReturn(true);
        assertTrue(PredictionFogBridge.environmental(camera, false), "spectating a blinded entity follows its fog");
        when(viewed.hasEffect(MobEffects.BLINDNESS)).thenReturn(false);
        when(viewed.hasEffect(MobEffects.DARKNESS)).thenReturn(true);
        assertTrue(PredictionFogBridge.environmental(camera, false));
        when(camera.entity()).thenReturn(mock(Entity.class));
        assertFalse(PredictionFogBridge.environmental(camera, false), "an unaffected camera does not inherit another player's effects");
    }

    @Test void fluidsAndWorldFogTakePriorityOverDistantHaze() {
        Camera camera = mock(Camera.class);
        for (FogType fluid : FogType.values()) {
            when(camera.getFluidInCamera()).thenReturn(fluid);
            assertEquals(fluid != FogType.ATMOSPHERIC, PredictionFogBridge.environmental(camera, false), fluid.name());
            assertTrue(PredictionFogBridge.environmental(camera, true));
        }
    }

    @Test void captureHookMatchesOriginalFogBeforeGpuUpload() throws Exception {
        var handler = PredictionFogCaptureMixin.class.getDeclaredMethods()[0];
        Inject injection = handler.getAnnotation(Inject.class);
        String target = injection.at()[0].target();
        ClassNode node = new ClassNode();
        try (var source = FogRenderer.class.getResourceAsStream("FogRenderer.class")) {
            assertNotNull(source); new ClassReader(source).accept(node, ClassReader.SKIP_FRAMES);
        }
        int matches = 0;
        for (var method : node.methods) if (method.name.equals("setupFog")) {
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && target.equals("L" + call.owner + ";" + call.name + call.desc)) {
                matches++;
                assertTrue(method.instructions.indexOf(call) > 0);
                assertTrue(java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                        .filter(insn -> method.instructions.indexOf(insn) > method.instructions.indexOf(call))
                        .anyMatch(insn -> insn.getOpcode() == org.objectweb.asm.Opcodes.ARETURN),
                        "the hook observes the computed fog before setupFog returns its color");
            }
        }
        assertEquals(1, matches, "the required injection must match this platform's actual Minecraft bytecode");
        assertEquals(org.spongepowered.asm.mixin.injection.At.Shift.NONE, injection.at()[0].shift(),
                "capture before the GPU buffer upload, at the same site Voxy extends fog");
        ClassNode hook = new ClassNode();
        try (var source = PredictionFogCaptureMixin.class.getResourceAsStream("PredictionFogCaptureMixin.class")) {
            assertNotNull(source); new ClassReader(source).accept(hook, ClassReader.SKIP_CODE);
        }
        var mixin = hook.invisibleAnnotations.stream()
                .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                .findFirst().orElseThrow();
        assertEquals(1100, mixin.values.get(mixin.values.indexOf("priority") + 1),
                "capture before Voxy's default-priority hook mutates the local FogData");
    }
}
