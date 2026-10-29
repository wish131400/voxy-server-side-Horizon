package dev.xantha.vss.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.network.FriendlyByteBuf;

import net.minecraft.world.entity.Entity;

/** Optional entity spawn-data methods, without a hard dependency on another loader's entity interface. */
public final class EntitySpawnDataBridge {
    private record Methods(Method write, Method read) { }
    private static final ClassValue<Methods> METHODS = new ClassValue<>() {
        @Override protected Methods computeValue(Class<?> type) {
            return new Methods(find(type, "writeSpawnData"), find(type, "readSpawnData"));
        }
    };
    private EntitySpawnDataBridge() { }

    private static Method find(Class<?> type, String name) {
        try { return type.getMethod(name, FriendlyByteBuf.class); }
        catch (NoSuchMethodException ignored) { return null; }
    }
    public static boolean canWrite(Entity entity) { return METHODS.get(entity.getClass()).write() != null; }
    public static boolean canRead(Entity entity) { return METHODS.get(entity.getClass()).read() != null; }
    public static void write(Entity entity, FriendlyByteBuf buffer) { invoke(METHODS.get(entity.getClass()).write(), entity, buffer); }
    public static void read(Entity entity, FriendlyByteBuf buffer) { invoke(METHODS.get(entity.getClass()).read(), entity, buffer); }
    private static void invoke(Method method, Entity entity, FriendlyByteBuf buffer) {
        if (method == null) throw new IllegalStateException("Entity has no compatible spawn-data method: " + entity.getType());
        try { method.invoke(entity, buffer); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Entity spawn data failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot invoke entity spawn-data method", failure);
        }
    }
}
