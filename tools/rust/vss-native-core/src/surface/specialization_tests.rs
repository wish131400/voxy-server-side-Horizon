//! Exact biome specialization against the original, unspecialized interpreter.
use super::*;
use serde_json::json;
use std::cell::Cell;

pub(super) fn fixture(modern: bool, seed: i64) -> (Terrain, Surface, Palette, ClimateColors, Biome) {
    let mut doc: Value = if modern {
        serde_json::from_str(include_str!("../../tests/fixtures/worldgen263/features.json")).unwrap()
    } else {
        serde_json::from_str(include_str!("../../tests/fixtures/worldgen/overworld.json")).unwrap()
    };
    if modern {
        let density: Value = serde_json::from_str(include_str!("../../tests/fixtures/worldgen263/overworld.json")).unwrap();
        for key in ["vss_density_semantics", "settings", "density_functions", "noises"] {
            doc[key] = density[key].clone();
        }
    } else {
        doc["block_definitions"] = serde_json::from_str(include_str!("../../tests/fixtures/worldgen/blocks.json")).unwrap();
        doc["biomes"] = serde_json::from_str(include_str!("../../tests/fixtures/worldgen/biomes.json")).unwrap();
    }
    let mut terrain = Terrain::from_document(seed, &doc).unwrap();
    let mut palette = Palette::from_json(&doc["block_definitions"]).unwrap();
    let surface = Surface::new(&mut terrain, &doc, &mut palette).unwrap();
    let colors = ClimateColors::new(vec![0; 65536], vec![0; 65536]).unwrap();
    let biome = Biome { temperature: 0.0, downfall: 0.4, frozen: true,
        grass: None, foliage: None, water: 0, modifier: crate::biome::GrassModifier::None };
    (terrain, surface, palette, colors, biome)
}

fn branch(condition: Condition, rule: Rule) -> Rule {
    Rule::Condition(condition, Box::new(rule))
}
fn biome(name: &str) -> Condition { Condition::Biome(vec![name.into()]) }
fn negated(condition: Condition) -> Condition { Condition::Not(Box::new(condition)) }

#[test]
fn biome_specialization_keeps_all_dynamic_conditions_and_negations_exact() {
    let (terrain, surface, mut palette, _, _) = fixture(false, 731);
    let dirt = palette.named("minecraft:dirt").unwrap();
    let grass = palette.named("minecraft:grass_block").unwrap();
    let conditions = vec![
        biome("minecraft:plains"), negated(biome("minecraft:plains")),
        negated(negated(biome("minecraft:forest"))), Condition::Biome(vec![]),
        Condition::Noise(surface.depth, -0.05, 0.05, false),
        Condition::Noise(surface.depth, -0.2, 0.2, true),
        Condition::Gradient(Positional::Legacy(17), -20, 100),
        Condition::Gradient(Positional::Xoroshiro(19, 29), -20, 100),
        Condition::Y(60, 2, false), Condition::Y(60, -2, true),
        Condition::Water(2, 1, false), Condition::Water(-2, 2, true),
        Condition::Cold, Condition::Steep, Condition::Hole, Condition::Above,
        Condition::Stone(0, true, 0, false), Condition::Stone(-2, false, 7, true),
        Condition::AcSimplex { min: -0.1, max: 0.1, scale: 0.1, y_scale: 0.2, offset: 3 },
        Condition::QuadNoise { id: surface.depth, radius: 32.0, threshold: 0.25 },
    ];
    for (index, condition) in conditions.into_iter().enumerate() {
        for condition in [condition.clone(), negated(condition)] {
            let original = Rule::Sequence(vec![
                branch(biome("minecraft:plains"), branch(condition.clone(), Rule::Block(dirt))),
                branch(negated(biome("minecraft:plains")),
                    branch(negated(condition.clone()), Rule::Block(grass))),
                branch(negated(negated(biome("minecraft:forest"))), Rule::Bands),
                // Even a dynamic condition whose body is known empty must
                // retain its query, rather than becoming a new dead-code pass.
                branch(condition, branch(Condition::Biome(vec![]), Rule::Block(palette.air))),
                Rule::Block(surface.default),
            ]);
            for name in ["minecraft:plains", "minecraft:forest", "custom:forest", "missing", ""] {
                let compiled = original.for_biome(name).unwrap_or_else(|| Rule::Sequence(vec![]));
                for sample in 0..128 {
                    let c = Context { p: [sample * 17 - 1053, sample * 3 - 80, 871 - sample * 31],
                        biome: name, cold: sample & 1 != 0, steep: Steepness::Known(sample & 2 != 0),
                        depth: sample % 7 - 2, secondary: sample as f64 / 64.0 - 1.0,
                        above: sample % 9 + 1, below: sample % 11 + 1,
                        water: if sample & 4 == 0 { i32::MIN } else { 63 }, min_surface: 80 };
                    assert_eq!(surface.rule(&compiled, &terrain.graph, &c, &mut None),
                        surface.rule(&original, &terrain.graph, &c, &mut None),
                        "condition={index} biome={name} sample={sample}");
                }
            }
        }
    }
}

#[test]
fn namespace_specialization_preserves_first_source_and_dynamic_base_fallback() {
    let (terrain, mut surface, mut palette, _, _) = fixture(false, 31);
    let dirt = palette.named("minecraft:dirt").unwrap();
    let water = palette.named("minecraft:water").unwrap();
    surface.replace_rule(Rule::Namespaced {
        base: Box::new(Rule::Namespaced { base: Box::new(Rule::Block(surface.default)),
            sources: vec![("custom".into(), branch(Condition::Cold, Rule::Block(water)))] }),
        sources: vec![
            ("minecraft".into(), branch(Condition::Y(64, 0, false),
                branch(biome("minecraft:plains"), Rule::Block(dirt)))),
            ("minecraft".into(), Rule::Block(water)),
            ("custom".into(), branch(negated(Condition::Cold), Rule::Block(dirt))),
        ],
    });
    for name in ["minecraft:plains", "minecraft:forest", "custom:tree", "absent:tree", ""] {
        let compiled = surface.rule.for_biome(name).unwrap();
        for y in [63, 64, 65] { for cold in [false, true] {
            let c = Context { p: [0, y, 0], biome: name, cold, steep: Steepness::Known(false),
                depth: 3, secondary: 0.0, above: 1, below: 1, water: i32::MIN, min_surface: 0 };
            assert_eq!(surface.rule(&compiled, &terrain.graph, &c, &mut None),
                surface.rule(&surface.rule, &terrain.graph, &c, &mut None));
        }}
    }
    // Registered biomes use their plan; a missing biome must keep the full
    // original tree and its cold requirement, never borrow another biome's plan.
    let (fallback, needs_cold, _) = surface.biome_rule("unregistered:test");
    assert!(std::ptr::eq(fallback, &surface.rule));
    assert!(needs_cold);
}

#[test]
fn specialization_skips_cold_only_when_surviving_rules_cannot_read_it() {
    let (_, mut surface, _, _, _) = fixture(false, 0);
    surface.replace_rule(Rule::Namespaced {
        base: Box::new(branch(biome("minecraft:forest"),
            branch(negated(negated(Condition::Cold)), Rule::Block(surface.default)))),
        sources: vec![("custom".into(), branch(Condition::Cold, Rule::Block(surface.default)))],
    });
    assert!(!surface.biome_rule("minecraft:plains").1);
    assert!(surface.biome_rule("minecraft:forest").1);
    assert!(surface.rule.for_biome("custom:test").unwrap().needs_cold());
    assert!(surface.biome_rule("unknown:test").1);
}

#[test]
fn dynamic_query_is_preserved_when_biome_specialization_empties_its_body() {
    let (terrain, surface, _, _, _) = fixture(false, 0);
    let original = branch(Condition::Steep,
        branch(biome("minecraft:forest"), Rule::Block(surface.default)));
    let compiled = original.for_biome("minecraft:plains").unwrap();
    for rule in [&original, &compiled] {
        let queries = Cell::new(0);
        let query = || { queries.set(queries.get() + 1); true };
        let c = Context { p: [0, 64, 0], biome: "minecraft:plains", cold: false,
            steep: Steepness::Lazy(&query), depth: 3, secondary: 0.0,
            above: 1, below: 1, water: i32::MIN, min_surface: 0 };
        assert_eq!(surface.rule(rule, &terrain.graph, &c, &mut None), None);
        assert_eq!(queries.get(), 1);
    }
}

#[test]
fn copied_ore_and_positional_state_match_original_without_changing_later_density() {
    let (mut terrain, surface, mut palette, _, _) = fixture(false, 731);
    let doc: Value = serde_json::from_str(include_str!("../../tests/fixtures/worldgen/overworld.json")).unwrap();
    for (density, richness, gap) in [(0.0, 1.0, -1.0), (0.7, 0.4, -1.0), (1.0, 1.0, 0.0)] {
        let original = Rule::Namespaced { base: Box::new(Rule::Bands), sources: vec![
            ("minecraft".into(), Rule::Ore(material::OreRule::parse(&json!({
                "density": density, "richness": richness, "filler_gap": gap,
                "raw_ore_chance": 0.35, "ore_block": "minecraft:copper_ore",
                "raw_ore_block": "minecraft:raw_copper_block", "filler_block": "minecraft:granite"
            }), &mut terrain.graph, &doc, &mut palette).unwrap()))] };
        let compiled = original.for_biome("minecraft:plains").unwrap();
        let mut expected = terrain.graph.scratch(0, 0, terrain.cell_width, terrain.cell_height).unwrap();
        let mut actual = terrain.graph.scratch(0, 0, terrain.cell_width, terrain.cell_height).unwrap();
        terrain.graph.initialize_column_caches(&mut expected);
        terrain.graph.initialize_column_caches(&mut actual);
        let final_density = terrain.graph.root("final_density").unwrap();
        for i in 0..256 {
            let p = [i % 16, i % 128 - 32, i / 16];
            let c = Context { p, biome: "minecraft:plains", cold: false, steep: Steepness::Known(false),
                depth: 3, secondary: 0.0, above: 1, below: 1, water: i32::MIN, min_surface: 0 };
            assert_eq!(surface.rule(&compiled, &terrain.graph, &c, &mut Some(&mut actual)),
                surface.rule(&original, &terrain.graph, &c, &mut Some(&mut expected)));
            assert_eq!(terrain.graph.compute(final_density, p, Mode::Block, &mut actual).to_bits(),
                terrain.graph.compute(final_density, p, Mode::Block, &mut expected).to_bits());
        }
    }
}

#[test]
fn modern_and_legacy_material_paths_match_unspecialized_eager_cold_reference() {
    for modern in [false, true] { for seed in [0, -917] {
        let (terrain, mut surface, palette, colors, data) = fixture(modern, seed);
        let names = ["minecraft:plains", "minecraft:forest", "minecraft:swamp", "minecraft:badlands",
            "minecraft:frozen_ocean", "unregistered:forest"];
        let mut samples = Vec::new();
        for name in names { for (x, z, floor, water) in [
            (-17, 31, 72, i32::MIN), (-1, -1, 100, 104), (16, 0, 200, i32::MIN),
        ] { for steep in [false, true] {
            samples.push((name, x, z, floor, water, steep));
        }}}
        let expected: Vec<_> = samples.iter().map(|&(name, x, z, floor, water, steep)|
            surface.summary_materials(&terrain, x, z, floor, water, name, &data, &colors,
                Steepness::Known(steep), None)).collect();
        let plans = std::mem::take(&mut surface.biome_rules);
        surface.fallback_needs_cold = true; // Original implementation evaluated temperature eagerly.
        for (sample, expected) in samples.iter().zip(expected) {
            let &(name, x, z, floor, water, steep) = sample;
            assert_eq!(surface.summary_materials(&terrain, x, z, floor, water, name, &data, &colors,
                Steepness::Known(steep), None), expected, "modern={modern} seed={seed} sample={sample:?}");
        }
        surface.biome_rules = plans;
        surface.fallback_needs_cold = surface.rule.needs_cold();
        for exterior in [false, true] {
            let blocks: Vec<_> = (80..176).map(|y| if y == 80 || (105..110).contains(&y) {
                palette.air
            } else if (141..144).contains(&y) { surface.water } else { surface.default }).collect();
            let mut optimized = ExteriorColumn { bottom: 80, top: 176, blocks: blocks.clone() };
            let mut reference = ExteriorColumn { bottom: 80, top: 176, blocks };
            let mut actual_job = terrain.job(0, 0, false).unwrap();
            let mut expected_job = terrain.job(0, 0, false).unwrap();
            surface.apply_retained(&mut actual_job, &mut optimized, &palette, 8, 8, [176; 4], &colors,
                |p| Ok((names[p[1].rem_euclid(names.len() as i32) as usize], &data)), exterior).unwrap();
            let plans = std::mem::take(&mut surface.biome_rules);
            surface.fallback_needs_cold = true;
            surface.apply_retained(&mut expected_job, &mut reference, &palette, 8, 8, [176; 4], &colors,
                |p| Ok((names[p[1].rem_euclid(names.len() as i32) as usize], &data)), exterior).unwrap();
            assert_eq!(optimized.blocks, reference.blocks, "retained modern={modern} seed={seed} exterior={exterior}");
            surface.biome_rules = plans;
            surface.fallback_needs_cold = surface.rule.needs_cold();
        }
        // Exercise the dense-volume entry too, including biome changes with Y.
        let mut optimized = Volume::new([0, 80, 0], [16, 96, 16], palette.clone()).unwrap();
        optimized.blocks.fill(surface.default);
        for x in 0..16 { for z in 0..16 {
            for y in [80, 105, 106, 107, 108] { optimized.set([x, y, z], palette.air); }
        }}
        let mut reference = Volume::new([0, 80, 0], [16, 96, 16], palette.clone()).unwrap();
        reference.blocks.clone_from(&optimized.blocks);
        let mut actual_job = terrain.job(0, 0, false).unwrap();
        let mut expected_job = terrain.job(0, 0, false).unwrap();
        // Avoid geometry extensions in this synthetic column; they are not part
        // of biome-rule compilation and retain their own temperature queries.
        let layer_names = ["minecraft:plains", "minecraft:forest", "unregistered:forest"];
        surface.apply_column(&mut actual_job, &mut optimized, 0, 0, 8, 8, &colors,
            |p| Ok((layer_names[p[1].rem_euclid(3) as usize], &data))).unwrap();
        surface.biome_rules.clear();
        surface.fallback_needs_cold = true;
        surface.apply_column(&mut expected_job, &mut reference, 0, 0, 8, 8, &colors,
            |p| Ok((layer_names[p[1].rem_euclid(3) as usize], &data))).unwrap();
        assert_eq!(optimized.blocks, reference.blocks, "dense modern={modern} seed={seed}");
    }}
}
