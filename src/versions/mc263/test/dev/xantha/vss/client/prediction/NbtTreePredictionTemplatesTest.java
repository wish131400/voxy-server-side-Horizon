package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import dev.xantha.vss.mixin.client.NbtTreePredictionTemplatesMixin;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarFile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class NbtTreePredictionTemplatesTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void virtualTreeLookupUsesVerifiedManagerAndNormalWorldGetLevelIsForwarded(@TempDir Path gameDir) throws Exception {
        var methods = NbtTreePredictionTemplatesMixin.class.getDeclaredMethods();
        var world = java.util.Arrays.stream(methods).filter(method -> method.getName().equals("vss$avoidLiveWorld")).findFirst().orElseThrow();
        var templates = java.util.Arrays.stream(methods).filter(method -> method.getName().equals("vss$verifiedTreeTemplates")).findFirst().orElseThrow();
        world.setAccessible(true); templates.setAccessible(true);
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.Identifier.withDefaultNamespace("overworld"), 42, -64, 384, "noise", "minecraft:overworld", 1);
        var sampler = new ClientTerrainSampler(42, profile);
        var level = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, 0, 0);
        assertTrue(java.lang.reflect.Modifier.isStatic(world.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(templates.getModifiers()));
        assertNull(world.invoke(null, level), "No access to a live ServerLevel during prediction");
        var loader = Mockito.spy(FabricLoader.getInstance());
        Mockito.doReturn(gameDir).when(loader).getGameDir();
        try (var fabric = Mockito.mockStatic(FabricLoader.class, Mockito.CALLS_REAL_METHODS)) {
            fabric.when(FabricLoader::getInstance).thenReturn(loader);
            var manager = level.templateManager();
            assertNotNull(manager, "Construct the real verified template manager in a disposable game directory");
            assertSame(manager, templates.invoke(null, null, null, level, RandomSource.create(42), BlockPos.ZERO));
        }
        var forwarded = new java.util.concurrent.atomic.AtomicInteger();
        var normal = (WorldGenLevel) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {WorldGenLevel.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getLevel")) { forwarded.incrementAndGet(); return null; }
                    throw new AssertionError("Unexpected real-world method: " + method);
                });
        assertFalse(PredictionTemplateAccess.virtual(normal));
        assertNull(world.invoke(null, normal));
        assertEquals(1, forwarded.get());
        var liveLevel = Mockito.mock(ServerLevel.class);
        var liveManager = Mockito.mock(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager.class);
        Mockito.when(liveLevel.getStructureTemplateManager()).thenReturn(liveManager);
        assertSame(liveManager, templates.invoke(null, liveLevel, null, normal, RandomSource.create(42), BlockPos.ZERO));
        Mockito.verify(liveLevel).getStructureTemplateManager();
    }

    @Test void everyReleasedTreeFeatureHasExactlyTheRedirectedManagerChain() throws Exception {
        String path = System.getProperty("vss.nbtTreeJar");
        assumeTrue(path != null, "Supply the released OTTYG JAR with -PvssNbtTreeJar");
        var mixin = new ClassNode();
        try (var input = getClass().getResourceAsStream("/dev/xantha/vss/mixin/client/NbtTreePredictionTemplatesMixin.class")) {
            new ClassReader(input).accept(mixin, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        try (var jar = new JarFile(path)) {
            for (String name : dev.xantha.vss.testutil.ReleasedNbtTreeFeatures.classNames(jar)) {
                var node = new ClassNode();
                try (var stream = jar.getInputStream(jar.getJarEntry(name.replace('.', '/') + ".class"))) {
                    new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                int levels = 0, managers = 0;
                for (var method : node.methods) if (method.name.equals("place") || method.name.equals("m_142674_")) {
                    assertTrue((method.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0,
                            "The fixed 26.3 release uses a static tree placement entrypoint");
                    for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                        if (call.owner.equals("net/minecraft/world/level/WorldGenLevel")
                                && (call.name.equals("getLevel") || call.name.equals("m_6018_"))) {
                            levels++;
                            validateRedirect(mixin, "vss$avoidLiveWorld", call, method.desc, false);
                            assertTrue(call.getNext() instanceof MethodInsnNode, "Only the manager consumes the redirected receiver");
                            var next = (MethodInsnNode) call.getNext();
                            assertEquals("net/minecraft/server/level/ServerLevel", next.owner);
                            assertTrue(next.name.equals("getStructureTemplateManager") || next.name.equals("getStructureManager") || next.name.equals("m_215082_"));
                        }
                        if (call.owner.equals("net/minecraft/server/level/ServerLevel")
                                && (call.name.equals("getStructureTemplateManager") || call.name.equals("getStructureManager") || call.name.equals("m_215082_"))) {
                            managers++;
                            validateRedirect(mixin, "vss$verifiedTreeTemplates", call, method.desc, true);
                        }
                    }
                }
                assertEquals(1, levels, name);
                assertEquals(1, managers, name);
            }
        }
    }

    private static void validateRedirect(ClassNode mixin, String name, MethodInsnNode invocation,
                                         String targetMethod, boolean captureContext) {
        var handler = mixin.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
        assertTrue((handler.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0, name);
        var args = new java.util.ArrayList<Type>();
        args.add(Type.getObjectType(invocation.owner));
        args.addAll(java.util.List.of(Type.getArgumentTypes(invocation.desc)));
        // Mixin RedirectInjector.validateParams permits trailing arguments from the enclosing method.
        if (captureContext) args.addAll(java.util.List.of(Type.getArgumentTypes(targetMethod)));
        assertEquals(Type.getReturnType(invocation.desc), Type.getReturnType(handler.desc));
        var actual = Type.getArgumentTypes(handler.desc);
        assertEquals(args.size(), actual.length, name);
        for (int i = 0; i < actual.length; i++) {
            if (captureContext && i == 1) {
                assertEquals("dev/corgitaco/ohthetreesyoullgrow/world/level/levelgen/feature/configurations/TreeFromStructureNBTConfigV2",
                        args.get(i).getInternalName());
                assertEquals(Type.getType(Object.class), actual[i]);
                assertNotNull(handler.invisibleParameterAnnotations);
                assertNotNull(handler.invisibleParameterAnnotations[i]);
                assertTrue(handler.invisibleParameterAnnotations[i].stream()
                        .anyMatch(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Coerce;")));
            } else assertEquals(args.get(i), actual[i], name + " argument " + i);
        }
    }
}
