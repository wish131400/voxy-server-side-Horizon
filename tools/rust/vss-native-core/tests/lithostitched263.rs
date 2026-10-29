//! Explicitly opt-in comparison against a released Java 2.0.6 fixture. Generate
//! it with LithostitchedNativeTest.releasedScalarAndVolumeReferences, then set
//! VSS_LITHOSTITCHED_263_FIXTURE and run this test with --ignored.
use serde_json::Value;
use vss_native_core::density::{Graph, Mode, ModernVolume};

#[test]
#[ignore = "requires the released Lithostitched 2.0.6 scalar/volume fixture"]
fn released_lithostitched_263_scalar_and_volume_bits() {
    let path = std::env::var("VSS_LITHOSTITCHED_263_FIXTURE")
        .expect("set VSS_LITHOSTITCHED_263_FIXTURE to the released Java reference JSON");
    let doc: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    let mut checked = 0;
    for seed in [0, -918273645, i64::MIN, i64::MAX] {
        let graph = Graph::from_document(seed, &doc).unwrap();
        let mut scratch = graph.scratch(-32, 32, 4, 8).unwrap();
        for record in doc["reference_samples"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|v| v["seed"].as_i64() == Some(seed))
        {
            let root = record["root"].as_str().unwrap();
            let p = std::array::from_fn(|i| record["p"][i].as_i64().unwrap() as i32);
            let expected = record["bits"].as_u64().unwrap() as u32;
            let actual =
                graph.compute(graph.root(root).unwrap(), p, Mode::Single, &mut scratch) as f32;
            assert_eq!(
                expected,
                actual.to_bits(),
                "scalar {root} seed={seed} {p:?}"
            );
            checked += 1;
        }
        for record in doc["reference_volumes"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|v| v["seed"].as_i64() == Some(seed))
        {
            let root = record["root"].as_str().unwrap();
            let origin = std::array::from_fn(|i| record["origin"][i].as_i64().unwrap() as i32);
            let size = std::array::from_fn(|i| record["size"][i].as_i64().unwrap() as i32);
            let step = std::array::from_fn(|i| record["step"][i].as_i64().unwrap() as i32);
            scratch.set_modern_volume(ModernVolume { origin, size, step });
            let mut index = 0;
            for z in 0..size[2] {
                for x in 0..size[0] {
                    for y in 0..size[1] {
                        let p = [
                            origin[0] + x * step[0],
                            origin[1] + y * step[1],
                            origin[2] + z * step[2],
                        ];
                        let expected = record["bits"][index].as_u64().unwrap() as u32;
                        let actual =
                            graph.compute(graph.root(root).unwrap(), p, Mode::Block, &mut scratch)
                                as f32;
                        assert_eq!(
                            expected,
                            actual.to_bits(),
                            "volume {root} seed={seed} {p:?} index={index}"
                        );
                        checked += 1;
                        index += 1;
                    }
                }
            }
        }
    }
    assert_eq!(15824, checked);
}
