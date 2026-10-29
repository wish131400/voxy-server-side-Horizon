package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class VoxyServerLodMixinApplicationTest {
    @Test void actualNativeStreamInstallsArbitrationCaptureAndLateWorkerRetirement() throws Exception {
        String path=System.getProperty("vss.voxyJar");assertNotNull(path);
        String server="me.cortex.voxy.commonImpl.network.ServerLodStreamer";
        String client="me.cortex.voxy.client.network.ClientLodReceiver";
        try(var zip=new ZipFile(path)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(zip.getEntry(server.replace('.','/')+".class")!=null,
                    "Only tkettela owns a separate native server stream");
        }
        var harness=new VoxyMixinHarness(path,"""
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
                 "compatibilityLevel":"JAVA_17","mixins":["voxy.VoxyServerLodServerMixin",
                 "voxy.VoxyServerLodPlayerStateMixin"],"client":["voxy.VoxyServerLodClientMixin"]}
                """);
        // The public release uses intermediary names, while this JVM contains the named Minecraft JAR.
        // Remap class references only; every native method body and control flow remains the release bytecode.
        Map<String,String> names=Map.of(
                "net/minecraft/class_310","net/minecraft/client/Minecraft",
                "net/minecraft/class_634","net/minecraft/client/multiplayer/ClientPacketListener",
                "net/minecraft/class_2540","net/minecraft/network/FriendlyByteBuf",
                "net/minecraft/class_3222","net/minecraft/server/level/ServerPlayer",
                "net/minecraft/class_3244","net/minecraft/server/network/ServerGamePacketListenerImpl",
                "net/minecraft/class_2826","net/minecraft/world/level/chunk/LevelChunkSection",
                "net/minecraft/class_2804","net/minecraft/world/level/chunk/DataLayer",
                "net/minecraft/class_5321","net/minecraft/resources/ResourceKey",
                "net/minecraft/class_2960","net/minecraft/resources/ResourceLocation");
        for(String target:new String[]{server,server+"$PlayerState",server+"$Outgoing",client}) {
            ClassWriter writer=new ClassWriter(0);
            harness.getClassNode(target).accept(new ClassRemapper(writer,new SimpleRemapper(names)));
            harness.replaceClass(target,writer.toByteArray());
        }
        var stream=apply(harness,server);
        assertCall(stream,"dev/xantha/vss/compat/VoxyServerLodServerCompat","reconcile");
        assertCall(stream,"dev/xantha/vss/compat/VoxyServerLodServerCompat","retireAll");
        assertCall(stream,"dev/xantha/vss/compat/VoxyServerLodServerCompat","owns");
        var receiver=apply(harness,client);
        for(String call:new String[]{"pauseNativeReceiver","beginNativeColumn","finishNativeColumn","ingestNativeSection"})
            assertCall(receiver,"dev/xantha/vss/compat/VoxyServerLodClientCompat",call);
        assertTrue(receiver.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
                .noneMatch(i->i instanceof MethodInsnNode call && call.owner.equals("me/cortex/voxy/common/world/service/VoxelIngestService")
                        && call.name.equals("rawIngest")),"Every native raw section passes through scoped capture");
        var state=apply(harness,server+"$PlayerState");
        assertTrue(state.interfaces.contains("dev/xantha/vss/compat/VoxyServerLodServerCompat$RetirableState"));
        assertCall(state,"dev/xantha/vss/compat/VoxyServerLodServerCompat","releaseOutgoing");
        assertTrue(state.methods.stream().anyMatch(m->m.name.equals("vss$retire")
                && (m.access & org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED)!=0));
        executeNativeRetirement(harness,server,state);
    }

    private static void executeNativeRetirement(VoxyMixinHarness harness,String server,ClassNode state) throws Exception {
        // Load the actual transformed state and actual native record. A small nest host
        // avoids starting the server's networking/generation services in this JVM.
        Map<String,byte[]> classes=new java.util.HashMap<>();
        ClassWriter stateWriter=new ClassWriter(0);state.accept(stateWriter);
        classes.put(server+"$PlayerState",stateWriter.toByteArray());
        ClassWriter outgoingWriter=new ClassWriter(0);harness.getClassNode(server+"$Outgoing").accept(outgoingWriter);
        classes.put(server+"$Outgoing",outgoingWriter.toByteArray());
        ClassWriter host=new ClassWriter(0);
        host.visit(org.objectweb.asm.Opcodes.V17,org.objectweb.asm.Opcodes.ACC_PUBLIC,server.replace('.','/'),null,"java/lang/Object",null);
        host.visitNestMember((server+"$PlayerState").replace('.','/'));
        host.visitNestMember((server+"$Outgoing").replace('.','/'));host.visitEnd();
        classes.put(server,host.toByteArray());
        ClassLoader loader=new ClassLoader(VoxyServerLodMixinApplicationTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                byte[] bytes=classes.get(name);
                if(bytes==null)return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)) {
                    Class<?> type=findLoadedClass(name);
                    if(type==null)type=defineClass(name,bytes,0,bytes.length);
                    if(resolve)resolveClass(type);return type;
                }
            }
        };
        Class<?> stateType=loader.loadClass(server+"$PlayerState"),outgoingType=loader.loadClass(server+"$Outgoing");
        var stateConstructor=stateType.getDeclaredConstructor();stateConstructor.setAccessible(true);
        Object instance=stateConstructor.newInstance();
        var outConstructor=outgoingType.getDeclaredConstructors()[0];outConstructor.setAccessible(true);
        var enqueue=stateType.getDeclaredMethod("enqueue",outgoingType);enqueue.setAccessible(true);
        var outbox=stateType.getDeclaredField("outbox");outbox.setAccessible(true);
        var byteCount=stateType.getDeclaredField("outboxBytes");byteCount.setAccessible(true);
        var first=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());first.writeByte(7);
        enqueue.invoke(instance,outConstructor.newInstance(first,null,0L,0L,0));
        assertEquals(1,((java.util.Queue<?>)outbox.get(instance)).size());
        assertEquals(1,((java.util.concurrent.atomic.AtomicLong)byteCount.get(instance)).get());
        ((VoxyServerLodServerCompat.RetirableState)instance).vss$retire();
        assertEquals(0,first.refCnt());
        assertTrue(((java.util.Queue<?>)outbox.get(instance)).isEmpty());
        assertEquals(0,((java.util.concurrent.atomic.AtomicLong)byteCount.get(instance)).get());
        var late=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());late.writeByte(9);
        enqueue.invoke(instance,outConstructor.newInstance(late,null,0L,0L,0));
        assertEquals(0,late.refCnt(),"An already-running native worker must release output after retirement");
        assertTrue(((java.util.Queue<?>)outbox.get(instance)).isEmpty());
    }

    private static ClassNode apply(VoxyMixinHarness harness,String target) throws Exception {
        var node=new ClassNode();new ClassReader(harness.apply(target)).accept(node,0);
        for(var method:node.methods)new Analyzer<>(new BasicVerifier()).analyze(node.name,method);
        return node;
    }
    private static void assertCall(ClassNode node,String owner,String name) {
        assertTrue(node.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
                .anyMatch(i->i instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)),
                node.name+" must call "+name);
    }
}
