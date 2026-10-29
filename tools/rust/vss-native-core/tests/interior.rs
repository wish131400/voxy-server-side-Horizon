use serde_json::{json, Value};
use std::{
    fs,
    path::Path,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    },
};
use vss_native_core::{
    backend::World,
    blocks::{Palette, Volume},
};

fn document() -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |name: &str| {
        serde_json::from_str::<Value>(&fs::read_to_string(root.join(name)).unwrap()).unwrap()
    };
    let mut doc = read("nether.json");
    for (key, file) in [
        ("block_definitions", "blocks.json"),
        ("biomes", "biomes.json"),
        ("grass_colormap", "grass.json"),
        ("foliage_colormap", "foliage.json"),
    ] {
        doc[key] = read(file);
    }
    doc
}

#[test]
fn complete_material_columns_match_full_surface_pass_in_all_nether_biomes() {
    for biome in [
        "crimson_forest",
        "warped_forest",
        "soul_sand_valley",
        "basalt_deltas",
        "nether_wastes",
    ] {
        let mut doc = document();
        doc["biome_source"] =
            json!({"type":"minecraft:fixed","biome":format!("minecraft:{biome}")});
        let world = Arc::new(World::new(42, 0, doc).unwrap());
        let mut dense = world.surface_region(-1, 0, 1).unwrap();
        let mut proxy = world.interior_proxy(-1, 0).unwrap();
        let mut caves = 0;
        for x in -16..0 {
            for z in 0..16 {
                let column = world.interior_column(x, z).unwrap();
                assert!(Arc::ptr_eq(&column, &world.interior_column(x, z).unwrap()));
                for y in 0..128 {
                    let actual = column[y as usize];
                    assert_eq!(actual, dense.get([x, y, z]), "{biome} {x},{y},{z}");
                    assert_eq!(actual, proxy.get([x, y, z]));
                    if y > 32 && y < 100 && world.palette.is_air(actual) {
                        caves += 1;
                    }
                }
                for kind in 0..4 {
                    assert_eq!(proxy.heightmap(x, z, kind), dense.heightmap(x, z, kind));
                }
            }
        }
        assert!(caves > 0);
    }
}

#[test]
fn interior_proxy_reads_only_touched_columns_and_rolls_back_cave_edits() {
    let mut palette = Palette::from_json(&document()["block_definitions"]).unwrap();
    let rock = palette.named("minecraft:netherrack").unwrap();
    let air = palette.air;
    let count = Arc::new(AtomicUsize::new(0));
    let calls = Arc::clone(&count);
    let mut volume = Volume::interior_proxy(
        [-16, 0, -16],
        32,
        32,
        128,
        palette,
        Box::new(move |_, _| {
            calls.fetch_add(1, Ordering::Relaxed);
            Ok(Arc::new(
                (0..128)
                    .map(|y| if y < 40 || y >= 90 { rock } else { air })
                    .collect(),
            ))
        }),
    )
    .unwrap();
    assert!(volume.blocks.is_empty());
    assert_eq!(count.load(Ordering::Relaxed), 0);
    assert_eq!(volume.get([-1, 50, -1]), air);
    assert_eq!(volume.get([-1, 95, -1]), rock);
    assert_eq!(count.load(Ordering::Relaxed), 1);
    volume.begin().unwrap();
    volume.set([-1, 50, -1], rock);
    volume.set([-1, 95, -1], air);
    volume.finish(false).unwrap();
    assert_eq!(volume.get([-1, 50, -1]), air);
    assert_eq!(volume.get([-1, 95, -1]), rock);
    assert!(volume.published.is_empty());
    let mut failed = Volume::interior_proxy(
        [0, 0, 0],
        16,
        16,
        128,
        volume.palette.clone(),
        Box::new(|_, _| Err("missing".into())),
    )
    .unwrap();
    failed.begin().unwrap();
    failed.set([0, 50, 0], rock);
    failed.get([1, 50, 0]);
    assert!(failed.finish(true).is_err());
    assert!(failed.published.is_empty());
}

#[test]
fn dimension_write_height_is_independent_of_noise_height() {
    let mut doc=document();doc["vss_dimension_bounds"]=json!({"min_y":-16,"height":288});
    let world=Arc::new(World::new(42,0,doc).unwrap());
    let rock=world.palette.clone().named("minecraft:netherrack").unwrap();let air=world.palette.air;
    let base=world.interior_column(1,1).unwrap();assert_eq!(base.len(),128);
    let mut volume=world.interior_proxy(0,0).unwrap();
    for y in 0..128 {assert_eq!(base[y as usize],volume.get([1,y,1]));}
    assert_eq!(air,volume.get([1,-1,1]));assert_eq!(air,volume.get([1,160,1]));
    volume.begin().unwrap();volume.set([1,160,1],rock);volume.finish(true).unwrap();
    assert_eq!(rock,volume.get([1,160,1]));assert!(!volume.incomplete);
    volume.begin().unwrap();volume.set([1,161,1],rock);volume.finish(false).unwrap();
    assert_eq!(air,volume.get([1,161,1]));
    let mut surface=world.decoration_proxy(0,0,true).unwrap();
    assert_eq!(air,surface.get([1,-1,1]));assert_eq!(air,surface.get([1,160,1]));
    surface.set([1,160,1],rock);assert_eq!(rock,surface.get([1,160,1]));
}

#[test]
fn placement_anchors_follow_generator_context_without_clipping_dimension_edits() {
    use vss_native_core::{random::Random, vegetation::{Placed, PlacementContext}};
    // WorldGenerationContext uses max(minY) and min(height), rather than
    // either the noise-column range or the dimension's full build range.
    for (dimension_min, dimension_height, gen_min, gen_height) in
        [(-16,288,0,128), (32,64,32,64)] {
        let mut doc=document();
        // Legacy golden metadata predates the unconditional survival field;
        // stone has no contextual canSurvive override in the target game.
        doc["block_definitions"]["minecraft:stone"]["survival"]=json!("none");
        doc["vss_dimension_bounds"]=json!({"min_y":dimension_min,"height":dimension_height});
        let world=Arc::new(World::new(42,0,doc).unwrap());
        let mut volume=world.interior_proxy(0,0).unwrap();
        assert_eq!(volume.generation_bounds,(gen_min,gen_height));
        assert_eq!(volume.generator_depth,128);
        assert_eq!(world.decoration_proxy(0,0,false).unwrap().generation_bounds,(gen_min,gen_height));
        assert_eq!(world.lazy_surface_proxy(0,0).unwrap().generation_bounds,(gen_min,gen_height));
        for (anchor, expected) in [
            (json!({"above_bottom":4}), gen_min+4),
            (json!({"below_top":4}), gen_min+gen_height-5),
            (json!({"absolute":dimension_min+dimension_height-8}),dimension_min+dimension_height-8),
        ] {
            let placed=Placed::compile(&json!({
                "feature":{"type":"minecraft:simple_block","config":{
                    "to_place":{"type":"minecraft:simple_state_provider","state":{"Name":"minecraft:stone"}}}},
                "placement":[{"type":"minecraft:height_range","height":{"type":"minecraft:constant","value":anchor}}]
            }),&world.document,&mut volume.palette).unwrap();
            let mut allow=|_: &str,_|true;
            let mut context=PlacementContext::new("anchor-test",&mut allow);
            placed.place_transaction(&mut volume,&mut Random::new(42,3),[0,0,0],&mut context).unwrap();
            let state=volume.get([0,expected,0]);
            assert!(volume.palette.is(state,"minecraft:stone"),"anchor={anchor} expected Y={expected}");
        }
        assert!(!volume.incomplete);
    }
}

#[test]
fn huge_fungus_uses_raw_generator_depth_and_preserves_rejected_random_continuation() {
    use vss_native_core::{random::Random, vegetation::{Feature, PlacementContext}};
    let mut doc=document();doc["vss_dimension_bounds"]=json!({"min_y":-16,"height":288});
    let world=Arc::new(World::new(42,0,doc).unwrap());
    let mut volume=world.interior_proxy(0,0).unwrap();
    let feature=Feature::compile(&json!({"type":"minecraft:huge_fungus","config":{
        "valid_base_block":{"Name":"minecraft:crimson_nylium"},
        "stem_state":{"Name":"minecraft:crimson_stem"},
        "hat_state":{"Name":"minecraft:nether_wart_block"},
        "decor_state":{"Name":"minecraft:shroomlight"},
        "planted":false,
        "replaceable_blocks":{"type":"minecraft:matching_blocks","blocks":["minecraft:air"]}
    }}),&world.document,&mut volume.palette).unwrap();
    let base=volume.palette.named("minecraft:crimson_nylium").unwrap();
    volume.set([0,124,0],base);
    for seed in 0..32 {
        let mut random=Random::new(seed,3);let mut oracle=random.clone();
        oracle.next_bounded(10);oracle.next_bounded(12);
        let mut allow=|_: &str,_|true;let mut context=PlacementContext::new("fungus-ceiling",&mut allow);
        assert!(!feature.place_transaction(&mut volume,&mut random,[0,125,0],&mut context).unwrap());
        assert_eq!(random.next_long(),oracle.next_long(),"seed={seed}");
    }
}

#[test]
fn nether_uniform_anchor_preserves_height_and_random_continuation_in_a_taller_dimension() {
    use vss_native_core::{random::Random, vegetation::{Placed, PlacementContext}};
    let mut doc=document();doc["vss_dimension_bounds"]=json!({"min_y":0,"height":256});
    doc["block_definitions"]["minecraft:stone"]["survival"]=json!("none");
    let world=Arc::new(World::new(42,0,doc).unwrap());
    let mut volume=world.interior_proxy(0,0).unwrap();
    let placed=Placed::compile(&json!({
        "feature":{"type":"minecraft:simple_block","config":{
            "to_place":{"type":"minecraft:simple_state_provider","state":{"Name":"minecraft:stone"}}}},
        "placement":[{"type":"minecraft:height_range","height":{"type":"minecraft:uniform",
            "min_inclusive":{"above_bottom":0},"max_inclusive":{"below_top":0}}}]
    }),&world.document,&mut volume.palette).unwrap();
    for seed in 0..64 {
        let mut random=Random::new(seed,3);let mut oracle=random.clone();
        let y=oracle.next_bounded(128);
        let mut allow=|_: &str,_|true;let mut context=PlacementContext::new("glowstone-height",&mut allow);
        placed.place_transaction(&mut volume,&mut random,[0,0,0],&mut context).unwrap();
        let state=volume.get([0,y,0]);assert!(volume.palette.is(state,"minecraft:stone"));
        assert_eq!(random.next_long(),oracle.next_long(),"seed={seed}");
    }
}
