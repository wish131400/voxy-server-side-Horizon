package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.mixin.client.SurfaceRulesContextAccessor;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;

/** Real UTF condition constructors/computations with the production preview Mixins applied. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "vss.ultraTerraForgedJar", matches = ".+")
class UltraTerraForgedSurfacePreviewTest {
    private static final String PREFIX = "com.pandaismyname1.ultraterraforged.";

    @org.junit.jupiter.api.Test void releasedRulesUsePointCellsOnlyForPreview(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap();
        Object fabric = net.fabricmc.loader.api.FabricLoader.getInstance();
        var game = fabric.getClass().getDeclaredField("gameDir");
        var config = fabric.getClass().getDeclaredField("configDir");
        game.setAccessible(true); config.setAccessible(true);
        Object previousGame = game.get(fabric), previousConfig = config.get(fabric);
        game.set(fabric, directory.toAbsolutePath());
        config.set(fabric, java.nio.file.Files.createDirectories(directory.resolve("config")).toAbsolutePath());
        String release = System.getProperty("vss.ultraTerraForgedJar");
        var harness = new dev.xantha.vss.testutil.VoxyMixinHarness(release, """
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin.client",
                 "compatibilityLevel":"JAVA_17","client":["SurfaceRulesContextAccessor","UltraTerraForgedPredictionNearGroundMixin","UltraTerraForgedPredictionCellConditionMixin"],
                 "injectors":{"defaultRequire":1}}
                """);
        var overrides = new java.util.HashMap<String, byte[]>();
        for (String type : new String[]{"NearGroundCondition", "CellCondition"}) {
            String name = PREFIX + "world.worldgen.surface.condition." + type;
            byte[] transformed = harness.apply(name);
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(transformed).accept(node, 0);
            assertTrue(node.methods.stream().anyMatch(m -> m.name.contains(type.equals("NearGroundCondition") ? "vss$previewGround" : "vss$previewCell")),
                    "production rule Mixin must transform the released UTF class");
            overrides.put(name, transformed);
        }
        String contextName = contextType().getName();
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(harness.apply(contextName)).accept(node, 0);
        assertTrue(node.interfaces.contains(SurfaceRulesContextAccessor.class.getName().replace('.', '/')));
        for (String coordinate : new String[]{"blockX", "blockY", "blockZ"})
            assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("vss$" + coordinate)));
        try (var loader = new dev.xantha.vss.testutil.FabricRegistryTestLoader(java.nio.file.Path.of(release).toUri().toURL(), getClass().getClassLoader(), overrides)) {
            Object preset = loader.loadClass(PREFIX + "data.preset.settings.BuiltinPresets").getMethod("makeDefault").invoke(null);
            Class<?> type = loader.loadClass(PREFIX + "world.worldgen.GeneratorContext");
            Object context = type.getMethod("makeCached", preset.getClass(), int.class, int.class, int.class, boolean.class)
                    .invoke(null, preset, 918273, 1, 1, true);
            try { verify(loader, context); }
            finally { FreeTerraForgedCompat.releaseTileCache(type.getField("cache").get(context)); }
        } finally { game.set(fabric, previousGame); config.set(fabric, previousConfig); }
    }

    private static Class<?> contextType() throws ClassNotFoundException {
        try { return Class.forName("net.minecraft.world.level.levelgen.material.MaterialRuleContext"); }
        catch (ClassNotFoundException legacy) { return Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$Context"); }
    }

    static void verify(ClassLoader loader, Object generatorContext) throws Exception {
        String prefix = "com.pandaismyname1.ultraterraforged.world.worldgen.";
        Object cache = generatorContext.getClass().getField("cache").get(generatorContext);
        Object generator = generatorContext.getClass().getField("generator").get(generatorContext);
        var heightmap = generator.getClass().getDeclaredField("heightmap"); heightmap.setAccessible(true);
        Object original = heightmap.get(generator);
        var batches = new AtomicInteger();
        var stateType = loader.loadClass(prefix + "UTFRandomState");
        var state = mock(RandomState.class, withSettings().extraInterfaces(stateType));
        stateType.getMethod("generatorContext").invoke(doReturn(generatorContext).when(state));
        Class<?> contextType = contextType();
        boolean material = contextType.getSimpleName().equals("MaterialRuleContext");
        Class<?>[] interfaces = material ? new Class<?>[]{SurfaceRulesContextAccessor.class,
                loader.loadClass("com.pandaismyname1.ultraterraforged.mixin.MaterialRuleContextAccessor")}
                : new Class<?>[]{SurfaceRulesContextAccessor.class};
        Object context = mock(contextType, withSettings().extraInterfaces(interfaces).defaultAnswer(i -> {
            String name = i.getMethod().getName();
            if (name.equals("ultraterraforged$randomState")) return state;
            if (name.startsWith("vss$") || name.matches("block[XYZ]")) {
                var f = contextType.getDeclaredField(name.startsWith("vss$") ? name.substring(4) : name);
                f.setAccessible(true); return f.get(i.getMock());
            }
            return RETURNS_DEFAULTS.answer(i);
        }));
        set(context,"randomState",state);
        if (!material) {
            var chunk = mock(ChunkAccess.class);
            when(chunk.getPos()).thenReturn(new ChunkPos(6301,-7412)); set(context,"chunk",chunk);
        }
        int x = 100821, z = -118581; set(context,"blockX",x);set(context,"blockZ",z);
        var rules = new FreeTerraForgedSurfaceRules(generatorContext);
        var near = loader.loadClass(prefix + "surface.condition.NearGroundCondition");
        var nearCtor = near.getDeclaredConstructor(contextType,int.class); nearCtor.setAccessible(true);
        var nearCompute = near.getDeclaredMethod("compute"); nearCompute.setAccessible(true);
        var terrain = loader.loadClass(prefix + "surface.condition.TerrainCondition");
        var terrainCtor = terrain.getDeclaredConstructor(contextType,Set.class); terrainCtor.setAccessible(true);
        var base = terrain.getSuperclass(); var compute = base.getDeclaredMethod("compute"); compute.setAccessible(true);
        Class<?> cellType = loader.loadClass(prefix + "cell.Cell");
        var cellTest = terrain.getDeclaredMethod("test",cellType,int.class,int.class); cellTest.setAccessible(true);
        Object cell = cellType.getConstructor().newInstance();
        Object lookup = generatorContext.getClass().getField("lookup").get(generatorContext);
        lookup.getClass().getMethod("apply",cellType,int.class,int.class).invoke(lookup,cell,x,z);
        Object levels = generatorContext.getClass().getField("levels").get(generatorContext);
        int ground = (int) levels.getClass().getMethod("scale",float.class).invoke(levels,cellType.getField("height").getFloat(cell));
        Set<Object> kinds = Set.of(cellType.getField("terrain").get(cell));
        try {
            heightmap.set(generator, new ThreadLocal<>() {
                @Override public Object get() { batches.incrementAndGet(); throw new IllegalStateException("unexpected full UTF tile"); }
            });
            assertFalse(FreeTerraForgedSurfaceRules.pointRules(cache));
            long start = System.nanoTime();
            Object[] saved = new Object[2];
            rules.preview(() -> {
                try {
                    assertTrue(FreeTerraForgedSurfaceRules.pointRules(cache));
                    assertFalse(FreeTerraForgedSurfaceRules.pointRules(new Object()));
                    saved[0]=nearCtor.newInstance(context,5); saved[1]=terrainCtor.newInstance(context,kinds);
                    for (int y : new int[]{ground-6,ground-5,ground,ground+1,ground+2}) {
                        set(context,"blockY",y);
                        assertEquals(y>=ground-5 && y<=ground+1,nearCompute.invoke(saved[0]));
                        assertEquals(cellTest.invoke(saved[1],cell,x,z),compute.invoke(saved[1]));
                    }
                    return null;
                } catch (Exception e) { throw new IllegalStateException(e); }
            });
            // Cached preview rules are reused during a later column request.
            rules.preview(() -> { try { set(context,"blockY",ground); assertEquals(true,nearCompute.invoke(saved[0])); return null; }
                catch(Exception e) { throw new IllegalStateException(e); } });
            rules.preview(() -> {
                try {
                    for (int[] p : List.of(new int[]{100823,-118583},new int[]{-13,-29},new int[]{x,z})) {
                        set(context,"blockX",p[0]); set(context,"blockZ",p[1]);
                        cellType.getMethod("reset").invoke(cell);
                        lookup.getClass().getMethod("apply",cellType,int.class,int.class).invoke(lookup,cell,p[0],p[1]);
                        int h=(int)levels.getClass().getMethod("scale",float.class).invoke(levels,cellType.getField("height").getFloat(cell));
                        set(context,"blockY",h);
                        assertEquals(true,nearCompute.invoke(saved[0]),"reused preview rule follows the current column");
                        assertEquals(cellTest.invoke(saved[1],cell,p[0],p[1]),compute.invoke(saved[1]));
                    }
                    return null;
                } catch(Exception e) { throw new IllegalStateException(e); }
            });
            rules.preview(() -> {
                try {
                    for (int[] p : List.of(new int[]{100823,-118583},new int[]{-13,-29},new int[]{x,z})) {
                        set(context,"blockX",p[0]); set(context,"blockZ",p[1]);
                        cellType.getMethod("reset").invoke(cell);
                        lookup.getClass().getMethod("apply",cellType,int.class,int.class).invoke(lookup,cell,p[0],p[1]);
                        int h=(int)levels.getClass().getMethod("scale",float.class).invoke(levels,cellType.getField("height").getFloat(cell));
                        set(context,"blockY",h);
                        assertEquals(true,nearCompute.invoke(saved[0]),"reused preview rule follows the current column");
                        assertEquals(cellTest.invoke(saved[1],cell,p[0],p[1]),compute.invoke(saved[1]));
                    }
                    return null;
                } catch(Exception e) { throw new IllegalStateException(e); }
            });
            assertThrows(IllegalArgumentException.class, () -> rules.preview(() -> {throw new IllegalArgumentException("cancel");}));
            assertFalse(FreeTerraForgedSurfaceRules.pointRules(cache));
            assertEquals(0,batches.get(),"preview material rules must never generate a full tile");
            // Exact/server rule construction must keep requesting the original filtered chunk.
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> {
                Object exact = nearCtor.newInstance(context,5);
                if (material) nearCompute.invoke(exact);
            });
            assertTrue(batches.get()>0,"outside preview the original tile path remains active");
            System.out.println("Released UTF surface previews: original conditions agree with point cells, zero full-tile batches; scope/cache isolation verified; ms="+(System.nanoTime()-start)/1e6);
        } finally { heightmap.set(generator,original); }
    }
    private static void set(Object context,String name,Object value) throws Exception {
        var f=contextType().getDeclaredField(name); f.setAccessible(true); f.set(context,value);
    }
}
