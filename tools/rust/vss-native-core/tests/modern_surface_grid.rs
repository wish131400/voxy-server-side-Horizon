use serde_json::{json, Value};
use vss_native_core::{density::Mode, terrain::Terrain};

#[test]
fn modern_surface_search_matches_descending_blocks_across_distinct_grids() {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/worldgen263");
    let end: Value = serde_json::from_slice(&std::fs::read(root.join("end.json")).unwrap()).unwrap();
    let gradient = json!({"type":"minecraft:gradient","axis":"y",
        "from_coordinate":0,"to_coordinate":128,"from_value":0.,"to_value":128.});
    let thin = json!({"type":"minecraft:range_choice","input":gradient,
        "min_inclusive":102.,"max_exclusive":105.,"when_in_range":1.,"when_out_of_range":-1.});
    let nested = json!({"type":"minecraft:interpolated","cell_size_xz":6,"cell_size_y":10,
        "input":{"type":"minecraft:interpolated","cell_size_xz":4,"cell_size_y":3,"input":thin}});
    for seed in [0, -917] {
        for density in [None, Some(thin.clone()), Some(nested.clone())] {
            let mut doc = end.clone();
            if let Some(density) = density { doc["settings"]["noise_router"]["final_density"] = density; }
            let t = Terrain::from_document(seed, &doc).unwrap();
            let id = t.graph.root("final_density").unwrap();
            for (cx,cz) in [(-32,-16),(0,0),(16,-16),(100000,100000),(-100016,-100000)] {
                // Production workspaces are scoped to one 16x16 volume.
                let mut fast = t.job(cx,cz,false).unwrap();
                for (dx,dz) in [(0,0),(3,3),(4,4),(7,7),(8,8),(15,15),(0,0)] {
                    let (x,z) = (cx+dx,cz+dz);
                    let mut oracle = t.job(x,z,false).unwrap();
                    let expected = (t.min_y..t.min_y+t.height).rev().find(|&y| {
                        oracle.scratch.advance_block();
                        t.graph.compute(id,[x,y,z],Mode::Cell,&mut oracle.scratch)>0.
                    });
                    assert_eq!(fast.density_surface(x,z), expected, "seed={seed} at {x},{z}");
                }
            }
        }
    }
}
