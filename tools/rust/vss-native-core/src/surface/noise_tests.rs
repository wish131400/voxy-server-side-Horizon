//! On-demand column inputs against the former eager computations.
use super::*;
use super::specialization_tests::fixture;

fn branch(condition: Condition, state: StateId) -> Rule {
    Rule::Condition(condition, Box::new(Rule::Block(state)))
}
fn biome_rule(name: &str, condition: Condition, state: StateId) -> Rule {
    Rule::Condition(Condition::Biome(vec![name.into()]), Box::new(branch(condition, state)))
}
fn eager_padding(surface: &Surface, graph: &Graph, x: i32, z: i32) -> Option<i32> {
    if surface.depth_predicates.is_empty() { return Some(1); }
    let depth = surface.depth(graph, x, z);
    let secondary = graph.noise(surface.secondary, x as f64, 0.0, z as f64);
    let mut maximum = 0;
    for &(offset, add, range) in &surface.depth_predicates {
        let extra = if range == 0 { 0 } else { ((secondary + 1.0) / 2.0 * range as f64) as i32 };
        let value = 1i32.checked_add(offset)?.checked_add(if add { depth } else { 0 })?.checked_add(extra)?;
        maximum = maximum.max(value);
    }
    let padding = maximum.checked_add(1)?;
    (padding <= 64).then_some(padding)
}

#[test]
fn on_demand_padding_matches_eager_for_signed_ranges_caps_and_overflow() {
    for modern in [false, true] {
        let (terrain, mut surface, _, _, _) = fixture(modern, -917);
        let cases = vec![vec![], vec![(0, false, 0)], vec![(-100, false, 0)],
            vec![(62, false, 0)], vec![(63, false, 0)], vec![(i32::MAX, false, 0)],
            vec![(i32::MIN, true, 0)], vec![(i32::MAX - 1, true, 0)],
            vec![(0, false, 6)], vec![(0, false, -6)], vec![(0, true, 0)],
            vec![(0, true, 30), (5, false, -30)],
            vec![(0, true, i32::MAX), (-1, true, i32::MIN)]];
        for predicates in cases {
            surface.depth_predicates = predicates;
            for x in [-131072, -17, -1, 0, 16, 131072] {
                for z in [-32768, -1, 0, 31, 32768] {
                    assert_eq!(surface.exterior_padding(&terrain.graph, x, z),
                        eager_padding(&surface, &terrain.graph, x, z),
                        "modern={modern} predicates={:?} position={x},{z}", surface.depth_predicates);
                }
            }
        }
        // Invalid IDs make any unintended lookup fail. Constant and empty
        // predicates must still return their exact capped/overflowing answer.
        surface.depth = usize::MAX;
        surface.secondary = usize::MAX;
        for (predicates, expected) in [(vec![], Some(1)), (vec![(0, false, 0)], Some(2)),
            (vec![(63, false, 0)], None), (vec![(i32::MAX, false, 0)], None)] {
            surface.depth_predicates = predicates;
            assert_eq!(surface.exterior_padding(&terrain.graph, 0, 0), expected);
        }
    }
}

#[test]
fn secondary_requirement_handles_negative_ranges_nested_not_and_namespace_fallback() {
    let (_, mut surface, _, _, _) = fixture(false, 0);
    let state = surface.default;
    for range in [-30, -1, 0, 1, 30] {
        surface.replace_rule(Rule::Namespaced {
            base: Box::new(biome_rule("minecraft:forest",
                Condition::Not(Box::new(Condition::Not(Box::new(Condition::Stone(0, true, range, false))))), state)),
            sources: vec![("custom".into(), branch(Condition::Stone(0, false, range, true), state))],
        });
        assert!(!surface.biome_rule("minecraft:plains").2);
        assert_eq!(surface.biome_rule("minecraft:forest").2, range != 0);
        assert_eq!(surface.biome_rule("missing:biome").2, range != 0);
        assert_eq!(surface.rule.for_biome("custom:test").unwrap().needs_secondary(), range != 0);
    }
}

fn refresh_summaries(surface: &mut Surface, palette: &Palette) {
    surface.global_summary = surface.analyse_summaries(palette, None);
    let names: Vec<_> = surface.summaries.keys().cloned().collect();
    for name in names {
        let value = surface.analyse_summaries(palette, Some(&name));
        surface.summaries.insert(name, value);
    }
}

#[test]
fn hole_independent_safety_omits_depth_and_dependent_safety_matches_eager() {
    let (terrain, mut surface, mut palette, _, _) = fixture(false, 0);
    let stone = surface.default;
    let water = palette.named("minecraft:water").unwrap();
    for rule in [Rule::Block(stone), Rule::Block(palette.air), Rule::Block(water),
        branch(Condition::Hole, palette.air),
        branch(Condition::Not(Box::new(Condition::Hole)), palette.air),
        Rule::Namespaced { base: Box::new(Rule::Block(stone)),
            sources: vec![("minecraft".into(), branch(Condition::Not(Box::new(Condition::Hole)), water))] }] {
        surface.replace_rule(rule);
        refresh_summaries(&mut surface, &palette);
        for name in ["minecraft:plains", "custom:missing"] {
            for x in [-131072, -17, 0, 16, 131072] { for z in [-32768, -1, 0, 32768] {
                let hole = surface.depth(&terrain.graph, x, z) <= 0;
                assert_eq!(surface.sparse_safe_at(&palette, name, &terrain.graph, x, z),
                    surface.analyse_summary(&palette, Some(name), false, Some(hole)));
                assert_eq!(surface.display_safe_at(&palette, name, &terrain.graph, x, z),
                    surface.analyse_summary(&palette, Some(name), true, Some(hole)));
            }}
            let values = surface.analyse_summaries(&palette, Some(name));
            let depth = surface.depth;
            surface.depth = usize::MAX;
            if values[0] == values[1] && values[0] == values[2] {
                assert_eq!(surface.sparse_safe_at(&palette, name, &terrain.graph, 0, 0), values[0]);
            }
            if values[3] == values[4] && values[3] == values[5] {
                assert_eq!(surface.display_safe_at(&palette, name, &terrain.graph, 0, 0), values[3]);
            }
            surface.depth = depth;
        }
    }
}

fn sample_material_paths(terrain: &Terrain, surface: &Surface, palette: &Palette,
    colors: &ClimateColors, data: &Biome) -> Vec<StateId> {
    let names = ["minecraft:plains", "minecraft:forest", "minecraft:desert", "custom:missing"];
    let mut output = Vec::new();
    for name in names { for (x, z, floor) in [(-17, 31, 72), (-1, -1, 100), (16, 0, 200)] {
        output.extend(surface.summary_materials(terrain, x, z, floor, i32::MIN,
            name, data, colors, Steepness::Known(false), None));
    }}
    for exterior in [false, true] {
        let mut column = ExteriorColumn { bottom: 80, top: 176,
            blocks: (80..176).map(|y| if y == 80 || (105..110).contains(&y) {
                palette.air
            } else { surface.default }).collect() };
        let mut job = terrain.job(0, 0, false).unwrap();
        surface.apply_retained(&mut job, &mut column, palette, 8, 8, [176; 4], colors,
            |p| Ok((names[p[1].rem_euclid(4) as usize], data)), exterior).unwrap();
        output.extend(column.blocks);
    }
    let mut volume = Volume::new([0, 80, 0], [16, 96, 16], palette.clone()).unwrap();
    volume.blocks.fill(surface.default);
    for x in 0..16 { for z in 0..16 {
        for y in [80, 105, 106, 107, 108] { volume.set([x, y, z], palette.air); }
    }}
    let mut job = terrain.job(0, 0, false).unwrap();
    surface.apply_column(&mut job, &mut volume, 0, 0, 8, 8, colors,
        |p| Ok((names[p[1].rem_euclid(4) as usize], data))).unwrap();
    output.extend(volume.blocks);
    output
}

#[test]
fn secondary_materials_match_eager_on_all_entries_with_mixed_biomes_and_signed_ranges() {
    for modern in [false, true] { for seed in [0, -917] {
        let (terrain, mut surface, mut palette, colors, data) = fixture(modern, seed);
        let dirt = palette.named("minecraft:dirt").unwrap();
        let grass = palette.named("minecraft:grass_block").unwrap();
        for range in [-30, -1, 0, 1, 30] {
            surface.replace_rule(Rule::Sequence(vec![
                biome_rule("minecraft:plains", Condition::Stone(0, true, 0, false), dirt),
                biome_rule("minecraft:forest", Condition::Not(Box::new(Condition::Stone(0, true, range, false))), grass),
                biome_rule("minecraft:desert", Condition::Stone(0, false, -range, true), dirt),
                Rule::Block(surface.default),
            ]));
            let actual = sample_material_paths(&terrain, &surface, &palette, &colors, &data);
            let plans = std::mem::take(&mut surface.biome_rules);
            surface.fallback_needs_secondary = true;
            surface.fallback_needs_cold = true;
            let expected = sample_material_paths(&terrain, &surface, &palette, &colors, &data);
            assert_eq!(actual, expected, "modern={modern} seed={seed} range={range}");
            surface.biome_rules = plans;
            surface.fallback_needs_secondary = surface.rule.needs_secondary();
            surface.fallback_needs_cold = surface.rule.needs_cold();
        }
        // A tree with zero secondary ranges must exercise all three real entry
        // points successfully even when its unused noise ID cannot be sampled.
        surface.replace_rule(branch(Condition::Not(Box::new(Condition::Stone(0, true, 0, false))), dirt));
        surface.secondary = usize::MAX;
        let _ = sample_material_paths(&terrain, &surface, &palette, &colors, &data);
    }}
}
