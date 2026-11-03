//! Compare deferred material inputs with eager queries and their traversal effects.
use super::*;
use std::cell::Cell;

fn fixture(file: &str, seed: i64) -> (Terrain, Surface, ClimateColors, Biome, Palette) {
    let mut doc: Value = serde_json::from_str(include_str!("../../tests/fixtures/worldgen263/features.json")).unwrap();
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen263");
    let density: Value = serde_json::from_slice(&std::fs::read(root.join(file)).unwrap()).unwrap();
    for key in ["vss_density_semantics", "settings", "density_functions", "noises"] {
        doc[key] = density[key].clone();
    }
    let mut terrain = Terrain::from_document(seed, &doc).unwrap();
    let mut palette = Palette::from_json(&doc["block_definitions"]).unwrap();
    let surface = Surface::new(&mut terrain, &doc, &mut palette).unwrap();
    let colors = ClimateColors::new(vec![0; 65536], vec![0; 65536]).unwrap();
    let biome = Biome { temperature: 0.8, downfall: 0.4, frozen: false,
        grass: None, foliage: None, water: 0, modifier: crate::biome::GrassModifier::None };
    (terrain, surface, colors, biome, palette)
}

fn branch(condition: Condition, state: StateId) -> Rule {
    Rule::Condition(condition, Box::new(Rule::Block(state)))
}

#[test]
fn modern_steepness_is_only_requested_by_reachable_rules_and_once_per_column() {
    let (t, mut surface, colors, biome, mut palette) = fixture("overworld.json", 0);
    let stone = palette.named("minecraft:stone").unwrap();
    let dirt = palette.named("minecraft:dirt").unwrap();
    let rules = vec![
        (Rule::Block(stone), 0),
        (Rule::Sequence(vec![Rule::Block(dirt), branch(Condition::Steep, stone)]), 0),
        (Rule::Condition(Condition::Biome(vec!["minecraft:desert".into()]),
            Box::new(branch(Condition::Steep, stone))), 0),
        (Rule::Condition(Condition::Y(1000, 0, false),
            Box::new(branch(Condition::Steep, stone))), 0),
        (branch(Condition::Not(Box::new(Condition::Steep)), dirt), 1),
        (Rule::Sequence(vec![
            Rule::Condition(Condition::Steep,
                Box::new(branch(Condition::Not(Box::new(Condition::Steep)), stone))),
            branch(Condition::Not(Box::new(Condition::Steep)), dirt),
            Rule::Block(stone),
        ]), 1),
        (Rule::Namespaced { base: Box::new(branch(Condition::Steep, stone)),
            sources: vec![("minecraft".into(), Rule::Block(dirt))] }, 0),
        (Rule::Namespaced { base: Box::new(branch(Condition::Steep, stone)),
            sources: vec![("custom".into(), Rule::Block(dirt))] }, 1),
        (Rule::Namespaced { base: Box::new(branch(Condition::Steep, stone)),
            sources: vec![("minecraft".into(), branch(Condition::Biome(vec![]), dirt))] }, 1),
    ];
    for (rule, queries) in rules {
        surface.replace_rule(rule);
        let mut workspace = None;
        for answer in [false, true] {
            for (x, z, floor, water) in [(-17, 31, 72, i32::MIN), (-1, -1, 100, 104), (16, 0, 200, i32::MIN)] {
                let expected = surface.summary_materials(&t, x, z, floor, water, "minecraft:plains",
                    &biome, &colors, Steepness::Known(answer), None);
                let count = Cell::new(0);
                let actual = surface.display_materials(&t, x, z, floor, water, "minecraft:plains",
                    &biome, &colors, || { count.set(count.get() + 1); answer }, &mut workspace);
                assert_eq!(actual, expected);
                assert_eq!(count.get(), queries);
            }
        }
    }
}

#[test]
fn lazy_slope_matches_eager_materials_and_later_density_across_dimensions() {
    let mut checked = 0;
    let mut omitted = 0;
    for file in ["overworld.json", "nether.json", "end.json"] {
        for seed in [0, -917] {
            for require_slope in [false, true] {
                let (t, mut surface, colors, biome, mut palette) = fixture(file, seed);
                if require_slope {
                    let dirt = palette.named("minecraft:dirt").unwrap();
                    surface.replace_rule(Rule::Sequence(vec![branch(Condition::Steep, dirt),
                        branch(Condition::Not(Box::new(Condition::Steep)), surface.default)]));
                }
                for (cx, cz) in [(-2, 1), (6250, -6250)] {
                    let mut eager = t.job(cx * 16, cz * 16, false).unwrap();
                    let mut lazy = t.job(cx * 16, cz * 16, false).unwrap();
                    eager.display = true;
                    lazy.display = true;
                    let mut eager_workspace = None;
                    let mut lazy_workspace = None;
                    for i in (0..256).step_by(4).rev().chain([0, 252, 16]) {
                        let (x, z) = (cx * 16 + i % 16, cz * 16 + i / 16);
                        let height = eager.density_surface(x, z);
                        assert_eq!(lazy.density_surface(x, z), height, "{file} seed={seed} {x},{z}");
                        let floor = height.map_or(t.min_y, |y| y + 1).max(t.min_y + 1);
                        let (water, fluid) = t.preview_fluid(floor);
                        let water = if fluid == 0 { i32::MIN } else { water };
                        // The eager job pays for slope before material evaluation,
                        // as the original display path did, including cache writes.
                        let steep = eager.display_steep(x, floor - 1, z);
                        let expected = surface.display_materials(&t, x, z, floor, water, "minecraft:plains",
                            &biome, &colors, || steep, &mut eager_workspace);
                        let count = Cell::new(0);
                        let actual = surface.display_materials(&t, x, z, floor, water, "minecraft:plains",
                            &biome, &colors, || { count.set(count.get() + 1); lazy.display_steep(x, floor - 1, z) },
                            &mut lazy_workspace);
                        assert_eq!(actual, expected, "{file} seed={seed} {x},{z}");
                        assert!(count.get() <= 1);
                        if require_slope { assert_eq!(count.get(), 1); }
                        omitted += usize::from(count.get() == 0);
                        checked += 1;
                    }
                }
            }
        }
    }
    assert_eq!(checked, 1608);
    assert!(omitted > 0, "ordinary rules should avoid unneeded neighbour work");
}

#[test]
fn legacy_materials_keep_eager_neighbour_query_even_without_a_steep_rule() {
    let doc: Value = serde_json::from_str(include_str!("../../tests/fixtures/worldgen/overworld.json")).unwrap();
    let mut t = Terrain::from_document(0, &doc).unwrap();
    let blocks: Value = serde_json::from_str(include_str!("../../tests/fixtures/worldgen/blocks.json")).unwrap();
    let mut palette = Palette::from_json(&blocks).unwrap();
    let mut surface = Surface::new(&mut t, &doc, &mut palette).unwrap();
    surface.replace_rule(Rule::Block(surface.default));
    let colors = ClimateColors::new(vec![0; 65536], vec![0; 65536]).unwrap();
    let biome = Biome { temperature: 0.8, downfall: 0.4, frozen: false,
        grass: None, foliage: None, water: 0, modifier: crate::biome::GrassModifier::None };
    let count = Cell::new(0);
    let result = surface.display_materials(&t, 0, 0, 72, i32::MIN, "minecraft:plains", &biome,
        &colors, || { count.set(count.get() + 1); false }, &mut None);
    assert_eq!(result, [surface.default; 3]);
    assert_eq!(count.get(), 1);
}
