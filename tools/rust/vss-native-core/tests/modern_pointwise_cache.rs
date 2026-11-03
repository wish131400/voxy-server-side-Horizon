use serde_json::Value;
use std::{fs, path::Path};
use vss_native_core::density::{Graph, Mode, ModernVolume};
#[test]
fn pure_pointwise_volume_cache_matches_recursion_bits_across_real_modern_graphs() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen263");
    let mut checked = 0u64;
    for file in [
        "overworld.json",
        "nether.json",
        "end.json",
        "lithostitched-2.0.6.json",
    ] {
        let doc: Value =
            serde_json::from_str(&fs::read_to_string(root.join(file)).unwrap()).unwrap();
        for seed in [0, -917] {
            let graph = Graph::from_document(seed, &doc).unwrap();
            let mut fast = graph.scratch(-32, 32, 4, 8).unwrap();
            let mut oracle = graph.scratch(-32, 32, 4, 8).unwrap();
            oracle.disable_column_plan();
            for volume in [
                ModernVolume {
                    origin: [-32, -64, 32],
                    size: [16, 384, 16],
                    step: [1; 3],
                },
                ModernVolume {
                    origin: [-7, 4, 11],
                    size: [3, 5, 4],
                    step: [4, 8, 4],
                },
                ModernVolume {
                    origin: [41, -8, -11],
                    size: [1, 16, 1],
                    step: [1, 1, 1],
                },
            ] {
                fast.set_modern_volume(volume);
                oracle.set_modern_volume(volume);
                for mode in [
                    Mode::Single,
                    Mode::Block,
                    Mode::Cell,
                    Mode::Slice,
                    Mode::Raw,
                ] {
                    for (x, z) in [(-17, -3), (0, 0), (4, 4), (31, -11), (0, 0)] {
                        for y in [-64, -1, 0, 1, 63, 64, 127, 319] {
                            for (name, &id) in &graph.roots {
                                let a = graph.compute(id, [x, y, z], mode, &mut fast);
                                let b = graph.compute(id, [x, y, z], mode, &mut oracle);
                                assert_eq!(a.to_bits(),b.to_bits(),"{file} seed={seed} root={name} {mode:?} volume={volume:?} at {x},{y},{z}");
                                checked += 1;
                            }
                        }
                    }
                }
            }
        }
    }
    println!("modern pointwise volume bit comparisons={checked}");
}
