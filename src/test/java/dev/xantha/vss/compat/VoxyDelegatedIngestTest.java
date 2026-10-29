package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.mixin.voxy.StrictVoxyIngestPlugin;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

class VoxyDelegatedIngestTest {
    @Test void delegatedPublicationTracksOnlyRawScopesAndFinishesSuccessFailureAndRejection() throws Throwable {
        VoxyIngestTracker.reset();
        String name=IngestDirectQueueFixture.class.getName();
        ClassNode node=new ClassNode();
        try(var in=getClass().getClassLoader().getResourceAsStream(name.replace('.','/')+".class")) {
            new ClassReader(Objects.requireNonNull(in)).accept(node,0);
        }
        assertTrue(StrictVoxyIngestPlugin.instrument(node));
        ClassWriter writer=new ClassWriter(0);node.accept(writer);
        byte[] root=writer.toByteArray();
        ClassLoader loader=new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String requested,boolean resolve) throws ClassNotFoundException {
                if(!requested.startsWith(name))return super.loadClass(requested,resolve);
                synchronized(getClassLoadingLock(requested)) {
                    Class<?> type=findLoadedClass(requested);
                    if(type==null) {
                        try {
                            byte[] bytes=requested.equals(name)?root:getParent().getResourceAsStream(requested.replace('.','/')+".class").readAllBytes();
                            type=defineClass(requested,bytes,0,bytes.length);
                        } catch(java.io.IOException e) {throw new ClassNotFoundException(requested,e);}
                    }
                    if(resolve)resolveClass(type);return type;
                }
            }
        };
        Class<?> type=loader.loadClass(name);Object instance=type.getConstructor().newInstance();
        var raw=type.getMethod("rawIngest0",Object.class);
        var process=type.getMethod("processJob");
        Object nativeOnly=new Object();
        assertEquals(true,raw.invoke(instance,nativeOnly));
        assertEquals(0,VoxyIngestTracker.taskCount());
        process.invoke(instance);
        Object tracked=new Object();
        assertTrue(VoxyIngestTracker.runRawIngest(tracked,3,4,()->(boolean)raw.invoke(instance,tracked)));
        assertEquals(1,VoxyIngestTracker.pendingSection(tracked));
        process.invoke(instance);
        assertEquals(0,VoxyIngestTracker.pendingSection(tracked));
        RuntimeException failure=new RuntimeException("native body");
        assertTrue(VoxyIngestTracker.runRawIngest(failure,3,4,()->(boolean)raw.invoke(instance,failure)));
        assertSame(failure,assertThrows(InvocationTargetException.class,()->process.invoke(instance)).getCause());
        assertEquals(0,VoxyIngestTracker.taskCount());
        assertFalse(VoxyIngestTracker.workerScopeActive());
        type.getField("live").setBoolean(instance,false);
        assertFalse(VoxyIngestTracker.runRawIngest(tracked,3,4,()->(boolean)raw.invoke(instance,tracked)));
        assertEquals(0,VoxyIngestTracker.taskCount());
        assertEquals(0,type.getField("refs").getInt(instance));
    }
}
