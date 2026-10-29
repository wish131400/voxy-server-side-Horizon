//! Registry-backed material rules introduced by Minecraft 26.3.
//! The codec tree is resolved once; point evaluation stays entirely native.
use super::*;
use crate::density::{Id, Mode, Scratch};

pub(super) fn reference<'a>(value: &Value, doc: &'a Value, registry: &str) -> Result<Option<&'a Value>> {
    let Some(name) = value.as_str() else { return Ok(None); };
    let name = if name.contains(':') { name.to_owned() } else { format!("minecraft:{name}") };
    doc[registry].get(&name).map(Some)
        .ok_or_else(|| format!("missing {registry} reference {name}"))
}

pub(super) fn state(value: &Value, palette: &mut Palette) -> Result<StateId> {
    if let Some(name) = value.as_str() {
        let name = if name.contains(':') { name.to_owned() } else { format!("minecraft:{name}") };
        palette.named(&name)
    } else {
        palette.intern(value)
    }
}

/// Registry holder sets encode one direct holder as a string in 26.3.
/// Named tags require their actual resolved members; never treat a tag as an ID.
pub(super) fn biome_names(value: &Value, doc: &Value) -> Result<Vec<String>> {
    fn name(value: &Value) -> Result<String> {
        let name = value.as_str().ok_or("invalid biome holder")?;
        if name.starts_with('#') { return Err("tag inside direct biome holder set".into()); }
        Ok(if name.contains(':') { name.into() } else { format!("minecraft:{name}") })
    }
    match value {
        Value::String(tag) if tag.starts_with('#') => {
            let tag = &tag[1..];
            let tag = if tag.contains(':') { tag.to_owned() } else { format!("minecraft:{tag}") };
            doc["biome_tags"].get(&tag).and_then(Value::as_array)
                .ok_or_else(|| format!("missing biome tag members {tag}"))?
                .iter().map(name).collect()
        }
        Value::String(_) => Ok(vec![name(value)?]),
        Value::Array(values) => values.iter().map(name).collect(),
        _ => Err("invalid biome holder set".into()),
    }
}

pub(super) struct OreRule {
    density: Id,
    richness: Id,
    gap: Id,
    pub ore: StateId,
    pub raw: StateId,
    pub filler: StateId,
    raw_chance: f32,
    random: Positional,
}
impl OreRule {
    pub fn parse(value: &Value, graph: &mut Graph, doc: &Value, palette: &mut Palette) -> Result<Self> {
        let raw_chance = number(value, "raw_ore_chance")? as f32;
        if !(0.0..=1.0).contains(&raw_chance) { return Err("invalid raw ore chance".into()); }
        let prefix = format!("vss:material/{}", graph.roots.len());
        Ok(Self {
            density: graph.add_root(&format!("{prefix}/density"), &value["density"], doc)?,
            richness: graph.add_root(&format!("{prefix}/richness"), &value["richness"], doc)?,
            gap: graph.add_root(&format!("{prefix}/filler_gap"), &value["filler_gap"], doc)?,
            ore: state(&value["ore_block"], palette)?,
            raw: state(&value["raw_ore_block"], palette)?,
            filler: state(&value["filler_block"], palette)?,
            raw_chance,
            random: graph.random.from_hash("minecraft:ore").positional(),
        })
    }
    pub fn sample(&self, graph: &Graph, pos: Pos, scratch: &mut Scratch) -> Option<StateId> {
        // MaterialRuleContext pre-fills these two fields over the chunk volume;
        // the gap field remains a single-point sample. Float interpolation can
        // round differently between those two paths in 26.3.
        let density = graph.compute(self.density, pos, Mode::Block, scratch) as f32;
        if density <= 0.0 { return None; }
        let mut random = self.random.at(pos[0], pos[1], pos[2]);
        if random.next_float() > density { return None; }
        let richness = graph.compute(self.richness, pos, Mode::Block, scratch) as f32;
        if random.next_float() < richness && graph.compute(self.gap, pos, Mode::Single, scratch) < 0.0 {
            Some(if random.next_float() < self.raw_chance { self.raw } else { self.ore })
        } else {
            Some(self.filler)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn modern_biome_holder_sets_accept_real_singleton_codec_and_resolved_tags() {
        let doc = json!({"biome_tags":{"minecraft:is_forest":["minecraft:forest","minecraft:birch_forest"]}});
        assert_eq!(biome_names(&json!("minecraft:wooded_badlands"), &doc).unwrap(), vec!["minecraft:wooded_badlands"]);
        assert_eq!(biome_names(&json!(["minecraft:forest","birch_forest"]), &doc).unwrap(), vec!["minecraft:forest","minecraft:birch_forest"]);
        assert_eq!(biome_names(&json!("#is_forest"), &doc).unwrap(), vec!["minecraft:forest","minecraft:birch_forest"]);
        assert!(biome_names(&json!("#missing"), &doc).is_err());
        assert!(biome_names(&json!(["#is_forest"]), &doc).is_err());
    }

    fn document() -> Value {
        serde_json::from_str(include_str!("../../tests/fixtures/worldgen/overworld.json")).unwrap()
    }
    fn palette() -> Palette {
        Palette::from_json(&serde_json::from_str::<Value>(include_str!("../../tests/fixtures/worldgen/blocks.json")).unwrap()).unwrap()
    }
    fn ore(density: f64, richness: f64, gap: f64, raw: f64) -> Value {
        json!({"type":"minecraft:ore_vein", "density":density, "richness":richness,
            "filler_gap":gap,"raw_ore_chance":raw,"ore_block":"minecraft:copper_ore",
            "raw_ore_block":"minecraft:raw_copper_block","filler_block":"minecraft:granite"})
    }
    #[test]
    fn material_ore_uses_data_driven_density_gap_and_raw_probability() {
        let doc = document();
        let mut graph = Graph::from_document(731, &doc).unwrap();
        let mut palette = palette();
        // Unlike the former fixed copper/iron Y bands, 26.3 can place any
        // configured vein at any Y; this position is outside both old bands.
        let pos = [11, 237, -93];
        for (density, richness, gap, chance, expected) in [
            (0., 1., -1., 0., None),
            (1., 1., -1., 0., Some("minecraft:copper_ore")),
            (1., 1., -1., 1., Some("minecraft:raw_copper_block")),
            (1., 0., -1., 1., Some("minecraft:granite")),
            (1., 1., 0., 1., Some("minecraft:granite")),
        ] {
            let rule = OreRule::parse(&ore(density, richness, gap, chance), &mut graph, &doc, &mut palette).unwrap();
            let mut scratch = graph.scratch(0, -96, 4, 8).unwrap();
            assert_eq!(rule.sample(&graph, pos, &mut scratch).map(|id| palette.state(id).name.as_str()), expected);
        }
    }
    #[test]
    fn references_keep_namespace_and_fail_on_missing_entries() {
        let doc = json!({"material_rules":{"minecraft:a":{"type":"minecraft:block","result_state":"minecraft:stone"},
            "custom:a":{"type":"minecraft:sequence","sequence":[]}}});
        assert_eq!(reference(&json!("a"), &doc, "material_rules").unwrap().unwrap()["result_state"], "minecraft:stone");
        assert_eq!(reference(&json!("custom:a"), &doc, "material_rules").unwrap().unwrap()["type"], "minecraft:sequence");
        assert!(reference(&json!("custom:missing"), &doc, "material_rules").is_err());
    }

    #[test]
    fn registered_materials_apply_to_nondefault_solids_with_an_independent_surface_root() {
        let mut doc = document();
        doc["settings"]["material_rule"] = json!("custom:surface");
        doc["material_rules"] = json!({"custom:surface":{"type":"minecraft:condition",
            "if_true":"custom:all_y", "then_run":{"type":"minecraft:block","result_state":"minecraft:orange_terracotta"}}});
        doc["material_conditions"] = json!({"custom:all_y":{"type":"minecraft:y_above",
            "anchor":{"absolute":-1000},"surface_depth_multiplier":0,"add_stone_depth":false}});
        let mut terrain = Terrain::from_document(73, &doc).unwrap();
        terrain.graph.add_root("chunk_surface_level", &json!(100.9), &doc).unwrap();
        let mut palette = palette();
        let surface = Surface::new(&mut terrain, &doc, &mut palette).unwrap();
        let granite = palette.named("minecraft:granite").unwrap();
        let mut column = ExteriorColumn { bottom: 90, top: 106, blocks: vec![granite; 16] };
        let colors = ClimateColors::new(vec![0; 65536], vec![0; 65536]).unwrap();
        let biome = Biome { temperature: 0.8, downfall: 0.4, frozen: false,
            grass: None, foliage: None, water: 0, modifier: crate::biome::GrassModifier::None };
        let mut job = terrain.job(0, 0, false).unwrap();
        let depth = surface.depth(&terrain.graph, 0, 0);
        assert_eq!(surface.min_surface(&mut job, 0, 0, depth), 100 + depth - 8);
        surface.apply_retained(&mut job, &mut column, &palette, 0, 0, [106; 4], &colors,
            |_| Ok(("minecraft:plains", &biome)), false).unwrap();
        assert!(column.blocks.iter().all(|&id| palette.state(id).name == "minecraft:orange_terracotta"));
    }

    #[test]
    fn three_dimensional_threshold_samples_y_and_reference_cycles_fail() {
        let mut doc = document();
        let mut graph = Graph::from_document(731, &doc).unwrap();
        let noise = graph.registered_noise("minecraft:surface", &doc).unwrap();
        let pos = [31, 127, -49];
        let expected = graph.noise(noise, pos[0] as f64, pos[1] as f64, pos[2] as f64);
        assert_ne!(expected, graph.noise(noise, pos[0] as f64, 0.0, pos[2] as f64));
        let mut value = json!({"type":"minecraft:noise_threshold", "noise":"minecraft:surface",
            "min_threshold":expected, "max_threshold":expected, "is_3d":true});
        let context = Context { p: pos, biome: "minecraft:plains", cold: false, steep: false,
            depth: 3, secondary: 0.0, above: 1, below: 1, water: i32::MIN, min_surface: 0 };
        assert!(parse_condition(&value, &mut graph, &doc, -64, 384, 0).unwrap().matches(&graph, &context));
        value["is_3d"] = json!(false);
        assert!(!parse_condition(&value, &mut graph, &doc, -64, 384, 0).unwrap().matches(&graph, &context));
        doc["material_rules"] = json!({"custom:a":"custom:b", "custom:b":"custom:a"});
        assert!(parse_rule(&json!("custom:a"), &mut graph, &doc, &mut palette(), -64, 384, 0).is_err());
    }
}
