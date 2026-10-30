package dev.xantha.vss.testutil;

import com.mojang.serialization.Lifecycle;
import java.net.URL;
import java.net.URLClassLoader;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.InsnNode;

/**
 * Runs a released Fabric mod's real codec registration in an isolated registry.
 * FabricRegistryBuilder normally installs registries through a game Mixin
 * accessor; that accessor is absent in ordinary JUnit. Only registry allocation
 * is replaced. The mod's bootstrap, entries, codecs and generation stay intact.
 */
public final class FabricRegistryTestLoader extends URLClassLoader {
    private static final String DESCRIPTOR = "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Registry;";

    public FabricRegistryTestLoader(URL release, ClassLoader parent) {
        super(new URL[]{release}, parent);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!name.endsWith(".platform.fabric.RegistryUtilImpl")) return super.findClass(name);
        var resource = findResource(name.replace('.', '/') + ".class");
        if (resource == null) throw new ClassNotFoundException(name);
        try (var stream = resource.openStream()) {
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            var methods = node.methods.stream().filter(method -> method.name.equals("createRegistry")
                    && method.desc.equals(DESCRIPTOR) && (method.access & Opcodes.ACC_STATIC) != 0).toList();
            if (methods.size() != 1) throw new ClassNotFoundException("Unsupported Fabric registry allocation: " + name);
            var method = methods.get(0);
            boolean fabricBuilder = java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                    .anyMatch(instruction -> instruction instanceof MethodInsnNode call
                            && call.owner.equals("net/fabricmc/fabric/api/event/registry/FabricRegistryBuilder")
                            && call.name.equals("buildAndRegister"));
            if (!fabricBuilder) throw new ClassNotFoundException("Expected real Fabric registry builder in " + name);
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            if (method.localVariables != null) method.localVariables.clear();
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    FabricRegistryTestLoader.class.getName().replace('.', '/'), "isolatedRegistry", DESCRIPTOR, false));
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
            var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] bytes = writer.toByteArray();
            return defineClass(name, bytes, 0, bytes.length);
        } catch (java.io.IOException failure) {
            throw new ClassNotFoundException(name, failure);
        }
    }

    public static <T> Registry<T> isolatedRegistry(ResourceKey<Registry<T>> key) {
        return new MappedRegistry<>(key, Lifecycle.stable());
    }
}
