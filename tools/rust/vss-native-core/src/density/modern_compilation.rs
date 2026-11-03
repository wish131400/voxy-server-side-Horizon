//! Compare graph compilation with the uninterned, uncollapsed float evaluator.
use super::{Graph, Mode, ModernVolume};
use serde_json::{json, Value};

fn compare(doc: &Value, seed: i64) -> (usize, usize, u64) {
    let fast = Graph::from_document(seed, doc).unwrap();
    let recursive = Graph::build_document(seed, doc, false).unwrap();
    let mut a = fast.scratch(-32, 32, 4, 8).unwrap();
    let mut b = recursive.scratch(-32, 32, 4, 8).unwrap();
    b.disable_column_plan();
    // Reserve the dense budget in the oracle to retain the original hash
    // corner cache as well as the original uncollapsed graph traversal.
    b.modern_dense_bytes = super::modern::DENSE_CORNER_BYTES;
    let mut checked = 0;
    for volume in [
        ModernVolume::blocks([16, 384, 16], [-32, -64, 32]),
        ModernVolume {
            origin: [-7, 4, 11],
            size: [3, 5, 4],
            step: [4, 8, 4],
        },
        ModernVolume::blocks([1, 16, 1], [41, -8, -11]),
    ] {
        a.set_modern_volume(volume);
        b.set_modern_volume(volume);
        for mode in [Mode::Single, Mode::Block, Mode::Cell, Mode::Slice, Mode::Raw] {
            for (x, z) in [(-17, -3), (0, 0), (4, 4), (31, -11), (0, 0)] {
                for y in [-64, -1, 0, 1, 63, 64, 127, 319] {
                    for (name, &id) in &fast.roots {
                        let expected = recursive.compute(
                            recursive.root(name).unwrap(), [x, y, z], mode, &mut b,
                        );
                        let actual = fast.compute(id, [x, y, z], mode, &mut a);
                        assert_eq!(actual.to_bits(), expected.to_bits(),
                            "{name} seed={seed} {mode:?} volume={volume:?} at {x},{y},{z}");
                        checked += 1;
                    }
                }
            }
        }
    }
    (fast.nodes.len(), recursive.nodes.len(), checked)
}

#[test]
fn real_modern_graphs_match_uncollapsed_recursion_bits() {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/worldgen263");
    let mut checked = 0;
    for file in ["overworld.json", "nether.json", "end.json", "lithostitched-2.0.6.json"] {
        let doc: Value = serde_json::from_slice(&std::fs::read(root.join(file)).unwrap()).unwrap();
        for seed in [0, -917] {
            let (compiled, uncompiled, count) = compare(&doc, seed);
            assert!(compiled <= uncompiled);
            checked += count;
        }
    }
    assert_eq!(checked, 70_800);
}

#[test]
fn aliases_preserve_nonfinite_values_signed_zero_and_volume_boundaries() {
    let wrap = |input: Value| json!({"type":"minecraft:cache", "input":{
        "type":"minecraft:blend_density", "input":input}});
    let gradient = json!({"type":"minecraft:gradient", "axis":"y",
        "from_coordinate":-64,"to_coordinate":320,"from_value":1.,"to_value":-1.});
    let doc = json!({"vss_density_semantics":"float-26.3", "settings":{
        "legacy_random_source":false,"noise_router":{
            "negative_zero":wrap(json!(-0.0)),
            "subnormal":wrap(json!(1e-40)),
            "nan":wrap(json!({"type":"minecraft:sqrt","input":-1.})),
            "infinity":wrap(json!({"type":"minecraft:reciprocal","input":0.})),
            "nested_volume":wrap(json!({"type":"minecraft:interpolated",
                "cell_size_xz":6,"cell_size_y":10,
                "input":wrap(json!({"type":"minecraft:interpolated",
                    "cell_size_xz":4,"cell_size_y":8,"input":gradient}))})),
            "thin_layer":wrap(json!({"type":"minecraft:range_choice",
                "input":{"type":"minecraft:gradient","axis":"y",
                    "from_coordinate":-64,"to_coordinate":320,"from_value":-64.,"to_value":320.},
                "min_inclusive":63.,"max_exclusive":64.,
                "when_in_range":1.,"when_out_of_range":-1.}))
        }}});
    let (compiled, uncompiled, _) = compare(&doc, 0);
    assert!(compiled < uncompiled);
}
