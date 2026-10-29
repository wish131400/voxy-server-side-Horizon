package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.FreeTerraForgedVariant;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.NoiseRouter;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.Interval;

/** The same cell-field reads used by RTF's full-chunk CacheChunk, without quart-coordinate rounding. */
final class FreeTerraForgedDensity {
    private static final ThreadLocal<Boolean> COARSE = ThreadLocal.withInitial(() -> false);

    static boolean coarse() { return COARSE.get(); }

    static <T> T coarse(boolean value, java.util.function.Supplier<T> action) {
        return FreeTerraForgedCompat.scoped(COARSE, value, action);
    }

    static NoiseRouter map(Object context, NoiseRouter router) throws ReflectiveOperationException {
        if (context == null) return router;
        FreeTerraForgedNativeFilters.register(context);
        var loader = context.getClass().getClassLoader();
        var variant = FreeTerraForgedVariant.fromClass(context.getClass());
        var cellType = loader.loadClass(variant.worldgenClass("densityfunction.CellSampler"));
        var fieldMethod = cellType.getMethod("field");
        var lookup = context.getClass().getField("lookup").get(context);
        Object heightmap = lookup.getClass().getMethod("getHeightmap").invoke(lookup);
        Object cache = context.getClass().getField("cache").get(context);
        var provideMethod = cache.getClass().getMethod("provideAtChunk", int.class, int.class);
        var handles = MethodHandles.publicLookup();
        MethodHandle provide = handles.unreflect(provideMethod).bindTo(cache)
                .asType(MethodType.methodType(Object.class, int.class, int.class));
        MethodHandle cell = handles.unreflect(provideMethod.getReturnType().getMethod("lookup", int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
        var mapped = new IdentityHashMap<DensityFunction, DensityFunction>();
        DfRewriteRule rule = new DfRewriteRule() {
          @Override public DensityFunction rewrite(DensityFunction originalFunction) {
            DensityFunction function = originalFunction.rewriteChildren(this);
            if (!cellType.isInstance(function)) return function;
            return mapped.computeIfAbsent(function, original -> {
                try {
                    Object field = fieldMethod.invoke(original);
                    MethodHandle read = handles.unreflect(fieldMethod.getReturnType().getMethod("read",
                            loader.loadClass(variant.worldgenClass("cell.Cell")), heightmap.getClass()))
                            .bindTo(field).asType(MethodType.methodType(float.class, Object.class, Object.class));
                    return new ExactCell(original, heightmap, provide, cell, read);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("FreeTerraForged cell-field API unavailable", failure);
                }
            });
          }
        };
        return new NoiseRouter(rule.rewrite(router.temperature()), rule.rewrite(router.vegetation()),
                rule.rewrite(router.continents()), rule.rewrite(router.erosion()), rule.rewrite(router.depth()),
                rule.rewrite(router.ridges()), rule.rewrite(router.chunkSurfaceLevel()), rule.rewrite(router.finalDensity()));
    }

    private record ExactCell(DensityFunction source, Object heightmap, MethodHandle provide,
                             MethodHandle cell, MethodHandle read, ThreadLocal<Column> columns)
            implements DensityFunction {
        ExactCell(DensityFunction source, Object heightmap, MethodHandle provide, MethodHandle cell, MethodHandle read) {
            this(source, heightmap, provide, cell, read, ThreadLocal.withInitial(Column::new));
        }
        private float exact(int x, int z) {
            Column value = columns.get();
            if (!value.valid || value.x != x || value.z != z) {
                try {
                    Object tile = (Object) provide.invokeExact(x >> 4, z >> 4);
                    Object data = (Object) cell.invokeExact(tile, x, z);
                    value.value = (float) read.invokeExact(data, heightmap);
                    value.x = x; value.z = z; value.valid = true;
                } catch (RuntimeException | Error failure) { throw failure; }
                catch (Throwable failure) { throw new IllegalStateException("FreeTerraForged cell sampling failed", failure); }
            }
            return value.value;
        }
        @Override public DensitySampler compileSampler(CompileContext context) {
            DensitySampler fallback = source.compileSampler(context);
            return new DensitySampler() {
                @Override public float sampleValue(SamplerContext context, int x, int y, int z) {
                    return coarse() ? fallback.sampleValue(context,x,y,z) : exact(x,z);
                }
                @Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
                    DensitySampler.sampleVolumeNaive(context,output,volume,this);
                }
            };
        }
        @Override public DensityFunction rewriteChildren(DfRewriteRule rule) {
            return new ExactCell(rule.rewrite(source),heightmap,provide,cell,read,columns);
        }
        @Override public Interval range() { return source.range(); }
        @Override public int domainAxes() { return AXIS_X | AXIS_Z; }
        @Override public MapCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("Runtime prediction cell");
        }
    }
    private static final class Column { int x, z; float value; boolean valid; }
    private FreeTerraForgedDensity() { }
}
