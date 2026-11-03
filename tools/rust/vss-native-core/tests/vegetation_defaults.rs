//! Optional fields omitted by the modern game codec retain their vanilla defaults.
use serde_json::{json, Value};
use std::{fs, path::Path};
use vss_native_core::{
    blocks::{Palette, Volume},
    random::Random,
    vegetation::{Feature, Placed, PlacementContext},
};

fn document() -> Value {
    serde_json::from_str(
        &fs::read_to_string(
            Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen263/features.json"),
        )
        .unwrap(),
    )
    .unwrap()
}

#[test]
fn omitted_spring_counts_match_explicit_defaults_and_place_the_source() {
    let doc = document();
    let implicit = doc["configured_features"]["minecraft:spring_water"].clone();
    assert!(implicit.get("rock_count").is_none());
    assert!(implicit.get("hole_count").is_none());
    let mut explicit = implicit.clone();
    explicit["rock_count"] = json!(4);
    explicit["hole_count"] = json!(1);
    for blocked in [false, true] {
        let mut palette = Palette::from_json(&doc["block_definitions"]).unwrap();
        let stone = palette.named("minecraft:stone").unwrap();
        let water = palette.named("minecraft:water").unwrap();
        let a = Feature::compile(&implicit, &doc, &mut palette).unwrap();
        let b = Feature::compile(&explicit, &doc, &mut palette).unwrap();
        let mut left = Volume::new([-2, 62, -2], [5, 5, 5], palette.clone()).unwrap();
        let mut right = Volume::new([-2, 62, -2], [5, 5, 5], palette).unwrap();
        for p in [[0, 65, 0], [0, 63, 0], [-1, 64, 0], [1, 64, 0], [0, 64, -1]] {
            left.set(p, stone);
            right.set(p, stone);
        }
        if blocked {
            left.set([0, 64, 1], stone);
            right.set([0, 64, 1], stone);
        }
        let mut ar = Random::new(731, 3);
        let mut br = ar.clone();
        let mut allow = |_: &str, _| true;
        let mut context = PlacementContext::new("minecraft:spring_water", &mut allow);
        let actual = a
            .place_transaction(&mut left, &mut ar, [0, 64, 0], &mut context)
            .unwrap();
        let expected = b
            .place_transaction(&mut right, &mut br, [0, 64, 0], &mut context)
            .unwrap();
        assert_eq!(actual, !blocked);
        assert_eq!(actual, expected);
        assert_eq!(left.blocks, right.blocks);
        assert_eq!(ar.next_long(), br.next_long());
        if !blocked {
            assert_eq!(left.get([0, 64, 0]), water);
        }
    }
    let mut invalid = implicit;
    invalid["rock_count"] = json!("invalid");
    assert!(Feature::compile(
        &invalid,
        &doc,
        &mut Palette::from_json(&doc["block_definitions"]).unwrap()
    )
    .is_err());
}

#[test]
fn omitted_noise_offset_keeps_candidate_random_continuation() {
    let doc = document();
    let mut implicit = json!({"feature":{"type":"minecraft:no_op"},"placement":[
        {"type":"minecraft:noise_based_count","noise_to_count_ratio":20,"noise_factor":80.},
        {"type":"minecraft:in_square"}]});
    let mut palette = Palette::from_json(&doc["block_definitions"]).unwrap();
    let a = Placed::compile(&implicit, &doc, &mut palette).unwrap();
    implicit["placement"][0]["noise_offset"] = json!(0.);
    let b = Placed::compile(&implicit, &doc, &mut palette).unwrap();
    for seed in 0..32 {
        for pos in [[0, 64, 0], [-1337, 64, 379], [16384, 64, -8192]] {
            let mut world = Volume::new([0, 64, 0], [1, 1, 1], palette.clone()).unwrap();
            let mut ar = Random::new(seed, 3);
            let mut br = ar.clone();
            let mut allow = |_: &str, _| true;
            let mut context = PlacementContext::new("test:default_noise", &mut allow);
            assert_eq!(
                a.place_transaction(&mut world, &mut ar, pos, &mut context)
                    .unwrap(),
                b.place_transaction(&mut world, &mut br, pos, &mut context)
                    .unwrap()
            );
            assert_eq!(ar.next_long(), br.next_long());
        }
    }
    implicit["placement"][0]["noise_offset"] = json!("invalid");
    assert!(Placed::compile(&implicit, &doc, &mut palette).is_err());
}


#[test]
fn bush_requires_the_vanilla_vegetation_support_tag() {
    let mut doc = document();
    // Use an explicit palette so this assertion cannot depend on fixture tag changes.
    let definition = |tags: Value| json!({"defaults":{},"properties":{},"tags":tags,
        "air":false,"fluid":false,"motion_blocking":false,"double_plant":false});
    doc["block_definitions"]["minecraft:bush"] = definition(json!([]));
    doc["block_definitions"]["test:vegetation_soil"] = definition(json!(["minecraft:supports_vegetation"]));
    doc["block_definitions"]["test:unsupported_soil"] = definition(json!([]));
    for (name, expected) in [("test:vegetation_soil",true),("test:unsupported_soil",false)] {
        let mut palette = Palette::from_json(&doc["block_definitions"]).unwrap();
        let soil = palette.named(name).unwrap();
        let bush = palette.named("minecraft:bush").unwrap();
        let feature = Feature::compile(&json!({"type":"minecraft:simple_block","to_place":{
            "type":"minecraft:simple_state_provider","state":{"Name":"minecraft:bush"}}}),
            &doc,&mut palette).unwrap();
        let mut volume = Volume::new([-1,63,-1],[3,3,3],palette).unwrap();
        volume.set([0,63,0],soil);
        let mut random = Random::new(37,3);
        let mut expected_random = random.clone();
        let mut allow = |_: &str, _| true;
        let mut context = PlacementContext::new("test:bush",&mut allow);
        assert_eq!(expected,feature.place_transaction(&mut volume,&mut random,[0,64,0],&mut context).unwrap());
        assert_eq!(volume.get([0,64,0]) == bush,expected);
        assert_eq!(expected_random.next_long(),random.next_long());
    }
}
