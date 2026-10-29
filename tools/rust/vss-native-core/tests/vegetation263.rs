//! Golden results emitted by the actual Minecraft 26.3 implementations.
use serde_json::Value;
use std::{fs, path::Path};
use vss_native_core::{
    blocks::{Palette, Volume},
    providers::StateProvider,
    random::Random,
    vegetation::{Feature, PlacementContext},
};
fn read(name: &str) -> Value {
    serde_json::from_str(&fs::read_to_string(root().join(name)).unwrap()).unwrap()
}
fn root() -> std::path::PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen263")
}
fn rows(name: &str) -> Vec<Value> {
    fs::read_to_string(root().join(name))
        .unwrap()
        .lines()
        .map(|row| serde_json::from_str(row).unwrap())
        .collect()
}
#[test]
fn actual_modern_provider_states_nulls_and_random_continuation() {
    let doc = read("features.json");
    let states = read("vegetation-states.json");
    let mut palette = Palette::from_json(&doc["block_definitions"]).unwrap();
    let ids: Vec<_> = states
        .as_array()
        .unwrap()
        .iter()
        .map(|v| palette.intern(v).unwrap())
        .collect();
    let mut count = 0;
    for row in rows("providers.jsonl") {
        let provider =
            StateProvider::parse_with_registries(&row["provider"], &doc, &mut palette).unwrap();
        let seed: i64 = row["seed"].as_str().unwrap().parse().unwrap();
        let mut world = Volume::new([0, 64, 0], [1, 1, 1], palette.clone()).unwrap();
        world.set([0, 64, 0], ids[row["input"].as_u64().unwrap() as usize]);
        let mut random = Random::new(seed, 3);
        let optional = provider
            .optional_state(&mut random, [0, 64, 0], &mut world)
            .unwrap();
        assert_eq!(
            optional,
            row["optional"].as_u64().map(|v| ids[v as usize]),
            "{} optional seed={seed}",
            row["name"]
        );
        assert_eq!(
            random.next_long().to_string(),
            row["after_optional"].as_str().unwrap(),
            "{} optional random seed={seed}",
            row["name"]
        );
        let mut random = Random::new(seed, 3);
        assert_eq!(
            provider
                .state_in(&mut random, [0, 64, 0], &mut world)
                .unwrap(),
            ids[row["state"].as_u64().unwrap() as usize],
            "{} getState seed={seed}",
            row["name"]
        );
        assert_eq!(
            random.next_long().to_string(),
            row["after_state"].as_str().unwrap(),
            "{} getState random seed={seed}",
            row["name"]
        );
        count += 1;
    }
    assert_eq!(count, 960);
}
#[test]
fn actual_modern_nether_features_match_voxels_and_random_continuation() {
    feature_golden("vegetation.jsonl", "vegetation-states.json", 176);
}
#[test]
fn actual_modern_feature_wrappers_match_voxels_and_random_continuation() {
    feature_golden("modern.jsonl", "vegetation-states.json", 496);
}
#[test]
fn actual_modern_trees_and_mushrooms_match_voxels_and_random_continuation() {
    feature_golden("trees263.jsonl", "trees-states.json", 80);
}
fn feature_golden(input: &str, state_file: &str, expected_count: usize) {
    let doc = read("features.json");
    let states = read(state_file);
    let mut failures = Vec::new();
    let mut count = 0;
    for row in rows(input) {
        let name = row["feature"].as_str().unwrap();
        let seed: i64 = row["seed"].as_str().unwrap().parse().unwrap();
        let label = format!("{name} seed={seed} pattern={}", row["pattern"]);
        let mut palette = Palette::from_json(&doc["block_definitions"]).unwrap();
        let ids: Vec<_> = states
            .as_array()
            .unwrap()
            .iter()
            .map(|v| palette.intern(v).unwrap())
            .collect();
        let feature = match Feature::compile(&row["feature"], &doc, &mut palette) {
            Ok(feature) => feature,
            Err(error) => {
                failures.push(format!("{label}: {error}"));
                continue;
            }
        };
        let origin: [i32; 3] = serde_json::from_value(row["origin"].clone()).unwrap();
        let min: [i32; 3] = serde_json::from_value(row["volume_min"].clone()).unwrap();
        let size: [usize; 3] = serde_json::from_value(row["volume_size"].clone()).unwrap();
        let ground = palette.named(row["ground"].as_str().unwrap()).unwrap();
        let rock = palette.named("minecraft:netherrack").unwrap();
        let surface_y = row["surface_y"].as_i64().unwrap_or(55) as i32;
        let ceiling_y = row["ceiling_y"].as_i64().unwrap_or(80) as i32;
        let mut world = Volume::new(min, size, palette).unwrap();
        for x in min[0]..min[0] + size[0] as i32 {
            for z in min[2]..min[2] + size[2] as i32 {
                for y in min[1]..min[1] + size[1] as i32 {
                    if y <= surface_y || y >= ceiling_y {
                        world.set([x, y, z], if y == surface_y { ground } else { rock });
                    }
                }
            }
        }
        for value in row["input"].as_array().unwrap() {
            world.set(
                std::array::from_fn(|i| value[i].as_i64().unwrap() as i32),
                ids[value[3].as_u64().unwrap() as usize],
            );
        }
        let mut expected = world.blocks.clone();
        for value in row["output"].as_array().unwrap() {
            let x = value[0].as_i64().unwrap() as i32;
            let y = value[1].as_i64().unwrap() as i32;
            let z = value[2].as_i64().unwrap() as i32;
            expected[((x - min[0]) as usize * size[2] + (z - min[2]) as usize) * size[1]
                + (y - min[1]) as usize] = ids[value[3].as_u64().unwrap() as usize];
        }
        let mut random = Random::new(seed, 3);
        let mut allow = |_: &str, _| true;
        let mut context = PlacementContext::new(name, &mut allow);
        let result = match feature.place_transaction(&mut world, &mut random, origin, &mut context)
        {
            Ok(result) => result,
            Err(error) => {
                failures.push(format!("{label}: {error}"));
                continue;
            }
        };
        let after = random.next_long();
        if result != row["placed"].as_bool().unwrap()
            || after.to_string() != row["after"].as_str().unwrap()
        {
            failures.push(format!(
                "{label} placed={result} random={after} expected placed={} random={}",
                row["placed"], row["after"]
            ));
        }
        if let Some((i, (actual, expected))) = world
            .blocks
            .iter()
            .zip(expected.iter())
            .enumerate()
            .find(|(_, (a, b))| a != b)
        {
            failures.push(format!(
                "{label} first voxel ({},{},{}) {:?} expected {:?}",
                i as i32 / (size[1] * size[2]) as i32 + min[0],
                i as i32 % size[1] as i32 + min[1],
                (i / size[1] % size[2]) as i32 + min[2],
                world.palette.state(*actual),
                world.palette.state(*expected)
            ));
        }
        count += 1;
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
    assert_eq!(count, expected_count);
}
