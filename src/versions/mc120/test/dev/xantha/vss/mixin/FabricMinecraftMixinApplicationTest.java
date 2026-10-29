package dev.xantha.vss.mixin;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.net.JarURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Applies the actual configuration to the named Minecraft JAR; never creates a world or GL context. */
class FabricMinecraftMixinApplicationTest {
    @Test
    void lifecycleDirtyCaptureFogAndTransportHooksApplyToMinecraft() throws Exception {
        var config = JsonParser.parseString(Files.readString(Path.of("src/versions/mc120/resources/vss.compat.mixins.json")))
                .getAsJsonObject();
        config.remove("plugin");
        config.remove("refmap");
        config.addProperty("required", true);
        var common = new com.google.gson.JsonArray();
        config.getAsJsonArray("mixins").forEach(entry -> {
            if (entry.getAsString().startsWith("minecraft.")) common.add(entry);
        });
        var client = new com.google.gson.JsonArray();
        config.getAsJsonArray("client").forEach(entry -> {
            if (entry.getAsString().startsWith("client.") && !entry.getAsString().contains("Iris")
                    && !entry.getAsString().contains("FreeTerraForged")
                    && !entry.getAsString().contains("ByePregen")
                    && !entry.getAsString().contains("NbtTree")) client.add(entry);
        });
        config.add("mixins", common);
        config.add("client", client);
        var resource = getClass().getClassLoader().getResource("net/minecraft/client/renderer/FogRenderer.class");
        assertNotNull(resource, "The test must use the actual Minecraft dependency");
        var jar = (JarURLConnection) resource.openConnection();
        var harness = new VoxyMixinHarness(Path.of(jar.getJarFileURL().toURI()).toString(), config.toString());
        var hooks = new LinkedHashMap<String, String[]>();
        hooks.put("net.minecraft.client.Minecraft", new String[]{"apply"});
        hooks.put("net.minecraft.client.renderer.GameRenderer", new String[]{"onRenderFrame"});
        hooks.put("net.minecraft.client.renderer.FogRenderer", new String[]{"captureTerrain"});
        hooks.put("net.minecraft.client.gui.Gui", new String[]{"interpolation"});
        hooks.put("net.minecraft.world.level.Level", new String[]{"markDirtyBlock", "consume"});
        hooks.put("net.minecraft.world.level.chunk.LevelChunk", new String[]{"markDirtyBlock", "onLocalColumnChanged"});
        hooks.put("net.minecraft.server.level.ServerLevel", new String[]{"markDirtyBlock"});
        hooks.put("net.minecraft.client.multiplayer.ClientPacketListener", new String[]{"beginSession"});
        hooks.put("net.minecraft.client.renderer.entity.EntityRenderDispatcher", new String[]{"shouldSkipVanillaPlayer"});
        hooks.put("net.minecraft.client.renderer.LevelRenderer", new String[]{"hasActiveFarPlayerAt"});
        for (var expected : hooks.entrySet()) {
            var node = apply(harness, expected.getKey());
            for (String method : expected.getValue()) assertTrue(node.methods.stream().flatMap(m ->
                    java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                    .anyMatch(insn -> insn instanceof MethodInsnNode call && call.owner.startsWith("dev/xantha/vss/")
                            && call.name.equals(method)), expected.getKey() + " must call " + method);
            if (expected.getKey().endsWith("renderer.FogRenderer")) {
                var setup = node.methods.stream().filter(m -> m.name.equals("setupFog")).findFirst().orElseThrow();
                int capture = -1, device = -1, index = 0;
                for (var insn : setup.instructions) {
                    if (insn instanceof MethodInsnNode call && call.name.contains("vss$captureTerrainFog")) capture = index;
                    if (insn instanceof MethodInsnNode call && call.name.equals("setShaderFogShape") && device < 0) device = index;
                    index++;
                }
                assertTrue(capture >= 0 && capture > device, "Capture the complete vanilla fog immediately after the last setter, before Voxy RETURN hooks");
            }
        }
        assertAccessor(harness, "net.minecraft.network.Connection", "vss$channel", "()Lio/netty/channel/Channel;");
        assertAccessor(harness, "net.minecraft.server.network.ServerGamePacketListenerImpl", "vss$connection",
                "()Lnet/minecraft/network/Connection;");
        assertAccessor(harness, "net.minecraft.server.level.ChunkMap", "vss$getEntityMap",
                "()Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;");
        assertAccessor(harness, "net.minecraft.server.level.ChunkMap$TrackedEntity", "vss$getSeenBy", "()Ljava/util/Set;");
        assertAccessor(harness, "net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper",
                "vss$rebind", "(Lnet/minecraft/world/level/block/entity/TickingBlockEntity;)V");
    }

    private static ClassNode apply(VoxyMixinHarness harness, String target) throws Exception {
        var node = new ClassNode();
        new ClassReader(harness.apply(target)).accept(node, 0);
        for (var method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        return node;
    }

    private static void assertAccessor(VoxyMixinHarness harness, String target, String name, String descriptor)
            throws Exception {
        var node = apply(harness, target);
        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)),
                target + " must retain " + name + descriptor);
    }
}
