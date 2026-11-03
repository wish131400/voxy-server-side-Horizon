//! Cold 26.3 height and surface scans against the released vanilla document.
//! Does not measure Java scheduling, decoration, rendering or frame time.
use std::time::Instant;
use vss_native_core::{
    density::{Graph, Mode},
    terrain::Terrain,
};

fn main() {
    let doc =
        serde_json::from_str(include_str!("../tests/fixtures/worldgen263/overworld.json")).unwrap();
    let terrain = Terrain::from_document(0, &doc).unwrap();
    let count: i32 = std::env::args()
        .nth(1)
        .unwrap_or("512".into())
        .parse()
        .unwrap();
    for preliminary in [true, false] {
        let mut hash = 0xcbf29ce484222325u64;
        let start = Instant::now();
        for i in 0..count {
            // Independent jobs and distant columns keep the run cold.
            let (x, z) = (-150_000 + (i % 32) * 257, 200_000 + (i / 32) * 251);
            let mut job = terrain.job(x, z, true).unwrap();
            let value = if preliminary {
                job.preliminary(x, z)
            } else {
                job.surface_top(x, z).map_or(i32::MIN, |v| v.0)
            };
            hash = (hash ^ value as u32 as u64).wrapping_mul(0x100000001b3);
        }
        println!(
            "{} columns={count} total_ms={:.2} checksum={hash:016x}",
            if preliminary {
                "preliminary"
            } else {
                "surface"
            },
            start.elapsed().as_secs_f64() * 1000.
        );
    }
    // The modded-graph case: a safe but loose upper bound leaves a long empty
    // vertical span. Compare the old descending loop with the new exact search.
    let mut top_doc: serde_json::Value = doc;
    top_doc["density_functions"]["vss:bench_density"] = serde_json::json!({"type":"minecraft:add",
        "left":{"type":"minecraft:gradient","axis":"y","from_coordinate":-64,"to_coordinate":320,
            "from_value":128.,"to_value":-256.},
        "right":{"type":"minecraft:noise","noise":"minecraft:temperature","xz_scale":0.1,"y_scale":0.1}});
    top_doc["settings"]["noise_router"]["test_density"] = "vss:bench_density".into();
    top_doc["settings"]["noise_router"]["test_top"] = serde_json::json!({"type":"minecraft:find_top_surface",
        "density":"vss:bench_density","upper_bound":320.,"lower_bound":-64,"cell_height":4});
    let graph = Graph::from_document(0, &top_doc).unwrap();
    let density = graph.root("test_density").unwrap();
    let top = graph.root("test_top").unwrap();
    for intervals in [false, true] {
        let mut hash = 0xcbf29ce484222325u64;
        let start = Instant::now();
        for i in 0..count {
            let (x, z) = (-150_000 + (i % 32) * 257, 200_000 + (i / 32) * 251);
            let mut scratch = graph.scratch(x, z, 4, 8).unwrap();
            let value = if intervals {
                graph.compute(top, [x, 0, z], Mode::Single, &mut scratch) as i32
            } else {
                (0..=96)
                    .map(|i| 320 - i * 4)
                    .find(|y| graph.compute(density, [x, *y, z], Mode::Single, &mut scratch) > 0.)
                    .unwrap_or(-64)
            };
            hash = (hash ^ value as u32 as u64).wrapping_mul(0x100000001b3);
        }
        println!(
            "loose_top_{} columns={count} total_ms={:.2} checksum={hash:016x}",
            if intervals { "intervals" } else { "linear" },
            start.elapsed().as_secs_f64() * 1000.
        );
    }
}
