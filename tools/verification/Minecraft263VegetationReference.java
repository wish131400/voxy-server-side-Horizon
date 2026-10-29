package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.stateproviders.*;
import net.minecraft.world.level.levelgen.placement.*;
import net.minecraft.util.valueproviders.*;

/** Reproducible Java 25 / actual MC 26.3 feature, provider and RNG reference. */
public final class Minecraft263VegetationReference {
    private static final Gson JSON = new Gson();
    private static final Map<BlockState, Integer> STATES = new LinkedHashMap<>();
    private static int state(BlockState state) { return STATES.computeIfAbsent(state, ignored -> STATES.size()); }
    private static JsonArray blocks(Map<BlockPos, BlockState> values) {
        var result = new JsonArray();
        values.entrySet().stream().sorted(Comparator.<Map.Entry<BlockPos, BlockState>>comparingInt(e -> e.getKey().getX())
                .thenComparingInt(e -> e.getKey().getZ()).thenComparingInt(e -> e.getKey().getY())).forEach(entry -> {
                    var row = new JsonArray(); row.add(entry.getKey().getX()); row.add(entry.getKey().getY());
                    row.add(entry.getKey().getZ()); row.add(state(entry.getValue())); result.add(row);
                });
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the output fixture directory");
        Path root = Path.of(args[0]); Files.createDirectories(root);
        PredictionNetherDecorationTest.bootstrap();
        try {
            var doc = PredictionNetherDecorationTest.nativeDocument("minecraft:crimson_forest");
            var modern = modernFeatures();
            var ops = PredictionNetherDecorationTest.lookup.createSerializationContext(JsonOps.INSTANCE);
            modern.forEach((name, feature) -> doc.getAsJsonObject("configured_features").add(name,
                    Feature.DIRECT_CODEC.encodeStart(ops, feature.feature()).getOrThrow()));
            Files.writeString(root.resolve("features.json"), JSON.toJson(doc));
            System.out.println("Exported real feature registry entries=" + doc.getAsJsonObject("configured_features").size());
            providers(root);
            vegetation(root);
            modern(root, modern);
            var states = new JsonArray();
            STATES.keySet().forEach(value -> states.add(RustWorldgenDocument.encodeState(value)));
            Files.writeString(root.resolve("vegetation-states.json"), JSON.toJson(states));
            trees(root);
        } finally { PredictionNetherDecorationTest.restore(); }
    }

    /** Small real-record regression for the modern tree/provider and mushroom field changes. */
    private static void trees(Path root) throws Exception {
        STATES.clear();
        var lookup = PredictionNetherDecorationTest.lookup;
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var biome = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        var generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(biome), settings);
        int rows = 0;
        try (var output = Files.newBufferedWriter(root.resolve("trees263.jsonl"))) {
            for (String name : List.of("oak", "birch", "fancy_oak", "spruce", "pine", "mega_pine", "cherry", "mangrove",
                    "huge_brown_mushroom", "huge_red_mushroom")) {
                var feature = lookup.lookupOrThrow(Registries.FEATURE).getOrThrow(ResourceKey.create(Registries.FEATURE,
                        Identifier.withDefaultNamespace(name))).value();
                for (long seed : new long[]{0, 7, 42, 731}) for (int pattern = 0; pattern < 2; pattern++) {
                    var level = new FeatureStampLevel(seed, RegistryAccess.EMPTY, Blocks.GRASS_BLOCK.defaultBlockState()) {
                        @Override public BlockState getBlockState(BlockPos pos) {
                            var edit = placed().get(pos); if (edit != null) return edit;
                            if (pos.getY() < 0 || pos.getY() >= 128) return Blocks.VOID_AIR.defaultBlockState();
                            return pos.getY() == 55 ? Blocks.GRASS_BLOCK.defaultBlockState()
                                    : pos.getY() < 55 ? Blocks.NETHERRACK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        }
                        @Override public Holder<Biome> getBiome(BlockPos pos) { return biome; }
                        @Override public Holder<Biome> getNoiseBiome(int x, int by, int z) { return biome; }
                        @Override public Holder<Biome> getUncachedNoiseBiome(int x, int by, int z) { return biome; }
                    };
                    if (pattern == 1) level.placed().put(new BlockPos(0, 58, 0), Blocks.STONE.defaultBlockState());
                    var input = blocks(level.placed());
                    var random = new WorldgenRandom(new XoroshiroRandomSource(seed));
                    boolean placed = feature.place(level, generator, random, new BlockPos(0, 56, 0));
                    var row = new JsonObject(); row.addProperty("feature", "minecraft:" + name); row.addProperty("seed", Long.toString(seed));
                    row.addProperty("pattern", pattern); row.addProperty("ground", "minecraft:grass_block");
                    row.addProperty("surface_y", 55); row.addProperty("ceiling_y", 128);
                    row.add("origin", JSON.toJsonTree(new int[]{0, 56, 0}));
                    row.add("volume_min", JSON.toJsonTree(new int[]{-32, 0, -32})); row.add("volume_size", JSON.toJsonTree(new int[]{80, 128, 80}));
                    row.add("input", input); row.add("output", blocks(level.placed())); row.addProperty("placed", placed);
                    row.addProperty("after", Long.toString(random.nextLong()));
                    output.write(JSON.toJson(row)); output.newLine(); rows++;
                }
            }
        }
        var states = new JsonArray();
        STATES.keySet().forEach(value -> states.add(RustWorldgenDocument.encodeState(value)));
        Files.writeString(root.resolve("trees-states.json"), JSON.toJson(states));
        System.out.println("Actual modern tree/mushroom reference rows=" + rows);
    }

    private record ModernCase(Feature feature, BlockPos origin, Direction obstructionDirection) {}

    private static Holder<PlacedFeature> placed(Feature feature) {
        return Holder.direct(new PlacedFeature(Holder.direct(feature), List.of()));
    }

    /** Actual game records, including wrappers absent from the vanilla feature registry. */
    private static Map<String, ModernCase> modernFeatures() {
        var ops = PredictionNetherDecorationTest.lookup.createSerializationContext(JsonOps.INSTANCE);
        var randomLogs = BlockStateProvider.DIRECT_CODEC.parse(ops, JsonParser.parseString("""
            {"type":"minecraft:weighted","entries":[{"data":{"id":"minecraft:oak_log"},"weight":3},
              {"data":{"id":"minecraft:birch_log"},"weight":2}]}
            """)).getOrThrow();
        var noState = BlockStateProvider.DIRECT_CODEC.parse(ops, JsonParser.parseString("""
            {"type":"minecraft:rule_based","rules":[]}
            """)).getOrThrow();
        var cases = new LinkedHashMap<String, ModernCase>();
        for (int height : new int[]{0, 2, 8}) cases.put("vss:test_projected_air_" + height,
                new ModernCase(new ProjectedRandomPatchySquare(Holder.direct(randomLogs),
                        BlockPredicate.matchesBlocks(Blocks.AIR), UniformInt.of(1, 3), height), new BlockPos(0, 61, 0), null));
        cases.put("vss:test_projected_solid", new ModernCase(new ProjectedRandomPatchySquare(Holder.direct(randomLogs),
                BlockPredicate.matchesBlocks(Blocks.NETHERRACK), ConstantInt.of(2), 5), new BlockPos(0, 20, 0), null));
        cases.put("vss:test_projected_nullable", new ModernCase(new ProjectedRandomPatchySquare(Holder.direct(noState),
                BlockPredicate.matchesBlocks(Blocks.AIR), ConstantInt.of(3), 8), new BlockPos(0, 61, 0), null));

        var cap = placed(new SimpleBlockFeature(BlockStateProvider.of(Blocks.RED_MUSHROOM_BLOCK)));
        for (Direction direction : List.of(Direction.UP, Direction.DOWN)) {
            var origin = new BlockPos(0, direction == Direction.UP ? 56 : 79, 0);
            for (float chance : new float[]{0, .65f, 1}) {
                String suffix = chance == 0 ? "zero" : chance == 1 ? "full" : "random";
                cases.put("vss:test_pillar_" + direction.getName() + "_" + suffix,
                        new ModernCase(new SingleBlockPillarFeature(Holder.direct(randomLogs),
                                BlockPredicate.matchesBlocks(Blocks.AIR), direction, chance, Optional.of(cap)), origin, direction));
            }
        }
        cases.put("vss:test_pillar_nullable", new ModernCase(new SingleBlockPillarFeature(Holder.direct(noState),
                BlockPredicate.matchesBlocks(Blocks.AIR), Direction.UP, .8f, Optional.of(cap)), new BlockPos(0, 56, 0), Direction.UP));

        var first = placed(new SimpleBlockFeature(randomLogs));
        var fail = placed(new SimpleBlockFeature(noState));
        var last = Holder.direct(new PlacedFeature(Holder.direct(new SimpleBlockFeature(BlockStateProvider.of(Blocks.GOLD_BLOCK))),
                List.of(OffsetPlacement.of(1, 0, 0))));
        cases.put("vss:test_sequence_fail_first", new ModernCase(new SequenceFeature(HolderSet.direct(fail, first, last)), new BlockPos(0, 56, 0), null));
        cases.put("vss:test_sequence_fail_second", new ModernCase(new SequenceFeature(HolderSet.direct(first, fail, last)), new BlockPos(0, 56, 0), null));
        cases.put("vss:test_sequence_success", new ModernCase(new SequenceFeature(HolderSet.direct(first, last)), new BlockPos(0, 56, 0), null));
        cases.put("vss:test_overlay_mixed", new ModernCase(new OverlayFeature(HolderSet.direct(first, fail, last)), new BlockPos(0, 56, 0), null));
        cases.put("vss:test_overlay_all_false", new ModernCase(new OverlayFeature(HolderSet.direct(fail, fail)), new BlockPos(0, 56, 0), null));

        for (Direction direction : List.of(Direction.UP, Direction.DOWN, Direction.EAST)) for (boolean prioritizeTip : new boolean[]{false, true}) {
            var origin = new BlockPos(0, direction == Direction.DOWN ? 79 : 56, 0);
            cases.put("vss:test_column_" + direction.getName() + (prioritizeTip ? "_tip" : "_base"), new ModernCase(
                    new BlockColumnFeature(List.of(BlockColumnFeature.layer(UniformInt.of(2, 4), randomLogs),
                                    BlockColumnFeature.layer(ConstantInt.of(2), BlockStateProvider.of(Blocks.GOLD_BLOCK))),
                            direction, BlockPredicate.matchesBlocks(Blocks.AIR), prioritizeTip), origin, direction));
        }
        cases.put("vss:test_column_zero", new ModernCase(new BlockColumnFeature(List.of(
                BlockColumnFeature.layer(ConstantInt.of(0), randomLogs), BlockColumnFeature.layer(ConstantInt.of(0), randomLogs)),
                Direction.UP, BlockPredicate.matchesBlocks(Blocks.AIR), false), new BlockPos(0, 56, 0), Direction.UP));
        return cases;
    }

    private static void modern(Path root, Map<String, ModernCase> cases) throws Exception {
        var lookup = PredictionNetherDecorationTest.lookup;
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
        var biome = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.CRIMSON_FOREST);
        var generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(biome), settings);
        int rows = 0;
        try (var output = Files.newBufferedWriter(root.resolve("modern.jsonl"))) {
            for (var entry : cases.entrySet()) for (long seed : new long[]{0, 1, 7, 19, 42, 128, 731, -1}) {
                var value = entry.getValue();
                int patterns = value.obstructionDirection() == null ? 2 : 3;
                for (int pattern = 0; pattern < patterns; pattern++) {
                    var level = new FeatureStampLevel(seed, RegistryAccess.EMPTY, Blocks.CRIMSON_NYLIUM.defaultBlockState()) {
                        @Override public BlockState getBlockState(BlockPos pos) {
                            var edit = placed().get(pos); if (edit != null) return edit;
                            if (pos.getY() < 0 || pos.getY() >= 128) return Blocks.VOID_AIR.defaultBlockState();
                            return pos.getY() == 55 ? Blocks.CRIMSON_NYLIUM.defaultBlockState()
                                    : pos.getY() < 55 || pos.getY() >= 80 ? Blocks.NETHERRACK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        }
                        @Override public Holder<Biome> getBiome(BlockPos pos) { return biome; }
                        @Override public Holder<Biome> getNoiseBiome(int x, int by, int z) { return biome; }
                        @Override public Holder<Biome> getUncachedNoiseBiome(int x, int by, int z) { return biome; }
                    };
                    if (pattern > 0 && value.obstructionDirection() != null) {
                        level.placed().put(value.origin().relative(value.obstructionDirection(), pattern == 1 ? 1 : 3), Blocks.STONE.defaultBlockState());
                    } else if (pattern == 1) {
                        int y = value.origin().getY() < 55 ? 18 : 58;
                        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                            level.placed().put(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
                    }
                    var input = blocks(level.placed());
                    var random = new WorldgenRandom(new XoroshiroRandomSource(seed));
                    boolean placed = value.feature().place(level, generator, random, value.origin());
                    var row = new JsonObject(); row.addProperty("feature", entry.getKey()); row.addProperty("seed", Long.toString(seed));
                    row.addProperty("pattern", pattern); row.addProperty("ground", "minecraft:crimson_nylium");
                    row.add("origin", JSON.toJsonTree(new int[]{value.origin().getX(), value.origin().getY(), value.origin().getZ()}));
                    row.add("volume_min", JSON.toJsonTree(new int[]{-32, 0, -32})); row.add("volume_size", JSON.toJsonTree(new int[]{80, 128, 80}));
                    row.add("input", input); row.add("output", blocks(level.placed())); row.addProperty("placed", placed);
                    row.addProperty("after", Long.toString(random.nextLong()));
                    output.write(JSON.toJson(row)); output.newLine(); rows++;
                }
            }
        }
        System.out.println("Actual custom modern feature reference rows=" + rows);
    }

    private static void providers(Path root) throws Exception {
        var lookup = PredictionNetherDecorationTest.lookup;
        var ops = lookup.createSerializationContext(JsonOps.INSTANCE);
        Map<String, BlockStateProvider> providers = new LinkedHashMap<>();
        providers.put("vanilla_soil", lookup.lookupOrThrow(Registries.BLOCK_STATE_PROVIDER).getOrThrow(ResourceKey.create(
                Registries.BLOCK_STATE_PROVIDER, Identifier.withDefaultNamespace("soil_beneath_tree"))).value());
        Map<String, String> definitions = Map.of(
                "nested_rule_fallback", """
                  {"type":"minecraft:rule_based","rules":[{"if_true":{"type":"minecraft:matching_block_tag","tag":"minecraft:dirt"},
                    "then":{"type":"minecraft:rule_based","rules":[],"fallback":{"id":"minecraft:rooted_dirt"}}}],
                   "fallback":{"type":"minecraft:rule_based","rules":[]}}
                  """,
                "rotated_randomized_weighted", """
                  {"type":"minecraft:rotated","state":{"type":"minecraft:randomized_int","property":"age",
                    "source":{"type":"minecraft:weighted","entries":[{"data":{"id":"minecraft:cave_vines"},"weight":3},
                      {"data":{"id":"minecraft:cave_vines","properties":{"berries":"true"}},"weight":1}]},
                    "values":{"type":"minecraft:uniform","min_inclusive":2,"max_inclusive":7}}}
                  """,
                "copy_properties", """
                  {"type":"minecraft:copy_properties","source":{"id":"minecraft:oak_log"}}
                  """,
                "rotated_logs", """
                  {"type":"minecraft:rotated","state":{"type":"minecraft:weighted","entries":[
                    {"data":{"id":"minecraft:oak_log"},"weight":3},{"data":{"id":"minecraft:birch_log"},"weight":1}]}}
                  """);
        for (var entry : definitions.entrySet()) providers.put(entry.getKey(),
                BlockStateProvider.DIRECT_CODEC.parse(ops, JsonParser.parseString(entry.getValue())).getOrThrow());
        int rows = 0;
        try (var output = Files.newBufferedWriter(root.resolve("providers.jsonl"))) {
            for (var entry : providers.entrySet()) for (var input : List.of(Blocks.GRASS_BLOCK.defaultBlockState(),
                    Blocks.COARSE_DIRT.defaultBlockState(), Blocks.BIRCH_LOG.defaultBlockState().setValue(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, Direction.Axis.X))) {
                var level = new FeatureStampLevel(42, RegistryAccess.EMPTY, Blocks.DIRT.defaultBlockState());
                var origin = new BlockPos(0, 64, 0); level.placed().put(origin, input);
                var encoded = BlockStateProvider.DIRECT_CODEC.encodeStart(ops, entry.getValue()).getOrThrow();
                for (int seed = 0; seed < 64; seed++) {
                    var optionalRandom = new WorldgenRandom(new XoroshiroRandomSource(seed));
                    var regularRandom = new WorldgenRandom(new XoroshiroRandomSource(seed));
                    var optional = entry.getValue().getOptionalState(level, optionalRandom, origin);
                    var regular = entry.getValue().getState(level, regularRandom, origin);
                    var row = new JsonObject(); row.addProperty("name", entry.getKey()); row.add("provider", encoded);
                    row.addProperty("seed", Integer.toString(seed)); row.addProperty("input", state(input));
                    row.add("optional", optional == null ? JsonNull.INSTANCE : new JsonPrimitive(state(optional)));
                    row.addProperty("state", state(regular)); row.addProperty("after_optional", Long.toString(optionalRandom.nextLong()));
                    row.addProperty("after_state", Long.toString(regularRandom.nextLong()));
                    output.write(JSON.toJson(row)); output.newLine(); rows++;
                }
            }
        }
        System.out.println("Actual provider reference rows=" + rows);
    }

    private static void vegetation(Path root) throws Exception {
        var lookup = PredictionNetherDecorationTest.lookup;
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
        int rows = 0;
        try (var output = Files.newBufferedWriter(root.resolve("vegetation.jsonl"))) {
            for (String name : List.of("crimson_fungus", "warped_fungus", "crimson_forest_vegetation", "warped_forest_vegetation",
                    "nether_sprouts", "glowstone_extra", "weeping_vines", "twisting_vines", "spring_nether_closed", "patch_fire", "patch_soul_fire")) {
                var feature = lookup.lookupOrThrow(Registries.FEATURE).getOrThrow(ResourceKey.create(Registries.FEATURE,
                        Identifier.withDefaultNamespace(name))).value();
                var biome = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(name.contains("warped")
                        || name.equals("twisting_vines") ? Biomes.WARPED_FOREST : Biomes.CRIMSON_FOREST);
                var generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(biome), settings);
                Block ground = name.contains("warped") || name.equals("twisting_vines") ? Blocks.WARPED_NYLIUM
                        : name.equals("patch_fire") ? Blocks.NETHERRACK : name.equals("patch_soul_fire") ? Blocks.SOUL_SOIL : Blocks.CRIMSON_NYLIUM;
                int y = name.equals("glowstone_extra") || name.equals("weeping_vines") ? 79 : name.startsWith("spring_") ? 20 : 56;
                for (long seed : new long[]{0, 1, 7, 19, 42, 128, 731, -1}) for (int pattern = 0; pattern < 2; pattern++) {
                    var level = new FeatureStampLevel(seed, RegistryAccess.EMPTY, ground.defaultBlockState()) {
                        @Override public BlockState getBlockState(BlockPos pos) {
                            var edit = placed().get(pos); if (edit != null) return edit;
                            if (pos.getY() < 0 || pos.getY() >= 128) return Blocks.VOID_AIR.defaultBlockState();
                            return pos.getY() == 55 ? ground.defaultBlockState()
                                    : pos.getY() < 55 || pos.getY() >= 80 ? Blocks.NETHERRACK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        }
                        @Override public Holder<Biome> getBiome(BlockPos pos) { return biome; }
                        @Override public Holder<Biome> getNoiseBiome(int x, int by, int z) { return biome; }
                        @Override public Holder<Biome> getUncachedNoiseBiome(int x, int by, int z) { return biome; }
                    };
                    if (pattern == 1) for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
                        level.placed().put(new BlockPos(x, 58, z), Blocks.STONE.defaultBlockState());
                    var input = blocks(level.placed());
                    var random = new WorldgenRandom(new XoroshiroRandomSource(seed));
                    boolean placed = feature.place(level, generator, random, new BlockPos(0, y, 0));
                    var row = new JsonObject(); row.addProperty("feature", "minecraft:" + name); row.addProperty("seed", Long.toString(seed));
                    row.addProperty("pattern", pattern); row.addProperty("ground", BuiltInRegistries.BLOCK.getKey(ground).toString());
                    row.add("origin", JSON.toJsonTree(new int[]{0, y, 0}));
                    row.add("volume_min", JSON.toJsonTree(new int[]{-32, 0, -32})); row.add("volume_size", JSON.toJsonTree(new int[]{80, 128, 80}));
                    row.add("input", input); row.add("output", blocks(level.placed())); row.addProperty("placed", placed);
                    row.addProperty("after", Long.toString(random.nextLong()));
                    output.write(JSON.toJson(row)); output.newLine(); rows++;
                }
            }
        }
        System.out.println("Actual modern Nether feature reference rows=" + rows);
    }
}
