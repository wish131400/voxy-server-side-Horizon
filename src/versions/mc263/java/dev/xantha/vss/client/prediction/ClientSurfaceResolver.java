package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

/** Evaluates the synced Minecraft surface rules against predicted column geometry. */
final class ClientSurfaceResolver {
    private final NoiseBasedChunkGenerator generator;
    private final RandomState randomState;
    private final LevelHeightAccessor heights;
    private final Registry<Biome> biomes;
    private final PredictionBiomeCache biomeCache;
    private final PredictionBiomeCache previewBiomeCache;
    private final ThreadLocal<Worker> workers = new ThreadLocal<>();
    private final ThreadLocal<Worker> previewWorkers = new ThreadLocal<>();

    ClientSurfaceResolver(NoiseBasedChunkGenerator generator, RandomState randomState,
                          LevelHeightAccessor heights, Registry<Biome> biomes) {
        this(generator, randomState, heights, biomes,
                new PredictionBiomeCache(generator.getBiomeSource(), new PredictionClimateSampler(randomState, generator.generatorSettings().value().noiseRouter()).sampler()));
    }

    ClientSurfaceResolver(NoiseBasedChunkGenerator generator, RandomState randomState,
                          LevelHeightAccessor heights, Registry<Biome> biomes, PredictionBiomeCache biomeCache) {
        this.generator = generator;
        this.randomState = randomState;
        this.heights = heights;
        this.biomes = biomes;
        this.biomeCache = biomeCache;
        this.previewBiomeCache = new PredictionBiomeCache(generator.getBiomeSource(), new PredictionClimateSampler(randomState, generator.generatorSettings().value().noiseRouter()).sampler());
    }

    ClientColumnSample resolve(ClientColumnSample sample, int x, int z,
                                ClientTerrainSampler.TerrainFunction terrain) {
        return resolve(sample, x, z, terrain, false);
    }

    ClientColumnSample resolvePreview(ClientColumnSample sample, int x, int z,
                                      ClientTerrainSampler.TerrainFunction terrain) {
        return resolve(sample, x, z, terrain, true);
    }

    /** Evaluate every solid run, including cave floors and ceilings, against the synced rules. */
    ClientColumnSample resolveInterior(ClientColumnSample sample, int x, int z,
                                       ClientTerrainSampler.TerrainFunction terrain) {
        var volume = sample.volume();
        if (volume == null || volume.size() == 0) return sample;
        try {
            Worker worker = workers.get();
            if (worker == null || worker.columns >= 4096 || (worker.x >> 4) != (x >> 4) || (worker.z >> 4) != (z >> 4)) {
                worker = new Worker(false,x,z);
                workers.set(worker);
            }
            worker.columns++; worker.x = x; worker.z = z; worker.terrain = terrain;
            worker.updateXZ(x,z);
            int min = volume.minY(), height = volume.maxY() - min;
            int[] blocks = new int[height];
            java.util.Arrays.fill(blocks, -1); // Volume sampling uses negative IDs for implicit air.
            int water = Integer.MIN_VALUE, above = 0;
            for (int i = volume.size() - 1; i >= 0; i--) {
                if (i + 1 < volume.size() && volume.top(i) < volume.bottom(i + 1)) {
                    water = Integer.MIN_VALUE; above = 0;
                }
                int bottom = volume.bottom(i), top = volume.top(i);
                if (volume.fluid(i) != 0) {
                    if (water == Integer.MIN_VALUE) water = top;
                    java.util.Arrays.fill(blocks, bottom - min, top - min, volume.block(i));
                    continue;
                }
                int solidBottom = bottom;
                for (int j = i - 1; j >= 0 && volume.top(j) == solidBottom && volume.fluid(j) == 0; j--)
                    solidBottom = volume.bottom(j);
                for (int y = top - 1; y >= bottom; y--) {
                    if ((y & 15) == 0 && Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                    int block = volume.block(i);
                    above++;
                    // The new material pass applies to every non-fluid solid,
                    // including structure and earlier material states.
                    Access.UPDATE_Y.invokeExact(worker.context, above, y - solidBottom + 1, water, y);
                    BlockState state = (BlockState) Access.APPLY.invokeExact(worker.rule, x, y, z);
                    if (state != null) block = state.isAir() ? -1
                            : BuiltInRegistries.BLOCK.getId(state.getBlock());
                    blocks[y - min] = block;
                }
            }
            return PredictionColumnVolume.sample(min, height, y -> blocks[y - min], block -> {
                var state = BuiltInRegistries.BLOCK.byId(block).defaultBlockState();
                return state.getFluidState().isEmpty() ? 0 : state.is(net.minecraft.world.level.block.Blocks.LAVA) ? 2 : 1;
            }).asSample();
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Minecraft interior surface rules failed", failure); }
    }

    private ClientColumnSample resolve(ClientColumnSample sample, int x, int z,
                                ClientTerrainSampler.TerrainFunction terrain, boolean preview) {
        try {
            var local = preview ? previewWorkers : workers;
            Worker worker = local.get();
            // NoiseChunk caches preliminary heights. Bound its lifetime even when
            // a worker travels indefinitely through coarse tiles.
            if (worker == null || worker.columns >= 4096 || (worker.x >> 4) != (x >> 4) || (worker.z >> 4) != (z >> 4)) {
                worker = new Worker(preview,x,z);
                local.set(worker);
            }
            worker.columns++;
            worker.x = x;
            worker.z = z;
            worker.terrain = terrain;
            worker.updateXZ(x,z);
            int top = worker.material(sample, 0);
            int under = worker.material(sample, 1);
            int deep = worker.material(sample, 6);
            Biome biome = worker.biomeAt(new BlockPos(x, sample.surfaceY(), z)).value();
            int flags = sample.flags() & ~(ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_ICE);
            int weatherY = sample.hasFluid() ? sample.fluidY() - 1 : sample.surfaceY();
            if (weatherY >= heights.getMinY() && weatherY < (heights.getMaxY() + 1)
                    && biome.coldEnoughToSnow(new BlockPos(x, weatherY, z), generator.getSeaLevel())) {
                if (sample.hasFluid()) {
                    if (sample.fluid() == 1) flags |= ClientColumnSample.FLAG_ICE;
                } else if (sample.hasSurface() && PredictionMaterialPalette.supportsSnow(top)) {
                    // SurfaceRules produce the ground block. SnowAndFreezeFeature
                    // adds the covering layer later; expose it before decoration.
                    flags |= ClientColumnSample.FLAG_SNOW;
                }
            }
            return new ClientColumnSample(sample.surfaceY(), sample.fluidY(), sample.biomeIndex(),
                    top, sample.structureIndex(), sample.treeKind(), sample.treeDensity(), sample.treeHeight(),
                    sample.fluid(), flags, sample.groundFeatureKind(), under, deep,
                    sample.surfaceBottom(), sample.lowerTop(), sample.lowerBottom(), sample.spanFloor());
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Minecraft surface rule evaluation failed", failure);
        }
    }

    private final class Worker {
        private final Object context;
        private final Object rule;
        private ClientTerrainSampler.TerrainFunction terrain;
        private int x;
        private int z;
        private int columns;
        private final boolean preview;

        @SuppressWarnings("unchecked")
        private Worker(boolean preview, int x, int z) throws ReflectiveOperationException {
            this.preview = preview; this.x=x; this.z=z;
            NoiseGeneratorSettings settings = generator.generatorSettings().value();
            DensityVolume volume = new DensityVolume(16,heights.getHeight(),16,x & ~15,heights.getMinY(),z & ~15);
            var samplerContext = SamplerContext.builder().enableCaches().build();
            context = Access.CONSTRUCTOR.newInstance(randomState.surfaceSystem(), randomState, volume,
                    randomState.samplersWithContext(samplerContext), (Function<BlockPos,Holder<Biome>>) this::biomeAt,
                    new WorldGenerationContext(generator,heights), generator.getBiomeSource().possibleBiomes());
            rule = settings.materialRule().value().compile((MaterialRuleContext)context);
        }

        private void updateXZ(int bx,int bz) throws Throwable {
            int startX = bx & ~15, startZ = bz & ~15;
            int gx=terrain.surfaceY(Math.min(bx+1,startX+15),bz)-terrain.surfaceY(Math.max(bx-1,startX),bz);
            int gz=terrain.surfaceY(bx,Math.min(bz+1,startZ+15))-terrain.surfaceY(bx,Math.max(bz-1,startZ));
            Access.UPDATE_XZ.invokeExact(context,bx,bz,gx,gz);
        }

        private Holder<Biome> biomeAt(BlockPos pos) {
            return (preview ? previewBiomeCache : biomeCache).get(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
        }

        private int material(ClientColumnSample sample, int depth) throws Throwable {
            int y = Math.max(heights.getMinY(), sample.surfaceY() - 1 - depth);
            int floor = sample.floating() ? sample.surfaceBottom() : heights.getMinY();
            int water = sample.hasFluid() ? sample.fluidY() : Integer.MIN_VALUE;
            Access.UPDATE_Y.invokeExact(context, depth + 1, Math.max(1, y - floor + 1), water, y);
            BlockState state = (BlockState) Access.APPLY.invokeExact(rule, x, y, z);
            if (state == null) state = generator.generatorSettings().value().defaultBlock();
            return BuiltInRegistries.BLOCK.getId(state.getBlock());
        }
    }

    // The rule context is protected vanilla API. Resolve its signatures once;
    // workers reuse compiled rules and method handles, with no per-column lookup.
    private static final class Access {
        private static final Constructor<?> CONSTRUCTOR;
        private static final MethodHandle UPDATE_XZ;
        private static final MethodHandle UPDATE_Y;
        private static final MethodHandle APPLY;

        static {
            try {
                Class<?> contextType = MaterialRuleContext.class;
                Class<?> ruleType = RuleEvaluator.class;
                CONSTRUCTOR = contextType.getDeclaredConstructor(MaterialSystem.class, RandomState.class,
                        DensityVolume.class, DensitySamplerSet.class, Function.class, WorldGenerationContext.class, java.util.Set.class);
                CONSTRUCTOR.setAccessible(true);
                UPDATE_XZ = named(contextType, "updateXZ");
                UPDATE_Y = named(contextType, "updateY");
                APPLY = method(ruleType, BlockState.class, 3);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        // Both updates now have four integer parameters. Matching only their
        // shape aliases XZ and Y and silently corrupts material context state.
        private static MethodHandle named(Class<?> owner, String name) throws ReflectiveOperationException {
            var method = owner.getDeclaredMethod(name, int.class, int.class, int.class, int.class);
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method).asType(
                    MethodType.methodType(void.class, Object.class, int.class, int.class, int.class, int.class));
        }

        private static MethodHandle method(Class<?> owner, Class<?> result, int integers)
                throws ReflectiveOperationException {
            Class<?>[] parameters = new Class<?>[integers];
            java.util.Arrays.fill(parameters, int.class);
            for (var method : owner.getDeclaredMethods()) {
                if (method.getReturnType() == result && java.util.Arrays.equals(method.getParameterTypes(), parameters)) {
                    method.setAccessible(true);
                    return MethodHandles.lookup().unreflect(method)
                            .asType(MethodType.methodType(result, Object.class, parameters));
                }
            }
            throw new NoSuchMethodException(owner.getName() + " with " + integers + " integer arguments");
        }
    }
}
