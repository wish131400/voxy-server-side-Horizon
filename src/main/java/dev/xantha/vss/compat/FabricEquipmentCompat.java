package dev.xantha.vss.compat;

import dev.xantha.vss.common.VSSLogger;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Optional Fabric equipment providers. The enclosing far-player NBT remains opaque to the wire codec. */
final class FabricEquipmentCompat {
    static final String ENVELOPE = "VssEquipment";
    private static final Provider TRINKETS = Provider.find("dev.emi.trinkets.api.TrinketsApi", "getTrinketComponent");
    private static final Provider ACCESSORIES = Provider.find("io.wispforest.accessories.api.AccessoriesCapability", "get");

    private static final ClassValue<TrinketNbt> TRINKET_NBT = new ClassValue<>() {
        @Override protected TrinketNbt computeValue(Class<?> type) {
            try {
                return new TrinketNbt(nbtMethod(type, "writeToNbt"), type.getMethod("getInventory"));
            } catch (NoSuchMethodException failure) {
                throw new IllegalStateException("Unsupported Trinkets inventory " + type.getName(), failure);
            }
        }
    };
    private static final ClassValue<TrinketInventoryNbt> TRINKET_INVENTORY_NBT = new ClassValue<>() {
        @Override protected TrinketInventoryNbt computeValue(Class<?> type) {
            try {
                return new TrinketInventoryNbt(type.getMethod("getSyncTag"), type.getMethod("applySyncTag", CompoundTag.class));
            } catch (NoSuchMethodException failure) {
                throw new IllegalStateException("Unsupported Trinkets slot " + type.getName(), failure);
            }
        }
    };

    private FabricEquipmentCompat() { }

    static CompoundTag capture(LivingEntity entity) {
        CompoundTag result = new CompoundTag();
        if (TRINKETS != null) {
            try {
                Object component = TRINKETS.get(entity);
                if (component != null) result.put("Trinkets", captureTrinkets(component, entity.level().registryAccess()));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                TRINKETS.report(failure);
            }
        }
        if (ACCESSORIES != null) {
            try {
                Object capability = ACCESSORIES.get(entity);
                if (capability != null) {
                    AccessoriesNbt access = accessoriesAccess();
                    result.put("Accessories", access.capture(access.holder(capability), entity.level().registryAccess()));
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                ACCESSORIES.report(failure);
            }
        }
        return result;
    }

    static boolean apply(LivingEntity entity, CompoundTag equipment) {
        boolean success = true;
        if (equipment.get("Trinkets") instanceof CompoundTag tag && TRINKETS != null) {
            try {
                Object component = TRINKETS.get(entity);
                if (component == null) success = false;
                else applyTrinkets(component, tag, entity.level().registryAccess());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                TRINKETS.report(failure);
                success = false;
            }
        }
        if (equipment.get("Accessories") instanceof CompoundTag tag && ACCESSORIES != null) {
            try {
                Object capability = ACCESSORIES.get(entity);
                if (capability == null) success = false;
                else {
                    AccessoriesNbt access = accessoriesAccess();
                    access.apply(access.holder(capability), capability, tag, entity.level().registryAccess());
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                ACCESSORIES.report(failure);
                success = false;
            }
        }
        // A client without a provider intentionally ignores that provider's payload.
        return success;
    }

    static CompoundTag captureTrinkets(Object component, Object registries) throws ReflectiveOperationException {
        CompoundTag tag = new CompoundTag();
        TrinketNbt access = TRINKET_NBT.get(component.getClass());
        invokeNbt(access.write(), component, tag, registries);
        for (var group : access.inventory(component).entrySet()) {
            CompoundTag groupTag = compound(tag, group.getKey());
            for (var slot : group.getValue().entrySet()) {
                Object inventory = slot.getValue();
                CompoundTag slotTag = compound(groupTag, slot.getKey());
                // Saving uses cached/persistent modifiers; a remote inventory needs the full sync state.
                slotTag.put("Metadata", (CompoundTag) TRINKET_INVENTORY_NBT.get(inventory.getClass()).write().invoke(inventory));
                groupTag.put(slot.getKey(), slotTag);
            }
            tag.put(group.getKey(), groupTag);
        }
        return tag;
    }

    static void applyTrinkets(Object component, CompoundTag tag, Object registries) throws ReflectiveOperationException {
        DynamicOps<Tag> ops = registries instanceof RegistryAccess access
                ? RegistryOps.create(NbtOps.INSTANCE, access) : NbtOps.INSTANCE;
        for (var group : TRINKET_NBT.get(component.getClass()).inventory(component).entrySet()) {
            CompoundTag groupTag = compound(tag, group.getKey());
            for (var slot : group.getValue().entrySet()) {
                Object inventory = slot.getValue();
                Container container = (Container) inventory;
                CompoundTag slotTag = compound(groupTag, slot.getKey());
                // readFromNbt merges storage data and can retain removed items/modifiers. A snapshot replaces it.
                // Clear before resizing so shrinking a slot cannot drop the previous ghost player's items.
                container.clearContent();
                TRINKET_INVENTORY_NBT.get(inventory.getClass()).read().invoke(inventory, compound(slotTag, "Metadata"));
                if (slotTag.get("Items") instanceof ListTag items) {
                    for (int i = 0; i < Math.min(items.size(), container.getContainerSize()); i++) {
                        Tag itemTag = items.get(i);
                        ItemStack stack = itemTag instanceof CompoundTag item && item.isEmpty() ? ItemStack.EMPTY
                                : ItemStack.CODEC.parse(ops, itemTag).result().orElse(ItemStack.EMPTY);
                        container.setItem(i, stack);
                    }
                }
            }
        }
    }

    private static CompoundTag compound(CompoundTag tag, String key) {
        return tag.get(key) instanceof CompoundTag child ? child : new CompoundTag();
    }

    private static Method nbtMethod(Class<?> type, String name) {
        Method legacy = null;
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && method.getReturnType() == void.class
                    && (parameters.length == 1 || parameters.length == 2) && parameters[0] == CompoundTag.class) {
                if (parameters.length == 2) return method;
                legacy = method;
            }
        }
        if (legacy != null) return legacy;
        throw new IllegalStateException("Unsupported Trinkets NBT method " + type.getName() + '.' + name);
    }

    private static void invokeNbt(Method method, Object component, CompoundTag tag, Object registries)
            throws ReflectiveOperationException {
        if (method.getParameterCount() == 1) method.invoke(component, tag);
        else method.invoke(component, tag, registries);
    }

    private record TrinketNbt(Method write, Method getInventory) {
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> inventory(Object component) throws ReflectiveOperationException {
            return (Map<String, Map<String, Object>>) getInventory.invoke(component);
        }
    }

    private record TrinketInventoryNbt(Method write, Method read) { }

    private static volatile AccessoriesNbt accessoriesNbt;

    @FunctionalInterface
    interface Classes { Class<?> get(String name) throws ClassNotFoundException; }

    private static AccessoriesNbt accessoriesAccess() throws ReflectiveOperationException {
        AccessoriesNbt cached = accessoriesNbt;
        if (cached == null) accessoriesNbt = cached = new AccessoriesNbt(FabricEquipmentCompat.class.getClassLoader());
        return cached;
    }

    /** Uses the same holder write/read/init sequence as Accessories' SyncEntireContainer packet. */
    static final class AccessoriesNbt {
        private final Constructor<?> carrierConstructor;
        private final Method carrierTag;
        private final Method write;
        private final Method read;
        private final Method init;
        private final Method getHolder;
        private final Method emptyContext;
        private final Method attributesContext;
        private final Class<?> attributeInstance;
        private final ClassValue<Method> registryContextFactories;

        AccessoriesNbt(ClassLoader loader) throws ReflectiveOperationException {
            this(name -> Class.forName(name, false, loader));
        }

        AccessoriesNbt(Classes classes) throws ReflectiveOperationException {
            Class<?> carrier = classes.get("io.wispforest.accessories.endec.NbtMapCarrier");
            Class<?> map = classes.get("io.wispforest.endec.util.MapCarrier");
            Class<?> context = classes.get("io.wispforest.endec.SerializationContext");
            Class<?> holder = classes.get("io.wispforest.accessories.impl.AccessoriesHolderImpl");
            Class<?> capability = classes.get("io.wispforest.accessories.api.AccessoriesCapability");
            carrierConstructor = carrier.getConstructor(CompoundTag.class);
            carrierTag = carrier.getMethod("compoundTag");
            write = holder.getMethod("write", map, context);
            read = holder.getMethod("read", map, context);
            init = holder.getMethod("init", capability);
            getHolder = capability.getMethod("getHolder");
            emptyContext = context.getMethod("empty");
            Class<?> attribute = null;
            Method attributes = null;
            Class<?> registryAttribute;
            try {
                registryAttribute = classes.get("io.wispforest.owo.serialization.RegistriesAttribute");
            } catch (ClassNotFoundException absentOn120) {
                // Accessories 1.20.1's own sync packet uses SerializationContext.empty().
                registryAttribute = null;
            }
            if (registryAttribute != null) {
                attribute = classes.get("io.wispforest.endec.SerializationAttribute$Instance");
                attributes = context.getMethod("attributes", Array.newInstance(attribute, 0).getClass());
            }
            attributeInstance = attribute;
            attributesContext = attributes;
            Class<?> factoryClass = registryAttribute;
            registryContextFactories = registryAttribute == null ? null : new ClassValue<>() {
                @Override protected Method computeValue(Class<?> registriesClass) {
                    for (Method candidate : factoryClass.getMethods()) {
                        if (candidate.getName().equals("of") && candidate.getParameterCount() == 1
                                && java.lang.reflect.Modifier.isStatic(candidate.getModifiers())
                                && candidate.getParameterTypes()[0].isAssignableFrom(registriesClass)
                                && attributeInstance.isAssignableFrom(candidate.getReturnType())) return candidate;
                    }
                    throw new IllegalStateException("Unsupported Accessories registry context for " + registriesClass.getName());
                }
            };
        }

        CompoundTag capture(Object holder, Object registries) throws ReflectiveOperationException {
            Object carrier = carrierConstructor.newInstance(new CompoundTag());
            write.invoke(holder, carrier, context(registries));
            return (CompoundTag) carrierTag.invoke(carrier);
        }

        Object holder(Object capability) throws ReflectiveOperationException {
            return getHolder.invoke(capability);
        }

        void apply(Object holder, Object capability, CompoundTag tag, Object registries)
                throws ReflectiveOperationException {
            read.invoke(holder, carrierConstructor.newInstance(tag), context(registries));
            // Rebuild resized containers and invalidate their render lookup caches.
            init.invoke(holder, capability);
        }

        private Object context(Object registries) throws ReflectiveOperationException {
            if (attributesContext == null) return emptyContext.invoke(null);
            Object attributes = Array.newInstance(attributeInstance, 1);
            Array.set(attributes, 0, registryContextFactories.get(registries.getClass()).invoke(null, registries));
            return attributesContext.invoke(null, attributes);
        }
    }

    private record Provider(Method getter, AtomicBoolean warned) {
        static Provider find(String name, String getter) {
            try {
                Class<?> type = Class.forName(name, false, FabricEquipmentCompat.class.getClassLoader());
                return new Provider(type.getMethod(getter, LivingEntity.class), new AtomicBoolean());
            } catch (ClassNotFoundException absent) {
                return null;
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                VSSLogger.warn("Fabric equipment provider is incompatible: " + name, incompatible);
                return null;
            }
        }

        Object get(LivingEntity entity) throws ReflectiveOperationException {
            Object value = getter.invoke(null, entity);
            return value instanceof Optional<?> optional ? optional.orElse(null) : value;
        }

        void report(Throwable failure) {
            if (warned.compareAndSet(false, true)) VSSLogger.warn("Far-player equipment sync failed for "
                    + getter.getDeclaringClass().getName(), failure);
        }
    }
}
