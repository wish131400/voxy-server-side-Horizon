package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Reads released intermediary bytecode without loading third-party classes into the test runtime. */
class FabricEquipmentActualJarTest {
    private static final String NBT = "Lnet/minecraft/class_2487;";
    private static final String ENTITY = "Lnet/minecraft/class_1309;";
    private static final String REGISTRY = "Lnet/minecraft/class_7225$class_7874;";
    private static final String MAP = "Lio/wispforest/endec/util/MapCarrier;";
    private static final String CONTEXT = "Lio/wispforest/endec/SerializationContext;";

    @Test
    @EnabledIfSystemProperty(named = "vss.trinketsJar", matches = ".+")
    void releasedTrinketsComponentMatchesSnapshotMethods() throws Exception {
        try (var jar = new JarFile(Path.of(System.getProperty("vss.trinketsJar")).toFile())) {
            ClassNode api = read(jar, "dev/emi/trinkets/api/TrinketsApi");
            assertTrue(method(api, "getTrinketComponent", '(' + ENTITY + ")Ljava/util/Optional;"));
            ClassNode component = read(jar, "dev/emi/trinkets/api/LivingEntityTrinketComponent");
            boolean legacy = method(component, "writeToNbt", '(' + NBT + ")V");
            String parameters = '(' + NBT + (legacy ? "" : REGISTRY) + ")V";
            assertTrue(method(component, "writeToNbt", parameters));
            assertTrue(method(component, "readFromNbt", parameters));
            assertTrue(method(component, "getInventory", "()Ljava/util/Map;"));
            ClassNode inventory = read(jar, "dev/emi/trinkets/api/TrinketInventory");
            assertTrue(inventory.interfaces.contains("net/minecraft/class_1263"), "Slots must implement Minecraft Container");
            assertTrue(method(inventory, "getSyncTag", "()" + NBT));
            assertTrue(method(inventory, "applySyncTag", '(' + NBT + ")V"));
            assertTrue(component.methods.stream().filter(m -> m.name.equals("applySyncPacket"))
                    .flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                    .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals("dev/emi/trinkets/api/TrinketInventory")
                            && call.name.equals("applySyncTag")),
                    "The selected metadata path must match the original client synchronization protocol");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.accessoriesJar", matches = ".+")
    void releasedAccessoriesHolderMatchesItsFullClientSyncProtocol() throws Exception {
        try (var jar = new JarFile(Path.of(System.getProperty("vss.accessoriesJar")).toFile())) {
            ClassNode capability = read(jar, "io/wispforest/accessories/api/AccessoriesCapability");
            assertTrue(method(capability, "get", '(' + ENTITY + ")Lio/wispforest/accessories/api/AccessoriesCapability;"));
            assertTrue(method(capability, "getHolder", "()Lio/wispforest/accessories/api/AccessoriesHolder;"));
            ClassNode carrier = read(jar, "io/wispforest/accessories/endec/NbtMapCarrier");
            assertTrue(method(carrier, "<init>", '(' + NBT + ")V"));
            assertTrue(method(carrier, "compoundTag", "()" + NBT));
            ClassNode holder = read(jar, "io/wispforest/accessories/impl/AccessoriesHolderImpl");
            assertTrue(method(holder, "write", '(' + MAP + CONTEXT + ")V"));
            assertTrue(method(holder, "read", '(' + MAP + CONTEXT + ")V"));
            assertTrue(method(holder, "init", "(Lio/wispforest/accessories/api/AccessoriesCapability;)V"));
            ClassNode sync = read(jar, "io/wispforest/accessories/networking/client/SyncEntireContainer");
            assertTrue(calls(sync, "io/wispforest/accessories/impl/AccessoriesHolderImpl", "read"));
            assertTrue(calls(sync, "io/wispforest/accessories/impl/AccessoriesHolderImpl", "init"));
            ClassNode container = read(jar, "io/wispforest/accessories/api/AccessoriesContainer");
            assertTrue(method(container, "renderOptions", "()Ljava/util/List;"));
            assertTrue(container.methods.stream().anyMatch(m -> m.name.equals("getCosmeticAccessories")));
            ClassNode implementation = read(jar, "io/wispforest/accessories/impl/AccessoriesContainerImpl");
            for (String field : new String[]{"RENDER_OPTIONS_KEY", "ITEMS_KEY", "COSMETICS_KEY"}) {
                assertTrue(implementation.fields.stream().anyMatch(f -> f.name.equals(field)), field);
            }
        }
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        var entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name);
        ClassNode node = new ClassNode();
        try (var in = jar.getInputStream(entry)) { new ClassReader(in).accept(node, 0); }
        return node;
    }

    private static boolean method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor));
    }

    private static boolean calls(ClassNode node, String owner, String name) {
        return node.methods.stream().flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name));
    }
}
