use super::*;

#[test]
fn top_cache_eviction_keeps_new_answers_and_exact_records() {
    let doc = exterior_tests::document();
    let mut world = World::new(42,0,doc.clone()).unwrap();
    world.neighbour_tops = Mutex::new(crate::terrain::TopCache::new(8));
    let oracle = World::new(42,0,doc).unwrap();
    for (x,z) in [(0,0),(-512,512),(512,-512),(0,0),(-16,-16)] {
        assert_eq!(world.surface_point(x,z).unwrap().values,oracle.surface_point(x,z).unwrap().values);
    }
    let (x,z)=(4096,-4096);
    let mut job=world.terrain.job(x,z,false).unwrap();
    world.attach_tops(&mut job);
    let expected=job.surface_top(x,z);
    assert_eq!(world.neighbour_tops.lock().unwrap().get(&(x,z)),Some(&expected));
    let mut second=world.terrain.job(x,z,false).unwrap();
    world.attach_tops(&mut second);
    assert_eq!(second.surface_top(x,z),expected);
}

#[test]
fn decoration_cache_hits_follow_the_requested_view() {
    let mut doc = exterior_tests::document();
    doc["settings"]["aquifers_enabled"] = json!(false);
    doc["settings"]["ore_veins_enabled"] = json!(false);
    doc["settings"]["noise_router"]["final_density"] = json!({
        "type":"minecraft:y_clamped_gradient", "from_y":0, "to_y":128,
        "from_value":1.0, "to_value":-1.0
    });
    doc["settings"]["surface_rule"] = json!({
        "type":"minecraft:block", "result_state":{"Name":"minecraft:grass_block"}
    });
    let points = [(-4, -4), (3, 5)];
    let count = points.len() as u64;

    // Exact warmth must not count as a display hit or bypass visual generation.
    let exact_warm = World::new(42, 0, doc.clone()).unwrap();
    let exact = exact_warm.surface_points(&points).unwrap();
    let before = exact_warm.display_query_stats();
    let visual = exact_warm.decoration_points(&points, true).unwrap();
    assert_eq!(exact_warm.decoration_query_stats()[2], 0);
    let after = exact_warm.display_query_stats();
    assert_eq!(after[0] - before[0], count);
    assert_eq!(after[1] - before[1], 0);
    assert_eq!(after[3] - before[3], 0, "fixture must enter the display path");
    assert_eq!(after[5] - before[5], count);
    assert!(visual.iter().all(|r| r.values[3] & (1 << 26) != 0));
    let repeated = exact_warm.decoration_points(&points, true).unwrap();
    assert_eq!(exact_warm.decoration_query_stats()[2], count);
    assert_eq!(exact_warm.display_query_stats()[5], after[5]);
    assert_eq!(repeated.iter().map(|r| r.values).collect::<Vec<_>>(),
        visual.iter().map(|r| r.values).collect::<Vec<_>>());

    // Display warmth must not count as an exact hit or bypass exact generation.
    let visual_warm = World::new(42, 0, doc).unwrap();
    visual_warm.display_points(&points).unwrap();
    assert!(points.iter().all(|&(x,z)| visual_warm.cached_surface_point(x,z).unwrap().is_none()));
    let before = visual_warm.work_counts();
    let generated = visual_warm.decoration_points(&points, false).unwrap();
    assert_eq!(visual_warm.decoration_query_stats()[2], 0);
    assert_eq!(visual_warm.work_counts()[1] - before[1], count);
    assert_eq!(generated.iter().map(|r| r.values).collect::<Vec<_>>(),
        exact.iter().map(|r| r.values).collect::<Vec<_>>());
    let before = visual_warm.work_counts();
    visual_warm.decoration_points(&points, false).unwrap();
    assert_eq!(visual_warm.decoration_query_stats()[2], count);
    assert_eq!(visual_warm.work_counts(), before);
}
