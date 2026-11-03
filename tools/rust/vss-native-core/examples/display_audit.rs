//! Reproducible display-path stage audit against an exported worldgen document.
//! Run each case serially. Profiling adds overhead; use an uninstrumented JNI
//! benchmark for production throughput and compare checksums before interpreting it.
use serde_json::json;
use std::{fs, hint::black_box, time::Instant};
use vss_native_core::{backend::World, prof};

fn query(world: &World, step: i32, batches: i32) -> u64 {
    let mut checksum = 0u64;
    for batch in 0..batches {
        let points: Vec<_> = (0..64)
            .map(|i| (100000 + batch * 4096 + i % 8 * step, 100000 + i / 8 * step))
            .collect();
        for row in world.display_points(&points).unwrap() {
            for v in row.values {
                checksum = checksum.wrapping_mul(31).wrapping_add(v as u64);
            }
        }
    }
    black_box(checksum)
}

fn main() {
    let args: Vec<_> = std::env::args().collect();
    let doc: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(&args[1]).unwrap()).unwrap();
    let rounds: usize = args.get(2).map_or(5, |s| s.parse().unwrap());
    let steps: Vec<i32> = args
        .get(3)
        .map_or("1,4,16,64", String::as_str)
        .split(',')
        .map(|s| s.parse().unwrap())
        .collect();
    let batches: i32 = args.get(4).map_or(16, |s| s.parse().unwrap());
    assert!(batches > 0 && steps.iter().all(|step| *step > 0));
    let warm = World::new(0, 0, doc.clone()).unwrap();
    query(&warm, 4, batches);
    drop(warm);
    for round in 0..rounds {
        for index in 0..steps.len() {
            let step = steps[if round % 2 == 0 {
                index
            } else {
                steps.len() - 1 - index
            }];
            let world = World::new(0, 0, doc.clone()).unwrap();
            prof::reset();
            let start = Instant::now();
            let checksum = query(&world, step, batches);
            let wall_ms = start.elapsed().as_secs_f64() * 1000.;
            let stages: serde_json::Map<_, _> = prof::display_snapshot()
                .into_iter()
                .map(|(name, ns)| (name.to_string(), json!(ns as f64 / 1e6)))
                .collect();
            let counts = json!({"evals": prof::evals(), "noise": prof::noise_evals(),
                "blocks": prof::block_calls(), "fluids": prof::fluid_calls(),
                "preliminary": prof::preliminary_calls(), "tops": prof::surface_top_counts()});
            let queries = world.display_query_stats();
            assert_eq!(
                checksum,
                query(&world, step, batches),
                "cold/cache output differs"
            );
            println!(
                "{}",
                json!({"round": round, "step": step, "columns": batches * 64,
                "wallMs": wall_ms, "checksum": checksum.to_string(), "stagesMs": stages,
                "counts": counts, "queries": queries})
            );
        }
    }
}
