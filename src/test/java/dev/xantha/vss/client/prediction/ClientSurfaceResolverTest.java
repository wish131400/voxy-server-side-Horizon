package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;

/** Chunk-bound upstream rules must keep the requested coordinates, even after workspace reuse. */
class ClientSurfaceResolverTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }

    @Test void actualChunkRulesAreReusedWithinRequestsAndReleasedAtCompletion() throws Exception {
        var bound = new ArrayList<ChunkPos>();
        var resolver = resolver(false, bound);
        var sample = sample();
        for (int x : new int[]{3, 19, -13, 1635, 3}) {
            int z = -29;
            int expected = id((((x >> 4) + (z >> 4)) & 1) == 0);
            assertEquals(expected, resolver.resolve(sample, x, z, (bx,bz) -> 80).topBlockIndex());
            assertEquals(expected, resolver.resolvePreview(sample, x, z, (bx,bz) -> 80).topBlockIndex());
            assertEquals(expected, resolver.resolveInterior(sample, x, z, (bx,bz) -> 80).topBlockIndex());
        }
        assertEquals(8, bound.size(), "exact/interior share four contexts; previews have four independent contexts");
        assertFalse(bound.contains(new ChunkPos(0, 0)));
        for (int x = 0; x < 32; x++) resolver.resolve(sample, x * 16, 0, (bx,bz) -> 80);
        assertTrue(workers(resolver, "workers").size() <= 8);
        try (var outer = resolver.request()) {
            try (var inner = resolver.request()) { resolver.resolve(sample, 3, -29, (bx,bz) -> 80); }
            assertFalse(workers(resolver, "workers").isEmpty(), "closing a nested scope retains its owning task");
        }
        assertTrue(workers(resolver, "workers").isEmpty());
        assertTrue(workers(resolver, "previewWorkers").isEmpty(), "task completion releases rule-held upstream pooled tiles");
    }

    @Test void reusedContextReadsCurrentTerrainRatherThanConstructorTerrain() throws Exception {
        boolean modern = Arrays.stream(NoiseGeneratorSettings.class.getRecordComponents())
                .anyMatch(c -> c.getName().equals("materialRule"));
        ClientTerrainSampler.TerrainFunction changed = modern ? (x,z) -> 80 + x * 10 : (x,z) -> 96;
        var resolver = resolver(true, new ArrayList<>());
        try (var request = resolver.request()) {
            for (boolean preview : new boolean[]{false, true}) {
                assertEquals(id(false), (preview ? resolver.resolvePreview(sample(), 3, 3, (x,z) -> 80)
                        : resolver.resolve(sample(), 3, 3, (x,z) -> 80)).topBlockIndex());
                assertEquals(id(true), (preview ? resolver.resolvePreview(sample(), 7, 7, changed)
                        : resolver.resolve(sample(), 7, 7, changed)).topBlockIndex());
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ClientSurfaceResolver resolver(boolean currentHeight, List<ChunkPos> bound) throws Exception {
        var fixture = TerrainPredictionFixtures.noise();
        var base = fixture.generatorSettings().value();
        var components = NoiseGeneratorSettings.class.getRecordComponents();
        boolean modern = Arrays.stream(components).anyMatch(c -> c.getName().equals("materialRule"));
        Class<?> ruleType = Class.forName(modern ? "net.minecraft.world.level.levelgen.material.rule.MaterialRule"
                : "net.minecraft.world.level.levelgen.SurfaceRules$RuleSource");
        Object source = mock((Class) ruleType, invocation -> {
            String call = invocation.getMethod().getName();
            if (!(call.equals("compile") || call.equals("apply"))) return null;
            Object context = invocation.getArgument(0);
            ChunkPos position;
            ChunkAccess chunk = null;
            if (modern) {
                var field = context.getClass().getDeclaredField("expectedVolume");
                field.setAccessible(true); Object volume = field.get(context);
                position = new ChunkPos(((Number) volume.getClass().getMethod("minBlockX").invoke(volume)).intValue() >> 4,
                        ((Number) volume.getClass().getMethod("minBlockZ").invoke(volume)).intValue() >> 4);
            } else {
                var field = context.getClass().getDeclaredField("chunk"); field.setAccessible(true);
                chunk = (ChunkAccess) field.get(context); position = chunk.getPos();
            }
            bound.add(position);
            ChunkAccess captured = chunk;
            Class<?> evaluator = Class.forName(modern ? "net.minecraft.world.level.levelgen.material.rule.RuleEvaluator"
                    : "net.minecraft.world.level.levelgen.SurfaceRules$SurfaceRule");
            return Proxy.newProxyInstance(evaluator.getClassLoader(), new Class<?>[]{evaluator}, (proxy, method, args) -> {
                boolean grass = currentHeight ? modern
                        ? ((Number) context.getClass().getMethod("surfaceGradientX").invoke(context)).intValue() > 0
                        : captured.getHeight(Heightmap.Types.WORLD_SURFACE_WG, 3, 3) >= 90
                        : (((position.getMinBlockX() >> 4) + (position.getMinBlockZ() >> 4)) & 1) == 0;
                return (grass ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState();
            });
        });
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < arguments.length; i++) arguments[i] =
                components[i].getName().equals("surfaceRule") ? source
                : components[i].getName().equals("materialRule") ? Holder.direct(source) : components[i].getAccessor().invoke(base);
        var signature = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        var settings = NoiseGeneratorSettings.class.getConstructor(signature).newInstance(arguments);
        var create = Arrays.stream(RandomState.class.getMethods()).filter(m -> m.getName().equals("create")
                && m.getParameterCount() == 3 && Arrays.asList(m.getParameterTypes()).contains(NoiseGeneratorSettings.class))
                .findFirst().orElseThrow();
        Object[] randomArgs = Arrays.stream(create.getParameterTypes()).map(type -> type == NoiseGeneratorSettings.class ? settings
                : type == long.class ? 5052304137288917019L : TerrainPredictionFixtures.sourceLookup().lookupOrThrow(Registries.NOISE)).toArray();
        var random = (RandomState) create.invoke(null, randomArgs);
        var generator = new NoiseBasedChunkGenerator(fixture.getBiomeSource(), Holder.direct(settings));
        var access = TerrainPredictionFixtures.access();
        var registry = Arrays.stream(access.getClass().getMethods()).filter(m -> (m.getName().equals("lookupOrThrow")
                || m.getName().equals("registryOrThrow")) && m.getParameterCount() == 1).findFirst().orElseThrow();
        return new ClientSurfaceResolver(generator, random,
                LevelHeightAccessor.create(settings.noiseSettings().minY(), settings.noiseSettings().height()),
                (Registry<Biome>) registry.invoke(access, Registries.BIOME));
    }
    private static int id(boolean grass) { return BuiltInRegistries.BLOCK.getId(grass ? Blocks.GRASS_BLOCK : Blocks.DIRT); }
    private static ClientColumnSample sample() {
        return PredictionColumnVolume.sample(60, 20, y -> BuiltInRegistries.BLOCK.getId(Blocks.STONE), block -> 0).asSample();
    }
    private static Map<?,?> workers(ClientSurfaceResolver resolver, String name) throws Exception {
        var field = ClientSurfaceResolver.class.getDeclaredField(name); field.setAccessible(true);
        return (Map<?,?>) ((ThreadLocal<?>) field.get(resolver)).get();
    }
}
