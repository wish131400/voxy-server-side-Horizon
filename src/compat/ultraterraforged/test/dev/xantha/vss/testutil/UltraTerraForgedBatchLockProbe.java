package dev.xantha.vss.testutil;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Observes real batch entry without changing its terrain or filtering instructions. */
public final class UltraTerraForgedBatchLockProbe {
    private static final Map<Object, Expected> EXPECTED = new IdentityHashMap<>();
    private record Expected(StampedLock lock, Object ownedCache, long key, Thread caller, AtomicInteger batches) { }
    private UltraTerraForgedBatchLockProbe() { }

    public static byte[] observe(byte[] transformed) {
        var node = new ClassNode(); new ClassReader(transformed).accept(node, 0);
        int hooked = 0;
        for (var method : node.methods) if (method.name.startsWith("lambda$generate$")
                && method.desc.startsWith("(IIL") && method.desc.endsWith("/tile/Tile;)V")) {
            var instructions = new InsnList(); instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    UltraTerraForgedBatchLockProbe.class.getName().replace('.', '/'), "batch", "(Ljava/lang/Object;)V", false));
            method.instructions.insert(instructions); hooked++;
        }
        if (hooked != 1) throw new AssertionError("Expected one released terrain batch body, found " + hooked);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }

    public static synchronized AtomicInteger expect(Object context, int tileX, int tileZ) throws Exception {
        Object generator = context.getClass().getField("generator").get(context);
        Object tileCache = context.getClass().getField("cache").get(context);
        Object cache = field(tileCache, "cache"), map = field(cache, "map");
        StampedLock lock = (StampedLock) field(map, "lock");
        AtomicInteger count = new AtomicInteger();
        long key = (long) tileX << 32 | tileZ & 0xffffffffL;
        // Use the release's actual coordinate convention rather than assuming it.
        Class<?> pos = generator.getClass().getClassLoader().loadClass(
                "com.pandaismyname1.ultraterraforged.world.worldgen.util.PosUtil");
        key = (long) pos.getMethod("pack", int.class, int.class).invoke(null, tileX, tileZ);
        EXPECTED.put(generator, new Expected(lock, cache, key, Thread.currentThread(), count));
        return count;
    }

    public static void batch(Object generator) throws Exception {
        Expected expected;
        synchronized (UltraTerraForgedBatchLockProbe.class) { expected = EXPECTED.get(generator); }
        if (expected == null) return; // Upstream server scheduling is exercised without changing it.
        if (Thread.currentThread() != expected.caller) throw new AssertionError("Owned batch left its admitted prediction thread");
        long stamp = expected.lock.tryReadLock();
        if (stamp == 0) throw new AssertionError("UTF terrain batch started while the tile-cache write lock was held");
        expected.lock.unlockRead(stamp);
        Object entry = expected.ownedCache.getClass().getMethod("get", long.class).invoke(expected.ownedCache, expected.key);
        if (entry == null) throw new AssertionError("The original pending entry must be published before generation starts");
        if ((boolean) entry.getClass().getMethod("isDone").invoke(entry))
            throw new AssertionError("Current batch must belong to the original pending generation future");
        expected.batches.incrementAndGet();
    }

    public static synchronized void forget(Object context) throws Exception {
        EXPECTED.remove(context.getClass().getField("generator").get(context));
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
}
