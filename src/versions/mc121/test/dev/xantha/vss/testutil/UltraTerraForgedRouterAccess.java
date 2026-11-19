package dev.xantha.vss.testutil;

import java.nio.file.Path;
import java.util.zip.ZipFile;
import net.minecraft.core.HolderGetter;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Ordinary JUnit does not apply UTF's access widener to Minecraft. This bridge
 * invokes those same vanilla private members reflectively; the released router
 * graph and every vanilla method body remain unchanged.
 */
public final class UltraTerraForgedRouterAccess {
    private static final String VANILLA = "net/minecraft/world/level/levelgen/NoiseRouterData";
    public static final ResourceKey<DensityFunction> Y = key("Y"),
            SPAGHETTI_ROUGHNESS_FUNCTION = key("SPAGHETTI_ROUGHNESS_FUNCTION"),
            PILLARS = key("PILLARS"), SPAGHETTI_2D_THICKNESS_MODULATOR = key("SPAGHETTI_2D_THICKNESS_MODULATOR");

    public static byte[] bridge(Path jar) throws Exception {
        String path = "com/pandaismyname1/ultraterraforged/data/preset/PresetNoiseRouterData.class";
        try (var zip = new ZipFile(jar.toFile()); var input = zip.getInputStream(zip.getEntry(path))) {
            var node = new ClassNode(); new ClassReader(input).accept(node, 0);
            for (var method : node.methods) for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(VANILLA))
                    call.owner = UltraTerraForgedRouterAccess.class.getName().replace('.', '/');
                if (instruction instanceof FieldInsnNode field && field.owner.equals("net/minecraft/world/level/levelgen/OreVeinifier$VeinType"))
                    method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            UltraTerraForgedRouterAccess.class.getName().replace('.', '/'), field.name, "(Ljava/lang/Object;)I", false));
                if (instruction instanceof FieldInsnNode field && field.owner.equals(VANILLA))
                    field.owner = UltraTerraForgedRouterAccess.class.getName().replace('.', '/');
            }
            var writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
        }
    }
    @SuppressWarnings("unchecked")
    private static ResourceKey<DensityFunction> key(String name) {
        try { var field = NoiseRouterData.class.getDeclaredField(name); field.setAccessible(true);
            return (ResourceKey<DensityFunction>) field.get(null); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static int minY(Object vein) { return veinBound(vein, "minY"); }
    public static int maxY(Object vein) { return veinBound(vein, "maxY"); }
    private static int veinBound(Object vein, String name) {
        try { var field = vein.getClass().getDeclaredField(name); field.setAccessible(true); return field.getInt(vein); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(name, failure); }
    }
    private static DensityFunction call(String name, Class<?>[] signature, Object... arguments) {
        try { var method = NoiseRouterData.class.getDeclaredMethod(name, signature); method.setAccessible(true);
            return (DensityFunction) method.invoke(null, arguments); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(name, failure); }
    }
    public static DensityFunction registerAndWrap(BootstrapContext<?> context, ResourceKey<?> key, DensityFunction value) {
        return call("registerAndWrap", new Class<?>[]{BootstrapContext.class, ResourceKey.class, DensityFunction.class}, context, key, value);
    }
    public static DensityFunction noiseGradientDensity(DensityFunction first, DensityFunction second) {
        return call("noiseGradientDensity", new Class<?>[]{DensityFunction.class, DensityFunction.class}, first, second);
    }
    public static DensityFunction entrances(HolderGetter<?> first, HolderGetter<?> second) {
        return call("entrances", new Class<?>[]{HolderGetter.class, HolderGetter.class}, first, second);
    }
    public static DensityFunction getFunction(HolderGetter<?> lookup, ResourceKey<?> key) {
        return call("getFunction", new Class<?>[]{HolderGetter.class, ResourceKey.class}, lookup, key);
    }
    public static DensityFunction yLimitedInterpolatable(DensityFunction first, DensityFunction second, int min, int max, int extent) {
        return call("yLimitedInterpolatable", new Class<?>[]{DensityFunction.class, DensityFunction.class, int.class, int.class, int.class},
                first, second, min, max, extent);
    }
}
