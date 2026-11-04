//! SurfaceRules codecs and SurfaceSystem's column pass, including terracotta
//! bands, eroded badlands and frozen ocean extensions (Minecraft 1.21.1).
use crate::{
    biome::{Biome, ClimateColors},
    blocks::{Palette, Pos, StateId, Volume},
    density::{integer, lerp, minecraft_type, number, string, Graph, Mode, Result, Scratch},
    random::{Positional, Random},
    terrain::{Job, Terrain},
};
use serde_json::Value;
mod exterior;
mod modded;
#[cfg(test)]
mod modded_tests;
mod material;
#[cfg(test)]
mod display_query_tests;
#[cfg(test)]
mod plan_tests;
#[cfg(test)]
mod specialization_tests;
#[cfg(test)]
mod noise_tests;
pub(crate) use exterior::ExteriorColumn;
#[derive(Clone)]
enum Rule {
    Block(StateId),
    Sequence(Vec<Rule>),
    Condition(Condition, Box<Rule>),
    Bands,
    Weighted { rules: Vec<Rule>, columns: Box<[usize; 256]> },
    ModBands { states: Vec<StateId>, frequency: f32, noise_scale: i32, noise: usize },
    Ore(material::OreRule),
    /// TerraBlender `terrablender:merged`. `NamespacedSurfaceRuleSource`
    /// dispatches on the biome's namespace and falls back to `base` whenever
    /// the namespaced rule answers with nothing.
    Namespaced {
        base: Box<Rule>,
        sources: Vec<(String, Rule)>,
    },
}
enum Condition {
    Biome(Vec<String>),
    Noise(usize, f64, f64, bool),
    Gradient(Positional, i32, i32),
    Y(i32, i32, bool),
    Water(i32, i32, bool),
    Cold,
    Steep,
    Hole,
    Above,
    Not(Box<Condition>),
    Stone(i32, bool, i32, bool),
    /// Alex's Caves `ac_simplex`: a simplex-noise lookup compared against a
    /// band. Its noise is a stock Gustavson table (no world seeding), but the
    /// scaling in `ACMath` and the coordinate truncation here are f32, so the
    /// arithmetic is reproduced in `crate::ac_simplex`.
    AcSimplex {
        min: f32,
        max: f32,
        scale: f32,
        y_scale: f32,
        offset: i32,
    },
    /// Youkaishomecoming `youkaishomecoming:noise`. Samples the noise on the
    /// column and at four corners offset by `radius` on the xz plane (y is
    /// pinned to 0), and requires the corners to disagree in sign.
    QuadNoise {
        id: usize,
        radius: f64,
        threshold: f64,
    },
}
// Positional factories are immutable seed pairs. Copy their state directly:
// specializing material rules must not advance any world or feature RNG.
fn copy_positional(random: &Positional) -> Positional {
    match *random {
        Positional::Legacy(seed) => Positional::Legacy(seed),
        Positional::Xoroshiro(lo, hi) => Positional::Xoroshiro(lo, hi),
    }
}
impl Clone for Condition {
    fn clone(&self) -> Self {
        match self {
            Self::Biome(names) => Self::Biome(names.clone()),
            Self::Noise(id, min, max, spatial) => Self::Noise(*id, *min, *max, *spatial),
            Self::Gradient(random, min, max) => Self::Gradient(copy_positional(random), *min, *max),
            Self::Y(anchor, multiplier, stone) => Self::Y(*anchor, *multiplier, *stone),
            Self::Water(offset, multiplier, stone) => Self::Water(*offset, *multiplier, *stone),
            Self::Cold => Self::Cold,
            Self::Steep => Self::Steep,
            Self::Hole => Self::Hole,
            Self::Above => Self::Above,
            Self::Not(condition) => Self::Not(condition.clone()),
            Self::Stone(offset, add, secondary, ceiling) => Self::Stone(*offset, *add, *secondary, *ceiling),
            Self::AcSimplex { min, max, scale, y_scale, offset } => Self::AcSimplex {
                min: *min, max: *max, scale: *scale, y_scale: *y_scale, offset: *offset,
            },
            Self::QuadNoise { id, radius, threshold } => Self::QuadNoise {
                id: *id, radius: *radius, threshold: *threshold,
            },
        }
    }
}

struct BiomeRule {
    rule: Rule,
    needs_cold: bool,
    needs_secondary: bool,
}
impl Rule {
    fn needs_cold(&self) -> bool {
        match self {
            Self::Condition(condition, rule) => condition.needs_cold() || rule.needs_cold(),
            Self::Sequence(rules) | Self::Weighted { rules, .. } => rules.iter().any(Self::needs_cold),
            Self::Namespaced { base, sources } => base.needs_cold()
                || sources.iter().any(|(_, rule)| rule.needs_cold()),
            _ => false,
        }
    }
    fn needs_secondary(&self) -> bool {
        match self {
            Self::Condition(condition, rule) => condition.needs_secondary() || rule.needs_secondary(),
            Self::Sequence(rules) | Self::Weighted { rules, .. } => rules.iter().any(Self::needs_secondary),
            Self::Namespaced { base, sources } => base.needs_secondary()
                || sources.iter().any(|(_, rule)| rule.needs_secondary()),
            _ => false,
        }
    }

    /// Resolve only immutable biome predicates and namespace dispatch. Every
    /// dynamic condition and Ore rule keeps its evaluation order and state.
    fn for_biome(&self, biome: &str) -> Option<Self> {
        match self {
            Self::Condition(condition, rule) => match condition.known_biome(biome) {
                Some(false) => None,
                Some(true) => rule.for_biome(biome),
                None => Some(Self::Condition(condition.clone(), Box::new(
                    rule.for_biome(biome).unwrap_or_else(|| Self::Sequence(Vec::new()))))),
            },
            Self::Sequence(rules) => Some(Self::Sequence(rules.iter()
                .filter_map(|rule| rule.for_biome(biome)).collect())),
            Self::Weighted { rules, columns } => Some(Self::Weighted {
                rules: rules.iter().map(|rule| rule.for_biome(biome)
                    .unwrap_or_else(|| Self::Sequence(Vec::new()))).collect(),
                columns: columns.clone(),
            }),
            Self::Namespaced { base, sources } => {
                let namespace = biome.split(':').next().unwrap_or("");
                let mut rules = Vec::with_capacity(2);
                if let Some((_, rule)) = sources.iter().find(|(name, _)| name == namespace) {
                    if let Some(rule) = rule.for_biome(biome) { rules.push(rule); }
                }
                if let Some(rule) = base.for_biome(biome) { rules.push(rule); }
                Some(Self::Sequence(rules))
            }
            _ => Some(self.clone()),
        }
    }
}
impl Condition {
    fn known_biome(&self, biome: &str) -> Option<bool> {
        match self {
            Self::Biome(names) => Some(names.iter().any(|name| name == biome)),
            Self::Not(condition) => condition.known_biome(biome).map(|value| !value),
            _ => None,
        }
    }
    fn needs_cold(&self) -> bool {
        match self {
            Self::Cold => true,
            Self::Not(condition) => condition.needs_cold(),
            _ => false,
        }
    }
    fn needs_secondary(&self) -> bool {
        match self {
            Self::Stone(_, _, range, _) => *range != 0,
            Self::Not(condition) => condition.needs_secondary(),
            _ => false,
        }
    }
}
#[derive(Clone, Copy)]
enum Steepness<'a> {
    Known(bool),
    Lazy(&'a dyn Fn() -> bool),
}
impl Steepness<'_> {
    fn get(self) -> bool {
        match self { Self::Known(value) => value, Self::Lazy(query) => query() }
    }
}
struct Context<'a> {
    p: Pos,
    biome: &'a str,
    cold: bool,
    steep: Steepness<'a>,
    depth: i32,
    secondary: f64,
    above: i32,
    below: i32,
    water: i32,
    min_surface: i32,
}
pub struct Surface {
    rule: Rule,
    biome_rules: std::collections::HashMap<String, BiomeRule>,
    fallback_needs_cold: bool,
    fallback_needs_secondary: bool,
    modern: bool,
    uses_density: bool,
    // Rules and their palette IDs are immutable for this world. Analyse their
    // reachable writes once rather than walking the tree for every neighbour.
    summaries: std::collections::HashMap<String, [bool; 6]>,
    global_summary: [bool; 6],
    conditional_display: bool,
    depth_predicates: Vec<(i32, bool, i32)>,
    bands: [StateId; 192],
    band_offset: usize,
    depth: usize,
    secondary: usize,
    badlands: [usize; 3],
    iceberg: [usize; 3],
    default: StateId,
    water: StateId,
    packed_ice: StateId,
    snow: StateId,
}
fn flag(v: &Value, key: &str) -> Result<bool> {
    v[key]
        .as_bool()
        .ok_or_else(|| format!("missing boolean {key}"))
}
fn anchor(v: &Value, min: i32, height: i32) -> Result<i32> {
    if v.get("absolute").is_some() {
        integer(v, "absolute")
    } else if v.get("above_bottom").is_some() {
        Ok(min + integer(v, "above_bottom")?)
    } else if v.get("below_top").is_some() {
        Ok(min + height - 1 - integer(v, "below_top")?)
    } else {
        Err("unknown vertical anchor".into())
    }
}
impl Surface {
    pub fn new(terrain: &mut Terrain, doc: &Value, palette: &mut Palette) -> Result<Self> {
        let rule = parse_rule(
            doc["settings"].get("material_rule").unwrap_or(&doc["settings"]["surface_rule"]),
            &mut terrain.graph,
            doc,
            palette,
            terrain.min_y,
            terrain.height,
            0,
        )?;
        let mut register = |name: &str| {
            terrain
                .graph
                .registered_noise(&format!("minecraft:{name}"), doc)
        };
        let band_offset = register("clay_bands_offset")?;
        let depth = register("surface")?;
        let secondary = register("surface_secondary")?;
        let badlands = [
            register("badlands_surface")?,
            register("badlands_pillar")?,
            register("badlands_pillar_roof")?,
        ];
        let iceberg = [
            register("iceberg_surface")?,
            register("iceberg_pillar")?,
            register("iceberg_pillar_roof")?,
        ];
        let mut r = terrain.graph.random.from_hash("minecraft:clay_bands");
        let mut bands = [palette.named("minecraft:terracotta")?; 192];
        let orange = palette.named("minecraft:orange_terracotta")?;
        let mut i = 0;
        while i < 192 {
            i += r.next_bounded(5) as usize + 1;
            if i < 192 {
                bands[i] = orange
            }
            i += 1;
        }
        for (size, color) in [(1, "yellow"), (2, "brown"), (1, "red")] {
            let state = palette.named(&format!("minecraft:{color}_terracotta"))?;
            make_bands(&mut r, &mut bands, size, state);
        }
        let white = palette.named("minecraft:white_terracotta")?;
        let gray = palette.named("minecraft:light_gray_terracotta")?;
        let count = r.next_bounded(7) + 9;
        let mut i = 0;
        for _ in 0..count {
            if i >= 192 {
                break;
            }
            bands[i] = white;
            if i > 1 && r.next_bool() {
                bands[i - 1] = gray
            }
            if i + 1 < 192 && r.next_bool() {
                bands[i + 1] = gray
            }
            i += r.next_bounded(16) as usize + 4;
        }
        fn uses_density(rule: &Rule) -> bool {
            match rule {
                Rule::Ore(_) => true,
                Rule::Sequence(rules) | Rule::Weighted { rules, .. } => rules.iter().any(uses_density),
                Rule::Condition(_, rule) => uses_density(rule),
                Rule::Namespaced { base, sources } => uses_density(base) || sources.iter().any(|(_, r)| uses_density(r)),
                _ => false,
            }
        }
        let mut surface = Self {
            modern: doc["settings"].get("material_rule").is_some(),
            uses_density: uses_density(&rule),
            fallback_needs_cold: rule.needs_cold(),
            fallback_needs_secondary: rule.needs_secondary(),
            biome_rules: std::collections::HashMap::new(),
            rule,
            summaries: std::collections::HashMap::new(),
            global_summary: [false; 6],
            conditional_display: false,
            depth_predicates: Vec::new(),
            bands,
            band_offset,
            depth,
            secondary,
            badlands,
            iceberg,
            default: material::state(&doc["settings"]["default_block"], palette)?,
            water: palette.named("minecraft:water")?,
            packed_ice: palette.named("minecraft:packed_ice")?,
            snow: palette.named("minecraft:snow_block")?,
        };
        surface.global_summary = surface.analyse_summaries(palette, None);
        if let Some(biomes) = doc["biomes"].as_object() {
            for name in biomes.keys() {
                let summary = surface.analyse_summaries(palette, Some(name));
                surface.summaries.insert(name.clone(), summary);
                let rule = surface.rule.for_biome(name).unwrap_or_else(|| Rule::Sequence(Vec::new()));
                surface.biome_rules.insert(name.clone(), BiomeRule {
                    needs_cold: rule.needs_cold(), needs_secondary: rule.needs_secondary(), rule });
            }
        }
        surface.conditional_display = surface.summaries.values().any(|summary|
            !summary[3] && (summary[4] || summary[5]));
        let mut predicates = Vec::new();
        surface.collect_depth_predicates(&surface.rule, &mut predicates);
        surface.depth_predicates = predicates;
        Ok(surface)
    }
    fn biome_rule(&self, biome: &str) -> (&Rule, bool, bool) {
        self.biome_rules.get(biome).map_or((&self.rule, self.fallback_needs_cold, self.fallback_needs_secondary),
            |plan| (&plan.rule, plan.needs_cold, plan.needs_secondary))
    }
    #[cfg(test)]
    fn replace_rule(&mut self, rule: Rule) {
        self.fallback_needs_cold = rule.needs_cold();
        self.fallback_needs_secondary = rule.needs_secondary();
        self.rule = rule;
        for (name, plan) in &mut self.biome_rules {
            let rule = self.rule.for_biome(name).unwrap_or_else(|| Rule::Sequence(Vec::new()));
            *plan = BiomeRule { needs_cold: rule.needs_cold(), needs_secondary: rule.needs_secondary(), rule };
        }
    }
    pub fn depth(&self, graph: &Graph, x: i32, z: i32) -> i32 {
        (graph.noise(self.depth, x as f64, 0., z as f64) * 2.75
            + 3.
            + graph.random.at(x, 0, z).next_double() * 0.25) as i32
    }
    pub fn band(&self, graph: &Graph, p: Pos) -> StateId {
        let offset =
            (graph.noise(self.band_offset, p[0] as f64, 0., p[2] as f64) * 4. + 0.5).floor() as i32;
        self.bands[(p[1] + offset + 192).rem_euclid(192) as usize]
    }
    pub fn min_surface(&self, job: &mut Job<'_>, x: i32, z: i32, depth: i32) -> i32 {
        if self.modern {
            let root = job.terrain.graph.root("chunk_surface_level")
                .expect("26.3 material system requires its chunk surface root");
            return (job.terrain.graph.compute(root, [x, 0, z], Mode::Block, &mut job.scratch).floor() as i32)
                .wrapping_add(depth).wrapping_sub(8);
        }
        let xx = x & !15;
        let zz = z & !15;
        let a = job.preliminary(xx, zz) as f64;
        let b = job.preliminary(xx + 16, zz) as f64;
        let c = job.preliminary(xx, zz + 16) as f64;
        let d = job.preliminary(xx + 16, zz + 16) as f64;
        (lerp(
            (z & 15) as f64 / 16.,
            lerp((x & 15) as f64 / 16., a, b),
            lerp((x & 15) as f64 / 16., c, d),
        )
        .floor() as i32)
            .wrapping_add(depth)
            .wrapping_sub(8)
    }
    pub fn apply_chunk<'b>(
        &self,
        job: &mut Job<'_>,
        world: &mut Volume,
        x0: i32,
        z0: i32,
        colors: &ClimateColors,
        mut biome: impl FnMut(Pos) -> Result<(&'b str, &'b Biome)>,
    ) -> Result<()> {
        for x in x0..x0 + 16 {
            for z in z0..z0 + 16 {
                self.apply_column(job, world, x0, z0, x, z, colors, &mut biome)?;
            }
        }
        Ok(())
    }
    pub fn sparse_safe(&self, palette: &Palette) -> bool {
        self.sparse_safe_for_biome(palette, None)
    }
    /// Steepness reads WORLD_SURFACE_WG: solid-to-water preserves its height.
    /// Only reachable air writes can change a predecessor column's top.
    pub fn sparse_safe_for_biome(&self, palette: &Palette, biome: Option<&str>) -> bool {
        self.summary_safe_for_biome(palette,biome,false,None)
    }
    pub(crate) fn display_safe_for_biome(&self, palette:&Palette,biome:Option<&str>)->bool {
        self.summary_safe_for_biome(palette,biome,true,None)
    }
    pub(crate) fn display_safe_at(&self, palette: &Palette, biome: &str, graph: &Graph, x: i32, z: i32) -> bool {
        self.summary_safe_at(palette, biome, true, graph, x, z)
    }
    pub(crate) fn sparse_safe_at(&self, palette: &Palette, biome: &str, graph: &Graph, x: i32, z: i32) -> bool {
        // Hole depends only on X/Z. Proving it false excludes air writes for
        // the entire neighbour column, including material depths not sampled.
        self.summary_safe_at(palette, biome, false, graph, x, z)
    }
    fn summary_safe_at(&self, palette: &Palette, biome: &str, dry: bool, graph: &Graph, x: i32, z: i32) -> bool {
        let uncached;
        let values = match self.summaries.get(biome) {
            Some(values) => values,
            None => {
                uncached = self.analyse_summaries(palette, Some(biome));
                &uncached
            }
        };
        let base = usize::from(dry) * 3;
        if values[base] == values[base + 1] && values[base] == values[base + 2] {
            return values[base];
        }
        values[base + 1 + usize::from(self.depth(graph, x, z) <= 0)]
    }
    fn summary_safe_for_biome(&self, palette:&Palette,biome:Option<&str>,dry:bool,hole:Option<bool>)->bool {
        let summary = match biome {
            Some(name) => self.summaries.get(name),
            None => Some(&self.global_summary),
        };
        match summary {
            Some(values) => values[usize::from(dry)*3 + match hole {None=>0,Some(false)=>1,Some(true)=>2}],
            // Keep the exact analysis for callers with a biome not in the
            // world's document. Never infer safety from a missing entry.
            None => self.analyse_summary(palette, biome, dry, hole),
        }
    }
    fn analyse_summaries(&self, palette: &Palette, biome: Option<&str>) -> [bool; 6] {
        std::array::from_fn(|i| self.analyse_summary(palette, biome, i>=3,
            match i%3 {0=>None,1=>Some(false),_=>Some(true)}))
    }
    fn analyse_summary(&self, palette:&Palette,biome:Option<&str>,dry:bool,hole:Option<bool>)->bool {
        fn known(condition: &Condition, biome: Option<&str>, hole: Option<bool>) -> Option<bool> {
            match condition {
                Condition::Biome(names) => biome.map(|b| names.iter().any(|n| n == b)),
                Condition::Hole => hole,
                Condition::Not(c) => known(c, biome, hole).map(|b| !b),
                _ => None,
            }
        }
        fn safe(rule: &Rule, p: &Palette, biome: Option<&str>,dry:bool,hole:Option<bool>) -> bool {
            match rule {
                Rule::Block(id) => !p.is_air(*id) && (!dry || !p.fluid(*id)),
                Rule::Weighted { rules, .. } => rules.iter().all(|r| safe(r,p,biome,dry,hole)),
                Rule::ModBands { states, .. } => states.iter().all(|id| !p.is_air(*id) && (!dry || !p.fluid(*id))),
                Rule::Bands => true,
                Rule::Ore(rule) => [rule.ore, rule.raw, rule.filler].iter()
                    .all(|&id| !p.is_air(id) && (!dry || !p.fluid(id))),
                Rule::Sequence(r) => r.iter().all(|r| safe(r, p, biome,dry,hole)),
                Rule::Condition(c, r) => known(c, biome,hole) == Some(false) || safe(r, p, biome,dry,hole),
                Rule::Namespaced { base, sources } => match biome {
                    // Dispatch reads the biome namespace; without a known biome
                    // the rule cannot be proven safe.
                    None => false,
                    Some(name) => {
                        let namespace = name.split(':').next().unwrap_or("");
                        match sources.iter().find(|(n, _)| n == namespace) {
                            // The namespaced rule may answer with nothing, in
                            // which case the base rule runs too.
                            Some((_, rule)) => safe(rule, p, biome,dry,hole) && safe(base, p, biome,dry,hole),
                            None => safe(base, p, biome,dry,hole),
                        }
                    }
                },
            }
        }
        safe(&self.rule, palette, biome,dry,hole)
    }
    pub(crate) fn eroded_biome(name: &str) -> bool {
        matches!(name, "minecraft:eroded_badlands" | "biomeswevegone:shattered_glacier" | "biomeswevegone:eroded_borealis")
    }
    pub(crate) fn geometry_biome(name: &str) -> bool {
        Self::eroded_biome(name) || matches!(name,"minecraft:frozen_ocean"|"minecraft:deep_frozen_ocean")
    }
    pub(crate) fn conditional_display(&self) -> bool { self.conditional_display }
    pub(crate) fn display_may_be_safe(&self, palette: &Palette, name: &str) -> bool {
        self.summary_safe_for_biome(palette, Some(name), true, Some(false))
            || self.summary_safe_for_biome(palette, Some(name), true, Some(true))
    }
    pub fn geometry_column<'b>(
        &self,
        job: &mut Job<'_>,
        world: &mut Volume,
        x: i32,
        z: i32,
        colors: &ClimateColors,
        mut biome: impl FnMut(Pos) -> Result<(&'b str, &'b Biome)>,
    ) -> Result<()> {
        let top = world.height(x, z, false);
        let t = job.terrain;
        let (name, b) = biome([x, if t.graph.legacy { 0 } else { top }, z])?;
        if Self::eroded_biome(name) {
            self.eroded(&t.graph, world, x, z, top);
        }
        if name == "minecraft:frozen_ocean" || name == "minecraft:deep_frozen_ocean" {
            let depth = self.depth(&t.graph, x, z);
            let min = self.min_surface(job, x, z, depth);
            self.frozen(
                &t.graph,
                world,
                x,
                z,
                top,
                min,
                t.sea_level,
                colors.temperature_with_sea_level(b, [x, 63, z], t.sea_level) > 0.1,
            );
        }
        Ok(())
    }
    /// Maximum downward stone-depth predicate used anywhere in the rule tree.
    /// With this many extra blocks, a truncated solid run produces exactly the
    /// same predicate results for every retained output material.
    pub fn exterior_padding(&self, graph: &Graph, x: i32, z: i32) -> Option<i32> {
        let predicates = &self.depth_predicates;
        if predicates.is_empty() { return Some(1); }
        let depth = std::cell::LazyCell::new(|| self.depth(graph, x, z));
        let secondary = std::cell::LazyCell::new(|| graph.noise(self.secondary, x as f64, 0., z as f64));
        let mut maximum = 0;
        for &(offset, add, range) in predicates {
            let extra = if range == 0 {0} else {((*secondary+1.)/2.*range as f64) as i32};
            let value = 1i32.checked_add(offset)?
                .checked_add(if add {*depth} else {0})?.checked_add(extra)?;
            maximum = maximum.max(value);
        }
        let padding = maximum.checked_add(1)?;
        (padding<=64).then_some(padding)
    }
    fn collect_depth_predicates(&self, rule: &Rule, predicates: &mut Vec<(i32,bool,i32)>) {
        fn collect(condition: &Condition, predicates: &mut Vec<(i32,bool,i32)>) {
            match condition {
                Condition::Stone(offset, add, range, true) => {
                    let value=(*offset,*add,*range);
                    if !predicates.contains(&value) {predicates.push(value);}
                }
                Condition::Not(inner) => collect(inner,predicates),
                _ => {}
            }
        }
        match rule {
            Rule::Sequence(rules) | Rule::Weighted { rules, .. } => for rule in rules {self.collect_depth_predicates(rule,predicates);},
            Rule::Condition(condition, rule) => {
                collect(condition,predicates);self.collect_depth_predicates(rule,predicates);
            }
            Rule::Namespaced {base,sources} => {
                self.collect_depth_predicates(base,predicates);
                for (_,rule) in sources {self.collect_depth_predicates(rule,predicates);}
            }
            _ => {}
        }
    }
    #[cfg(test)]
    fn exterior_padding_recursive(&self, graph: &Graph, x: i32, z: i32) -> Option<i32> {
        fn condition(c: &Condition, depth: i32, secondary: f64) -> Option<i32> {
            match c {
                Condition::Stone(offset, add, range, true) => {
                    let extra = if *range == 0 {
                        0
                    } else {
                        ((secondary + 1.) / 2. * *range as f64) as i32
                    };
                    1i32.checked_add(*offset)?
                        .checked_add(if *add { depth } else { 0 })?
                        .checked_add(extra)
                }
                Condition::Not(c) => condition(c, depth, secondary),
                _ => Some(0),
            }
        }
        fn rule(r: &Rule, depth: i32, secondary: f64) -> Option<i32> {
            match r {
                Rule::Sequence(rs) | Rule::Weighted { rules: rs, .. } => rs
                    .iter()
                    .try_fold(0, |n, r| Some(n.max(rule(r, depth, secondary)?))),
                Rule::Condition(c, r) => {
                    Some(condition(c, depth, secondary)?.max(rule(r, depth, secondary)?))
                }
                Rule::Namespaced { base, sources } => sources.iter().try_fold(
                    rule(base, depth, secondary)?,
                    |n, (_, r)| Some(n.max(rule(r, depth, secondary)?)),
                ),
                _ => Some(0),
            }
        }
        let padding = rule(
            &self.rule,
            self.depth(graph, x, z),
            graph.noise(self.secondary, x as f64, 0., z as f64),
        )?
        .max(0)
        .checked_add(1)?;
        (padding <= 64).then_some(padding)
    }

    pub fn apply_column<'b>(
        &self,
        job: &mut Job<'_>,
        world: &mut Volume,
        x0: i32,
        z0: i32,
        x: i32,
        z: i32,
        colors: &ClimateColors,
        mut biome: impl FnMut(Pos) -> Result<(&'b str, &'b Biome)>,
    ) -> Result<()> {
        let t = job.terrain;
        let graph = &t.graph;
        let original_top = world.height(x, z, false);
        let (name, b) = biome([x, if t.graph.legacy { 0 } else { original_top }, z])?;
        if Self::eroded_biome(name) {
            self.eroded(graph, world, x, z, original_top);
        }
        let top = world.height(x, z, false);
        let depth = self.depth(graph, x, z);
        let secondary = std::cell::LazyCell::new(|| graph.noise(self.secondary, x as f64, 0., z as f64));
        let min_surface = self.min_surface(job, x, z, depth);
        let north = world.height(x, (z - 1).max(z0), false);
        let south = world.height(x, (z + 1).min(z0 + 15), false);
        let steep = if south >= north + 4 {
            true
        } else {
            let west = world.height((x - 1).max(x0), z, false);
            let east = world.height((x + 1).min(x0 + 15), z, false);
            west >= east + 4
        };
        let mut above = 0;
        let mut water = i32::MIN;
        let mut floor = i32::MAX;
        for y in (world.origin[1]..=top).rev() {
            let p = [x, y, z];
            let state = world.get(p);
            if world.palette.is_air(state) {
                above = 0;
                water = i32::MIN;
            } else if world.palette.fluid(state) {
                if water == i32::MIN {
                    water = y + 1;
                }
            } else {
                if floor >= y {
                    floor = -32512;
                    for yy in (world.origin[1] - 1..y).rev() {
                        let s = world.get([x, yy, z]);
                        if !world.palette.stone(s) {
                            floor = yy + 1;
                            break;
                        }
                    }
                }
                above += 1;
                if self.modern || state == self.default {
                    let (biome_name, biome_data) = biome(p)?;
                    let (rule, needs_cold, needs_secondary) = self.biome_rule(biome_name);
                    let context = Context {
                        p,
                        biome: biome_name,
                        cold: needs_cold && colors.temperature_with_sea_level(biome_data, p, t.sea_level) < 0.15,
                        steep: Steepness::Known(steep),
                        depth,
                        secondary: if needs_secondary { *secondary } else { 0.0 },
                        above,
                        below: y - floor + 1,
                        water,
                        min_surface,
                    };
                    if let Some(id) = self.rule(rule, graph, &context, &mut Some(&mut job.scratch)) {
                        world.set(p, id);
                    }
                }
            }
        }
        if name == "minecraft:frozen_ocean" || name == "minecraft:deep_frozen_ocean" {
            self.frozen(
                graph,
                world,
                x,
                z,
                original_top,
                min_surface,
                t.sea_level,
                colors.temperature_with_sea_level(b, [x, 63, z], t.sea_level) > 0.1,
            );
        }
        if world.incomplete {
            Err("surface pass missing horizontal neighbour data".into())
        } else {
            Ok(())
        }
    }
    /// Run the world's real material rules against a cheap exterior context.
    /// Neighbour steepness, pillars and iceberg geometry await exact refinement.
    pub fn preview_materials(&self, t: &Terrain, x: i32, z: i32, floor: i32,
        water: i32, name: &str, biome: &Biome, colors: &ClimateColors) -> [StateId; 3] {
        self.summary_materials(t,x,z,floor,water,name,biome,colors,Steepness::Known(false),None)
    }
    pub(crate) fn display_materials(&self,t:&Terrain,x:i32,z:i32,floor:i32,water:i32,name:&str,biome:&Biome,colors:&ClimateColors,steep:impl FnOnce()->bool,workspace:&mut Option<Scratch>)->[StateId;3] {
        // Old cache markers can depend on traversal order. Preserve their
        // original eager neighbour queries; modern pointwise graphs do not.
        if !t.graph.is_modern() || t.graph.has_stateful_queries() {
            return self.summary_materials(t,x,z,floor,water,name,biome,colors,
                Steepness::Known(steep()),Some(workspace));
        }
        let value = std::cell::LazyCell::new(steep);
        let query = || *value;
        self.summary_materials(t,x,z,floor,water,name,biome,colors,
            Steepness::Lazy(&query),Some(workspace))
    }
    fn summary_materials(&self,t:&Terrain,x:i32,z:i32,floor:i32,water:i32,name:&str,biome:&Biome,colors:&ClimateColors,steep:Steepness<'_>,workspace:Option<&mut Option<Scratch>>)->[StateId;3] {
        let (rule, needs_cold, needs_secondary) = self.biome_rule(name);
        let depth = self.depth(&t.graph, x, z);
        let secondary = if needs_secondary { t.graph.noise(self.secondary, x as f64, 0., z as f64) } else { 0.0 };
        // Material rules use their own scalar/default-volume context. Reuse
        // it only within a caller's chunk batch, never the terrain job's volume
        // or a legacy graph whose Flat/Cell caches have traversal semantics.
        let mut workspace = workspace.filter(|_| self.modern && !t.graph.has_stateful_queries());
        let retained = workspace.as_mut().and_then(|slot| slot.take());
        let fresh = retained.is_none();
        let mut owned_scratch = retained.or_else(|| (self.modern || self.uses_density).then(||
            t.graph.scratch(x & !3, z & !3, t.cell_width, t.cell_height)
                .expect("validated terrain dimensions")));
        if fresh {
            if let Some(scratch) = owned_scratch.as_mut() { t.graph.initialize_column_caches(scratch); }
        }
        let min_surface = if self.modern {
            let root = t.graph.root("chunk_surface_level").expect("26.3 material surface root");
            (t.graph.compute(root, [x, 0, z], Mode::Block, owned_scratch.as_mut().unwrap()).floor() as i32)
                .wrapping_add(depth).wrapping_sub(8)
        } else { floor - depth - 8 };
        let mut scratch = owned_scratch.as_mut();
        let result = [1, 2, 7].map(|offset| {
            let p = [x, floor - offset, z];
            let c = Context { p, biome: name, cold: needs_cold && colors.temperature_with_sea_level(biome, p, t.sea_level) < 0.15,
                steep, depth, secondary, above: offset,
                below: (floor - offset - t.min_y + 1).max(1), water,
                min_surface };
            self.rule(rule, &t.graph, &c, &mut scratch).unwrap_or(self.default)
        });
        if let Some(workspace) = workspace { *workspace = owned_scratch; }
        result
    }

    fn rule(&self, rule: &Rule, graph: &Graph, c: &Context<'_>, scratch: &mut Option<&mut Scratch>) -> Option<StateId> {
        match rule {
            Rule::Block(id) => Some(*id),
            Rule::Bands => Some(self.band(graph, c.p)),
            Rule::Weighted { rules, columns } => self.rule(&rules[columns[((c.p[0] & 15)*16+(c.p[2]&15)) as usize]], graph, c, scratch),
            Rule::ModBands { states, frequency, noise_scale, noise } => {
                let offset = graph.noise(*noise, (c.p[0] as f32 * frequency) as f64, 0.,
                    (c.p[2] as f32 * frequency) as f64) * *noise_scale as f64;
                let y = (c.p[1] as i64).wrapping_add((offset + 0.5).floor() as i64);
                Some(states[y.rem_euclid((states.len()-1) as i64) as usize])
            }
            Rule::Ore(rule) => rule.sample(graph, c.p, scratch.as_deref_mut()
                .expect("material density scratch must be initialized")),
            Rule::Sequence(rules) => rules.iter().find_map(|r| self.rule(r, graph, c, scratch)),
            Rule::Namespaced { base, sources } => {
                let namespace = c.biome.split(':').next().unwrap_or("");
                if let Some((_, rule)) = sources.iter().find(|(name, _)| name == namespace) {
                    if let Some(state) = self.rule(rule, graph, c, scratch) {
                        return Some(state);
                    }
                }
                self.rule(base, graph, c, scratch)
            }
            Rule::Condition(test, then) => {
                if test.matches(graph, c) {
                    self.rule(then, graph, c, scratch)
                } else {
                    None
                }
            }
        }
    }
    fn eroded(&self, g: &Graph, w: &mut Volume, x: i32, z: i32, top: i32) {
        let n = g.noise(self.badlands[0], x as f64, 0., z as f64).abs() * 8.25;
        let pillar = g.noise(self.badlands[1], x as f64 * 0.2, 0., z as f64 * 0.2) * 15.;
        let v = n.min(pillar);
        if v <= 0. {
            return;
        }
        let roof = (g.noise(self.badlands[2], x as f64 * 0.75, 0., z as f64 * 0.75) * 1.5).abs();
        let end = (64. + (v * v * 2.5).min((roof * 50.).ceil() + 24.)).floor() as i32;
        if top > end {
            return;
        }
        for y in (w.origin[1]..=end).rev() {
            let b = w.get([x, y, z]);
            if w.palette.state(b).name == w.palette.state(self.default).name {
                break;
            }
            if w.palette.is(b, "minecraft:water") {
                return;
            }
        }
        for y in (w.origin[1]..=end).rev() {
            let p = [x, y, z];
            let b = w.get(p);
            if !w.palette.is_air(b) {
                break;
            }
            w.set(p, self.default);
        }
    }
    pub(crate) fn iceberg_noise(&self, g: &Graph, x: i32, z: i32) -> f64 {
        (g.noise(self.iceberg[0], x as f64, 0., z as f64) * 8.25)
            .abs()
            .min(g.noise(self.iceberg[1], x as f64 * 1.28, 0., z as f64 * 1.28) * 15.)
    }
    fn frozen(
        &self,
        g: &Graph,
        w: &mut Volume,
        x: i32,
        z: i32,
        top: i32,
        min: i32,
        sea: i32,
        melt: bool,
    ) {
        let v = self.iceberg_noise(g, x, z);
        if v <= 1.8 {
            return;
        }
        let roof = (g.noise(self.iceberg[2], x as f64 * 1.17, 0., z as f64 * 1.17) * 1.5).abs();
        let mut height = (v * v * 1.2).min((roof * 40.).ceil() + 14.);
        if melt {
            height -= 2.;
        }
        let bottom = if height > 2. {
            let b = sea as f64 - height - 7.;
            height += sea as f64;
            b
        } else {
            height = 0.;
            0.
        };
        let mut r = g.random.at(x, 0, z);
        let snow_count = 2 + r.next_bounded(4);
        let snow_start = sea + 18 + r.next_bounded(10);
        let mut snow = 0;
        for y in (min..=top.max(height as i32 + 1)).rev() {
            let p = [x, y, z];
            let b = w.get(p);
            if (w.palette.is_air(b) && y < (height as i32) && r.next_double() > 0.01)
                || (b == self.water
                    && y > bottom as i32
                    && y < sea
                    && bottom != 0.
                    && r.next_double() > 0.15)
            {
                if snow <= snow_count && y > snow_start {
                    w.set(p, self.snow);
                    snow += 1;
                } else {
                    w.set(p, self.packed_ice);
                }
            }
        }
    }
}
impl Condition {
    fn matches(&self, g: &Graph, c: &Context<'_>) -> bool {
        match self {
            Self::Biome(names) => names.iter().any(|name| name == c.biome),
            Self::Noise(id, a, b, is_3d) => {
                let v = g.noise(*id, c.p[0] as f64, if *is_3d { c.p[1] as f64 } else { 0. }, c.p[2] as f64);
                v >= *a && v <= *b
            }
            Self::Gradient(r, a, b) => {
                if c.p[1] <= *a {
                    true
                } else if c.p[1] >= *b {
                    false
                } else {
                    (r.at(c.p[0], c.p[1], c.p[2]).next_float() as f64)
                        < 1. + (c.p[1] - a) as f64 / (b - a) as f64 * -1.
                }
            }
            Self::Y(anchor, mul, stone) => {
                c.p[1] + if *stone { c.above } else { 0 } >= anchor + c.depth * mul
            }
            Self::Water(offset, mul, stone) => {
                c.water == i32::MIN
                    || c.p[1] + if *stone { c.above } else { 0 } >= c.water + offset + c.depth * mul
            }
            Self::Cold => c.cold,
            Self::Steep => c.steep.get(),
            Self::Hole => c.depth <= 0,
            Self::Above => c.p[1] >= c.min_surface,
            Self::Not(test) => !test.matches(g, c),
            Self::Stone(offset, add, secondary, ceiling) => {
                let depth = if *ceiling { c.below } else { c.above };
                let extra = if *secondary == 0 {
                    0
                } else {
                    ((c.secondary - -1.) / 2. * *secondary as f64) as i32
                };
                depth <= 1 + offset + if *add { c.depth } else { 0 } + extra
            }
            Self::AcSimplex {
                min,
                max,
                scale,
                y_scale,
                offset,
            } => crate::ac_simplex::ac_simplex_test(
                c.p[0], c.p[1], c.p[2], *min, *max, *scale, *y_scale, *offset,
            ),
            Self::QuadNoise {
                id,
                radius,
                threshold,
            } => {
                let x = c.p[0] as f64;
                let z = c.p[2] as f64;
                if g.noise(*id, x, 0.0, z).abs() > *threshold {
                    return false;
                }
                let a = g.noise(*id, x - radius, 0.0, z - radius);
                let b = g.noise(*id, x - radius, 0.0, z + radius);
                let cc = g.noise(*id, x + radius, 0.0, z - radius);
                let d = g.noise(*id, x + radius, 0.0, z + radius);
                let all_negative = a < 0.0 && b < 0.0 && cc < 0.0 && d < 0.0;
                let all_positive = a > 0.0 && b > 0.0 && cc > 0.0 && d > 0.0;
                !(all_negative || all_positive)
            }
        }
    }
}
fn parse_rule(
    v: &Value,
    g: &mut Graph,
    doc: &Value,
    p: &mut Palette,
    min: i32,
    height: i32,
    depth: usize,
) -> Result<Rule> {
    if depth > 128 {
        return Err("surface rule depth exceeded".into());
    }
    if let Some(resolved) = material::reference(v, doc, "material_rules")? {
        return parse_rule(resolved, g, doc, p, min, height, depth + 1);
    }
    if let Some(rule) = modded::parse(v, g, doc, p, min, height, depth)? { return Ok(rule); }
    // `minecraft_type` only accepts the `minecraft:` prefix, so mod codecs have
    // to be matched on the raw type string before it runs.
    if string(v, "type")? == "terrablender:merged" {
        let base = parse_rule(&v["base"], g, doc, p, min, height, depth + 1)?;
        let sources = v["sources"].as_object().ok_or("missing merged sources")?;
        if sources.len() > 256 {
            return Err("merged source budget".into());
        }
        return Ok(Rule::Namespaced {
            base: Box::new(base),
            sources: sources
                .iter()
                .map(|(name, rule)| {
                    Ok((
                        name.clone(),
                        parse_rule(rule, g, doc, p, min, height, depth + 1)?,
                    ))
                })
                .collect::<Result<_>>()?,
        });
    }
    Ok(match minecraft_type(v)? {
        "block" => Rule::Block(material::state(&v["result_state"], p)?),
        "ore_vein" => Rule::Ore(material::OreRule::parse(v, g, doc, p)?),
        "bandlands" => Rule::Bands,
        "sequence" => {
            let values = v["sequence"].as_array().ok_or("missing surface sequence")?;
            if values.len() > 4096 {
                return Err("surface sequence budget".into());
            }
            Rule::Sequence(
                values
                    .iter()
                    .map(|v| parse_rule(v, g, doc, p, min, height, depth + 1))
                    .collect::<Result<_>>()?,
            )
        }
        "condition" => Rule::Condition(
            parse_condition(&v["if_true"], g, doc, min, height, 0)?,
            Box::new(parse_rule(
                &v["then_run"],
                g,
                doc,
                p,
                min,
                height,
                depth + 1,
            )?),
        ),
        s => return Err(format!("unsupported surface rule {s}")),
    })
}
fn parse_condition(
    v: &Value,
    g: &mut Graph,
    doc: &Value,
    min: i32,
    height: i32,
    depth: usize,
) -> Result<Condition> {
    if depth > 128 {
        return Err("surface condition depth exceeded".into());
    }
    if let Some(resolved) = material::reference(v, doc, "material_conditions")? {
        return parse_condition(resolved, g, doc, min, height, depth + 1);
    }
    // As with rules, `minecraft_type` rejects anything outside the vanilla
    // namespace, so mod conditions are matched on the raw type first.
    match string(v, "type")? {
        "youkaishomecoming:noise" => {
            return Ok(Condition::QuadNoise {
                id: g.registered_noise(string(v, "noise")?, doc)?,
                radius: number(v, "radius")?,
                threshold: number(v, "threshold")?,
            })
        }
        "alexscaves:ac_simplex" => {
            return Ok(Condition::AcSimplex {
                min: number(v, "noise_min")? as f32,
                max: number(v, "noise_max")? as f32,
                scale: number(v, "noise_scale")? as f32,
                y_scale: number(v, "y_scale")? as f32,
                offset: integer(v, "offset_type")?,
            })
        }
        _ => {}
    }
    Ok(match minecraft_type(v)? {
        "biome" => Condition::Biome(material::biome_names(&v["biome_is"], doc)?),
        "noise_threshold" => Condition::Noise(
            g.registered_noise(string(v, "noise")?, doc)?,
            number(v, "min_threshold")?,
            number(v, "max_threshold")?,
            v.get("is_3d").map_or(Ok(false), |value| value.as_bool().ok_or("invalid noise dimensionality"))?,
        ),
        "vertical_gradient" => Condition::Gradient(
            g.random.from_hash(string(v, "random_name")?).positional(),
            anchor(&v["true_at_and_below"], min, height)?,
            anchor(&v["false_at_and_above"], min, height)?,
        ),
        "y_above" => Condition::Y(
            anchor(&v["anchor"], min, height)?,
            integer(v, "surface_depth_multiplier")?,
            flag(v, "add_stone_depth")?,
        ),
        "water" => Condition::Water(
            integer(v, "offset")?,
            integer(v, "surface_depth_multiplier")?,
            flag(v, "add_stone_depth")?,
        ),
        "temperature" => Condition::Cold,
        "steep" => Condition::Steep,
        "hole" => Condition::Hole,
        "above_preliminary_surface" => Condition::Above,
        "not" => Condition::Not(Box::new(parse_condition(
            &v["invert"],
            g,
            doc,
            min,
            height,
            depth + 1,
        )?)),
        "stone_depth" => {
            let surface = string(v, "surface_type")?;
            if surface != "floor" && surface != "ceiling" {
                return Err("invalid cave surface".into());
            }
            Condition::Stone(
                integer(v, "offset")?,
                flag(v, "add_surface_depth")?,
                integer(v, "secondary_depth_range")?,
                surface == "ceiling",
            )
        }
        s => return Err(format!("unsupported surface condition {s}")),
    })
}
fn make_bands(r: &mut Random, bands: &mut [StateId; 192], base: i32, color: StateId) {
    let count = r.next_bounded(10) + 6;
    for _ in 0..count {
        let size = base + r.next_bounded(3);
        let start = r.next_bounded(192) as usize;
        for i in start..(start + size as usize).min(192) {
            bands[i] = color;
        }
    }
}
