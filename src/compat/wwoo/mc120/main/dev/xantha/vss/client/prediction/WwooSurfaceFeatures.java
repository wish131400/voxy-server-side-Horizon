package dev.xantha.vss.client.prediction;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

final class WwooSurfaceFeatures {
    private WwooSurfaceFeatures() { }

    /** WWOO 2.0.0 places exposed terrain and trees in normally underground stages.
     * Registered IDs keep unrelated cave and ore features out of surface prediction. */
    static boolean wythersSurfaceFeature(GenerationStep.Decoration stage,
            net.minecraft.resources.ResourceLocation id, boolean trees) {
        if (id == null || !id.getNamespace().equals("wythers")) return false;
        String path = id.getPath();
        return switch (stage) {
            case SURFACE_STRUCTURES -> path.equals("terrain/feature/tuff_spikes")
                    || path.equals("terrain/local/swamp_pools")
                    || path.equals("terrain/local/volcanic_pools")
                    || path.equals("terrain/feature/tepui_falls")
                    || path.equals("terrain/feature/tepui_terrain");
            case UNDERGROUND_STRUCTURES -> path.equals("terrain/extended/sandify_rooted_dirt")
                    || path.equals("terrain/extended/red_sandify_rooted_dirt");
            case STRONGHOLDS -> path.equals("terrain/extended/base_windswept_gravelly_hills")
                    || path.equals("terrain/local/base_dark_forest")
                    || path.equals("terrain/local/volcanic_basalt_cliffs")
                    || path.equals("terrain/extended/volcanic_fallout")
                    || path.equals("terrain/extended/volcanic_flows")
                    || path.equals("terrain/feature/tepui_surface")
                    || path.equals("terrain/feature/tepui_lakes")
                    || trees && path.startsWith("vegetation/") && path.contains("/trees/");
            case UNDERGROUND_DECORATION -> trees && (path.equals("terrain/local/grass_spread")
                    || path.startsWith("vegetation/") && (path.contains("/trees/")
                    || path.contains("/patch/flower_bluebells")));
            case FLUID_SPRINGS -> path.startsWith("terrain/local/thermal_savanna_")
                    || path.equals("terrain/local/thermal_taiga_white")
                    || path.equals("terrain/feature/thermal_savanna_block_spikes");
            default -> false;
        };
    }

    static boolean wythersOreStageVegetation(net.minecraft.resources.ResourceLocation id) {
        return id != null && id.getNamespace().equals("wythers")
                && (id.getPath().equals("vegetation/local/other/coral_disks")
                || id.getPath().equals("vegetation/local/patch/dead_corals_on_gravel"));
    }

    /** WWOO 1.20.1 uses surface-height patches and a grass-to-mycelium ore replacement. */
    static boolean wythersOreStageSurfaceEdit(net.minecraft.resources.ResourceLocation id) {
        if (id == null || !id.getNamespace().equals("wythers")) return false;
        return switch (id.getPath()) {
            case "terrain/local/red_sand_spread", "terrain/local/savanna_packed_mud",
                    "terrain/local/snow_spread", "terrain/local/replace_grass_to_mycelium" -> true;
            default -> false;
        };
    }

    /** WWOO also uses the ore stage for a sea-level packed-mud-to-moss surface replacement. */
    static boolean surfaceMudReplacement(PlacedFeature feature) {
        var configured = feature.feature().value();
        if (configured.feature() != net.minecraft.world.level.levelgen.feature.Feature.ORE
                || !(configured.config() instanceof net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration ore)
                || ore.targetStates.size() != 1
                || feature.placement().stream().noneMatch(modifier ->
                        modifier instanceof net.minecraft.world.level.levelgen.placement.HeightRangePlacement)) return false;
        var target = ore.targetStates.get(0);
        return target.state.is(Blocks.MOSS_BLOCK)
                && target.target instanceof net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest
                && target.target.test(Blocks.PACKED_MUD.defaultBlockState(), net.minecraft.util.RandomSource.create(0))
                && !target.target.test(Blocks.STONE.defaultBlockState(), net.minecraft.util.RandomSource.create(0));
    }
}
