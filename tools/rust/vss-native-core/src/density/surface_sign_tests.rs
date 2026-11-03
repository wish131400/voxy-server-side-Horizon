//! Verify sign proofs against exact float evaluation, including fallback boundaries.
use super::*;
use crate::density::{modern, ColumnPlan, Mode, ModernVolume};
use serde_json::{json, Value};

fn graph(density: Value) -> Graph {
    Graph::from_document(
        0,
        &json!({"vss_density_semantics":"float-26.3",
        "settings":{"legacy_random_source":false,"noise_router":{"final_density":density}}}),
    )
    .unwrap()
}

fn check(sign: Sign, value: f64, epsilon: f64) {
    match sign {
        Sign::NonPositive => assert!(value <= 0., "nonpositive proof for {value}"),
        Sign::Positive => assert!(
            value > epsilon,
            "positive proof for {value}, margin {epsilon}"
        ),
        Sign::Unknown => {}
    }
}

fn kinds(plan: &SurfacePlan) -> [usize; 4] {
    let mut out=[0;4];
    for op in &plan.ops {
        match op {
            Op::Leaf(_) => out[0]+=1,
            Op::Min(..) | Op::Max(..) => out[1]+=1,
            Op::Squeeze(_) => out[2]+=1,
            Op::ZeroBeard(..) => out[3]+=1,
            Op::Scale(..) => {},
        }
    }
    out
}

#[test]
fn real_modern_plans_prove_columns_and_cells_without_changing_density() {
    let fixtures =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen263");
    let mut proofs = [0; 2];
    let mut documents: Vec<(String, Value)> = ["overworld.json", "nether.json", "end.json"]
        .into_iter()
        .map(|name| {
            (
                name.into(),
                serde_json::from_slice(&std::fs::read(fixtures.join(name)).unwrap()).unwrap(),
            )
        })
        .collect();
    let mods: Value =
        serde_json::from_slice(&std::fs::read(fixtures.join("lithostitched-2.0.6.json")).unwrap())
            .unwrap();
    for name in ["shift_-1.9", "cell_13_DISTANCE_1", "fast_2"] {
        let density = mods["settings"]["noise_router"][name].clone();
        assert!(!density.is_null());
        let mut doc = mods.clone();
        doc["settings"]["noise_router"] = json!({"final_density":{
            "type":"minecraft:add", "left":{"type":"minecraft:min",
                "left":{"type":"minecraft:squeeze", "input":density}, "right":1.},
            "right":{"type":"minecraft:beardifier"}}});
        documents.push((name.into(), doc));
    }
    for (name, doc) in documents {
        for seed in [0, -917] {
            let g = Graph::from_document(seed, &doc).unwrap();
            let root = g.root("final_density").unwrap();
            let plan = g.surface_plan.as_ref().unwrap();
            {
                let n = kinds(plan);
                assert!(
                    n[3] > 0 && (!name.ends_with(".json") || n[2] > 0),
                    "{name}: squeeze and beard must be compiled"
                );
                if name == "overworld.json" {
                    assert!(n[1] > 0);
                }
            }
            for (x, z) in [(0, 0), (-32, 16), (100000, -100016)] {
                for bottom in [-64, 0, 64, 120] {
                    let mut s = g.scratch(x, z, 4, 8).unwrap();
                    let mut exact = g.scratch(x, z, 4, 8).unwrap();
                    let mut ranged = g.scratch(x, z, 4, 8).unwrap();
                    let volume = ModernVolume::blocks([16, 384, 16], [x, -64, z]);
                    s.set_modern_volume(volume);
                    exact.set_modern_volume(volume);
                    ranged.set_modern_volume(volume);
                    for beard in [0., -0., 0.25, -0.5] {
                        s.beard = beard;
                        exact.beard = beard;
                        ranged.beard = beard;
                        let column = g.surface_sign([x, bottom, z], bottom + 7, &mut s);
                        let cell = g.surface_cell_sign([x, bottom, z], bottom + 7, 4, &mut s);
                        assert_eq!(s.range_width, 4);
                        for (i, sign) in [column, cell].into_iter().enumerate() {
                            if sign != Sign::Unknown {
                                proofs[i] += 1;
                            }
                            let width = if i == 0 { 1 } else { 4 };
                            for dx in 0..width {
                                for dz in 0..width {
                                    for y in bottom..bottom + 8 {
                                        let p = [x + dx, y, z + dz];
                                        let value = g.compute(root, p, Mode::Cell, &mut exact);
                                        check(sign, value, 1e-12);
                                        // A sign query must not poison caches subsequently used by blocks.
                                        assert_eq!(
                                            g.compute(root, p, Mode::Cell, &mut s).to_bits(),
                                            g.compute(root, p, Mode::Cell, &mut ranged).to_bits()
                                        );
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    assert!(proofs[0] > 0 && proofs[1] > 0);
}

#[test]
fn zero_beard_min_shortcut_skips_unused_interpolation() {
    let g = graph(
        json!({"type":"minecraft:add", "left":{"type":"minecraft:min",
        "left":-1., "right":{"type":"minecraft:squeeze", "input":{
            "type":"minecraft:interpolated", "cell_size_xz":4, "cell_size_y":8,
            "input":{"type":"minecraft:gradient", "axis":"y", "from_coordinate":0,
                "to_coordinate":128, "from_value":1., "to_value":-1.}}}},
        "right":{"type":"minecraft:beardifier"}}),
    );
    assert!(matches!(g.surface_plan.as_ref().map(|p| p.ops[p.root]), Some(Op::ZeroBeard(_, _))));
    assert_eq!(kinds(g.surface_plan.as_ref().unwrap()), [2, 1, 1, 1]);
    let root = g.root("final_density").unwrap();
    let mut s = g.scratch(0, 0, 4, 8).unwrap();
    assert!(g.surface_sign([0, 0, 0], 7, &mut s) == Sign::NonPositive);
    assert!(s.modern_corners.is_empty());
    assert_eq!(s.modern_dense_bytes, 0);
    // With an actual structure contribution, use the original add range.
    for beard in [0.5, 2., -2., f64::NAN] {
        let mut fast = g.scratch(0, 0, 4, 8).unwrap();
        let mut old = g.scratch(0, 0, 4, 8).unwrap();
        fast.beard = beard;
        old.beard = beard;
        assert!(
            g.surface_sign([0, 0, 0], 7, &mut fast)
                == SurfacePlan::leaf(root).classify(&g, [0, 0, 0], 7, &mut old, 1e-12, false)
        );
    }
}

#[test]
fn min_with_nonfinite_branch_keeps_original_nan_behavior() {
    for bad in [
        json!({"type":"minecraft:sqrt", "input":-1.}),
        json!({"type":"minecraft:reciprocal", "input":0.}),
    ] {
        for swap in [false, true] {
            let (left, right) = if swap {
                (bad.clone(), json!(-1.))
            } else {
                (json!(-1.), bad.clone())
            };
            let g = graph(json!({"type":"minecraft:min", "left":left, "right":right}));
            assert!(matches!(g.surface_plan.as_ref().map(|p| p.ops[p.root]), Some(Op::Leaf(_))));
            let root = g.root("final_density").unwrap();
            let mut a = g.scratch(0, 0, 4, 8).unwrap();
            let sign = g.surface_sign([0, 0, 0], 7, &mut a);
            check(sign, g.compute(root, [0, 0, 0], Mode::Cell, &mut a), 1e-12);
        }
    }
}

#[test]
fn squeeze_sign_proofs_preserve_float_underflow_and_margins() {
    for bits in [
        0,
        1,
        2,
        3,
        0x007fffff,
        0x00800000,
        (1e-12_f32).to_bits(),
        (3e-12_f32).to_bits(),
        (1e-6_f32).to_bits(),
        (0.5_f32).to_bits(),
        (1.0_f32).to_bits(),
        (1e30_f32).to_bits(),
    ] {
        for negative in [0, 0x80000000] {
            let input = f32::from_bits(bits | negative) as f64;
            // Build an explicit node: constant folding would bypass the plan under test.
            let mut g = graph(json!(input));
            let id = g.root("final_density").unwrap();
            let root = g.nodes.len();
            g.nodes.push(Node::Modern(Box::new(modern::Node::Unary(
                modern::Unary::Squeeze,
                id,
            ))));
            g.roots.insert("final_density".into(), root);
            g.column_plan = ColumnPlan::compile(&g);
            let plan = SurfacePlan::compile(&g).unwrap();
            assert!(matches!(plan.ops[plan.root], Op::Squeeze(_)));
            for epsilon in [0., 1e-12, 1e-6, 0.399, 0.4, f64::INFINITY] {
                let mut s = g.scratch(0, 0, 4, 8).unwrap();
                let sign = plan.classify(&g, [0, 0, 0], 7, &mut s, epsilon, false);
                let value = g.compute(root, [0, 0, 0], Mode::Cell, &mut s);
                check(sign, value, epsilon);
            }
        }
    }
}

#[test]
fn thin_layers_and_nested_grids_remain_inconclusive_when_required() {
    let thin = json!({"type":"minecraft:range_choice", "input":{
        "type":"minecraft:gradient", "axis":"y", "from_coordinate":0,
        "to_coordinate":128, "from_value":0., "to_value":128.},
        "min_inclusive":3., "max_exclusive":4., "when_in_range":1., "when_out_of_range":-1.});
    let nested = json!({"type":"minecraft:interpolated", "cell_size_xz":6,"cell_size_y":10,
        "input":{"type":"minecraft:interpolated", "cell_size_xz":4,"cell_size_y":3,
            "input":thin}});
    let mut mixed = 0;
    for input in [thin, nested] {
        let g = graph(json!({"type":"minecraft:add", "left":{
            "type":"minecraft:min", "left":{"type":"minecraft:squeeze", "input":input},
            "right":1.}, "right":{"type":"minecraft:beardifier"}}));
        let root = g.root("final_density").unwrap();
        for volume in [
            ModernVolume::blocks([16, 128, 16], [0, 0, 0]),
            ModernVolume {
                origin: [-3, -7, 5],
                size: [3, 5, 4],
                step: [4, 8, 4],
            },
        ] {
            let mut s = g.scratch(0, 0, 4, 8).unwrap();
            let mut exact = g.scratch(0, 0, 4, 8).unwrap();
            s.set_modern_volume(volume);
            exact.set_modern_volume(volume);
            let sign = g.surface_sign([0, 0, 0], 7, &mut s);
            let values: Vec<_> = (0..8)
                .map(|y| g.compute(root, [0, y, 0], Mode::Cell, &mut exact))
                .collect();
            if values.iter().any(|v| *v > 0.) && values.iter().any(|v| *v <= 0.) {
                assert!(sign == Sign::Unknown);
                mixed += 1;
            }
            for y in 0..8 {
                check(
                    sign,
                    g.compute(root, [0, y, 0], Mode::Cell, &mut exact),
                    1e-12,
                );
            }
        }
    }
    assert!(mixed > 0);
}

#[test]
fn shared_min_dag_has_bounded_plan_and_correct_leaf_fallback() {
    let mut g = graph(json!(-1.));
    let mut root = g.root("final_density").unwrap();
    // 36 shared nodes must not expand to a tree of 2^36 boxes.
    for _ in 0..36 {
        g.nodes.push(Node::Modern(Box::new(modern::Node::Binary(
            modern::Binary::Min,
            root,
            root,
        ))));
        root = g.nodes.len() - 1;
    }
    g.roots.insert("final_density".into(), root);
    g.column_plan = ColumnPlan::compile(&g);
    let plan = SurfacePlan::compile(&g).unwrap();
    let count = kinds(&plan);
    assert!(count.iter().sum::<usize>() <= MAX_PLAN_EXPANSIONS * 2 + 1);
    assert_eq!(count[0], 1, "one shared leaf, without exponential expansion");
    assert_eq!(count[1], 36, "each shared Min is compiled once");
    for bottom in [0, 64, 120] {
        let mut s = g.scratch(0, 0, 4, 8).unwrap();
        let mut exact = g.scratch(0, 0, 4, 8).unwrap();
        let sign = plan.classify(&g, [0, bottom, 0], bottom + 7, &mut s, 1e-12, false);
        for y in bottom..bottom + 8 {
            check(
                sign,
                g.compute(root, [0, y, 0], Mode::Cell, &mut exact),
                1e-12,
            );
        }
    }
}
