package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionDimensionContextTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }

    @Test void customDimensionUsesItsSyncedTypeAndSurvivesSamplerWrapping() {
        RegistryAccess access = TerrainPredictionFixtures.access();
        DimensionType nether = access.lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(BuiltinDimensionTypes.NETHER).value();
        var sampler = sampler(Identifier.parse("vss:custom_world"), access);
        var root = new JsonObject();
        root.add("vss_dimension_type", DimensionType.DIRECT_CODEC.encodeStart(TerrainPredictionFixtures.sourceLookup().createSerializationContext(JsonOps.INSTANCE), nether).getOrThrow());
        sampler.bindDimensionType(root);
        assertEquals(encoded(nether), encoded(sampler.dimensionTypeContext()));
        var wrapped = ClientTerrainSampler.withSurfaceOverride(sampler, (x, z) -> 70);
        assertSame(sampler.dimensionTypeContext(), wrapped.dimensionTypeContext());
        var level = new PredictionDecorationLevel(wrapped, wrapped, access, 0, 0);
        assertSame(wrapped.dimensionTypeContext(), level.dimensionType());
        assertTrue(level.dimensionType().hasCeiling());
        assertEquals(8, level.dimensionType().coordinateScale());
    }

    @Test void nativeStyleAdapterBindsTheJavaDecorationContext() {
        var java = sampler(Identifier.parse("vss:custom_world"), TerrainPredictionFixtures.access());
        var nativeAdapter = new ClientTerrainSampler(42, java.profile()) {
            @Override ClientTerrainSampler decorationContext() { return java; }
        };
        var access = TerrainPredictionFixtures.access();
        var type = access.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value();
        var root = new JsonObject();
        root.add("vss_dimension_type", DimensionType.DIRECT_CODEC.encodeStart(TerrainPredictionFixtures.sourceLookup().createSerializationContext(JsonOps.INSTANCE), type).getOrThrow());
        nativeAdapter.bindDimensionType(root);
        assertEquals(encoded(type), encoded(java.dimensionTypeContext()));
    }

    @Test void oldSnapshotUsesOnlyAnUnambiguousRegistryType() {
        var access = TerrainPredictionFixtures.access();
        assertEquals(access.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value(),
                sampler(Identifier.withDefaultNamespace("overworld"), access).dimensionTypeContext());
        assertThrows(UnsupportedOperationException.class,
                () -> sampler(Identifier.parse("vss:custom_world"), access).dimensionTypeContext());
    }

    @Test void dimensionMetadataKeepsNativeTerrainAndJavaStructureContextTogether() throws Exception {
        assertTrue(RustTerrainSampler.available());
        var access = TerrainPredictionFixtures.access();
        var type = access.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value();
        var java = sampler(Identifier.parse("vss:custom_world"), access);
        var original = LithostitchedNativeTest.document();
        original.add("possible_biomes", new com.google.gson.JsonArray());
        var document = original.deepCopy();document.add("vss_dimension_type", encoded(type));
        assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(document, new JsonObject()));
        try (var baseline = new RustTerrainSampler(RustWorldgenBackend.create(42,0,original.toString()),java.profile(),java);
             var nativeSampler = new RustTerrainSampler(RustWorldgenBackend.create(42,0,document.toString()),java.profile(),java)) {
            nativeSampler.bindDimensionType(document);
            assertEquals(encoded(type), encoded(nativeSampler.decorationContext().dimensionTypeContext()));
            assertEquals(baseline.sample(17,-19), nativeSampler.sample(17,-19), "structure metadata cannot change native terrain");
        }
    }

    private static com.google.gson.JsonElement encoded(DimensionType type) {
        return DimensionType.DIRECT_CODEC.encodeStart(TerrainPredictionFixtures.sourceLookup().createSerializationContext(JsonOps.INSTANCE), type).getOrThrow();
    }

    private static ClientTerrainSampler sampler(Identifier dimension, RegistryAccess access) {
        return new ClientTerrainSampler(42, new DimensionProfile(dimension, 42, -64, 384, "noise", "minecraft:overworld", 1)) {
            @Override RegistryAccess decorationAccess() { return access; }
        };
    }
}
