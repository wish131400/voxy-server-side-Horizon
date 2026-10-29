//! Vanilla IntProvider and block-state providers used by vegetation.
use crate::{
    blocks::{Palette, Pos, StateId, Volume},
    density::{clamped_lerp, integer, minecraft_type, number, string, Result},
    noise::{modern::Noise, NormalNoise},
    random::Random,
    vegetation::Predicate,
};
use serde_json::Value;
pub enum IntProvider {
    Constant(i32),
    Uniform(i32, i32),
    Biased(i32, i32),
    Clamped(Box<Self>, i32, i32),
    Normal(f32, f32, i32, i32),
    Weighted(Vec<(Self, i32)>, i32),
    Trapezoid(i32, i32, i32),
}
impl IntProvider {
    pub fn parse(v: &Value) -> Result<Self> {
        Self::parse_depth(v, 0)
    }
    fn parse_depth(v: &Value, depth: usize) -> Result<Self> {
        if depth > 64 {
            return Err("int provider recursion limit".into());
        }
        if let Some(c) = v.as_i64() {
            return Ok(Self::Constant(
                i32::try_from(c).map_err(|_| "integer constant overflow")?,
            ));
        }
        let t = minecraft_type(v)?;
        let value = &v["value"];
        let value = if value.is_null() { v } else { value };
        let range = |v: &Value| -> Result<(i32, i32)> {
            let a = integer(v, "min_inclusive")?;
            let b = integer(v, "max_inclusive")?;
            if b < a || b as i64 - a as i64 >= i32::MAX as i64 {
                return Err("invalid int provider interval".into());
            }
            Ok((a, b))
        };
        Ok(match t {
            "constant" => Self::Constant(if value.is_number() {
                value
                    .as_i64()
                    .and_then(|v| i32::try_from(v).ok())
                    .ok_or("constant out of range")?
            } else {
                integer(value, "value")?
            }),
            "uniform" => {
                let (a, b) = range(value)?;
                Self::Uniform(a, b)
            }
            "biased_to_bottom" => {
                let (a, b) = range(value)?;
                Self::Biased(a, b)
            }
            "clamped" => {
                let (a, b) = range(value)?;
                Self::Clamped(
                    Box::new(Self::parse_depth(&value["source"], depth + 1)?),
                    a,
                    b,
                )
            }
            "clamped_normal" => {
                let (a, b) = range(value)?;
                Self::Normal(
                    number(value, "mean")? as f32,
                    number(value, "deviation")? as f32,
                    a,
                    b,
                )
            }
            "weighted_list" => {
                let mut entries = vec![];
                let mut total = 0i32;
                for entry in value["distribution"]
                    .as_array()
                    .ok_or("missing distribution")?
                {
                    let weight = integer(entry, "weight")?;
                    if weight < 0 {
                        return Err("negative weight".into());
                    }
                    total = total.checked_add(weight).ok_or("weight overflow")?;
                    entries.push((Self::parse_depth(&entry["data"], depth + 1)?, weight));
                }
                if total <= 0 {
                    return Err("empty weighted distribution".into());
                }
                Self::Weighted(entries, total)
            }
            "trapezoid" => {
                let min = integer(value, "min")?;
                let max = integer(value, "max")?;
                let plateau = integer(value, "plateau")?;
                let span = max as i64 - min as i64;
                if span < 0 || span >= i32::MAX as i64 || plateau < 0 || plateau as i64 > span {
                    return Err("invalid trapezoid interval".into());
                }
                Self::Trapezoid(min, max, plateau)
            }
            s => return Err(format!("unsupported int provider {s}")),
        })
    }
    pub fn sample(&self, r: &mut Random) -> i32 {
        match self {
            Self::Constant(v) => *v,
            Self::Uniform(a, b) => a + r.next_bounded(b - a + 1),
            Self::Biased(a, b) => {
                let bound = r.next_bounded(b - a + 1) + 1;
                a + r.next_bounded(bound)
            }
            Self::Clamped(v, a, b) => v.sample(r).clamp(*a, *b),
            Self::Normal(mean, dev, a, b) => {
                ((*mean + r.next_gaussian() as f32 * dev).clamp(*a as f32, *b as f32)) as i32
            }
            Self::Weighted(entries, total) => {
                let mut v = r.next_bounded(*total);
                for (entry, weight) in entries {
                    v -= weight;
                    if v < 0 {
                        return entry.sample(r);
                    }
                }
                unreachable!()
            }
            Self::Trapezoid(min, max, plateau) => {
                if *plateau == 0 && *max as i64 == -(*min as i64) {
                    r.next_bounded(max + 1) - r.next_bounded(max + 1)
                } else {
                    let span = max - min;
                    if *plateau == span {
                        return min + r.next_bounded(span + 1);
                    }
                    let slope = (span - plateau) / 2;
                    min + r.next_bounded(span - slope + 1) + r.next_bounded(slope + 1)
                }
            }
        }
    }
}
pub enum StateProvider {
    Simple(StateId),
    Weighted(Vec<(StateId, i32)>, i32),
    Rotated(StateId),
    RandomInt(Box<Self>, String, IntProvider),
    RuleBased(Vec<(Predicate, Self)>, Option<Box<Self>>),
    RotatedModern(Box<Self>, Option<usize>),
    CopyProperties(Box<Self>),
    Noise {
        noise: ProviderNoise,
        scale: f64,
        states: Vec<StateId>,
    },
    Threshold {
        noise: ProviderNoise,
        scale: f64,
        threshold: f64,
        chance: f32,
        default: StateId,
        low: Vec<StateId>,
        high: Vec<StateId>,
    },
    Dual {
        noise: ProviderNoise,
        scale: f64,
        slow: ProviderNoise,
        slow_scale: f32,
        variety: [i32; 2],
        states: Vec<StateId>,
    },
}
pub enum ProviderNoise {
    Legacy(NormalNoise),
    Modern(Noise),
}
impl ProviderNoise {
    fn sample(&self, x: f64, y: f64, z: f64) -> f64 {
        match self {
            Self::Legacy(noise) => noise.sample(x, y, z),
            Self::Modern(noise) => noise.sample(x, y, z, false) as f64,
        }
    }
    fn modern(&self) -> bool {
        matches!(self, Self::Modern(_))
    }
}
fn provider_noise(v: &Value, key: &str) -> Result<ProviderNoise> {
    let seed = v["seed"].as_i64().ok_or("missing provider seed")?;
    let n = &v[key];
    if n.get("base_octave").is_some() {
        return Noise::from_declaration(&mut Random::new(seed, 2), n).map(ProviderNoise::Modern);
    }
    let first = integer(n, "firstOctave")?;
    let amplitudes: Vec<f64> = serde_json::from_value(n["amplitudes"].clone())
        .map_err(|e| format!("noise amplitudes: {e}"))?;
    NormalNoise::new(&mut Random::new(seed, 2), first, &amplitudes, false)
        .map(ProviderNoise::Legacy)
        .ok_or("invalid state provider noise".into())
}
fn state_list(v: &Value, p: &mut Palette) -> Result<Vec<StateId>> {
    let values = v.as_array().ok_or("missing provider states")?;
    if values.is_empty() || values.len() > 4096 {
        return Err("provider state count limit".into());
    }
    values.iter().map(|v| p.intern(v)).collect()
}
fn direction_index(value: &str) -> Result<usize> {
    ["down", "up", "north", "south", "west", "east"]
        .iter()
        .position(|s| *s == value)
        .ok_or_else(|| format!("unsupported provider direction {value}"))
}
impl StateProvider {
    pub fn parse(v: &Value, p: &mut Palette) -> Result<Self> {
        Self::parse_with_registries(v, &Value::Null, p)
    }
    pub fn parse_with_registries(v: &Value, doc: &Value, p: &mut Palette) -> Result<Self> {
        Self::parse_depth(v, doc, p, 0)
    }
    fn parse_depth(v: &Value, doc: &Value, p: &mut Palette, depth: usize) -> Result<Self> {
        if depth > 64 {
            return Err("state provider recursion limit".into());
        }
        if let Some(name) = v.as_str() {
            let name = if name.contains(':') {
                name.to_owned()
            } else {
                format!("minecraft:{name}")
            };
            let provider = doc["block_state_providers"]
                .get(&name)
                .or_else(|| {
                    doc["custom_registries"]["minecraft:worldgen/block_state_provider"].get(&name)
                })
                .ok_or_else(|| format!("missing block state provider {name}"))?;
            return Self::parse_depth(provider, doc, p, depth + 1);
        }
        // Modern DIRECT_CODEC accepts an inline block state as the simple provider.
        if v.get("type").is_none() && (v.get("Name").is_some() || v.get("id").is_some()) {
            return Ok(Self::Simple(p.intern(v)?));
        }
        Ok(match minecraft_type(v)? {
            "simple_state_provider" | "simple" => Self::Simple(p.intern(&v["state"])?),
            "rotated_block_provider" => {
                let id = p.intern(&v["state"])?;
                let name = p.state(id).name.clone();
                Self::Rotated(p.named(&name)?)
            }
            "rotated" => Self::RotatedModern(
                Box::new(Self::parse_depth(&v["state"], doc, p, depth + 1)?),
                v.get("direction")
                    .map(|v| direction_index(v.as_str().ok_or("invalid provider direction")?))
                    .transpose()?,
            ),
            "copy_properties" => Self::CopyProperties(Box::new(Self::parse_depth(
                &v["source"],
                doc,
                p,
                depth + 1,
            )?)),
            "rule_based" => {
                let rules = v["rules"]
                    .as_array()
                    .ok_or("missing state provider rules")?
                    .iter()
                    .map(|v| {
                        Ok((
                            Predicate::parse(&v["if_true"], p, 0)?,
                            Self::parse_depth(&v["then"], doc, p, depth + 1)?,
                        ))
                    })
                    .collect::<Result<Vec<_>>>()?;
                let fallback = v
                    .get("fallback")
                    .filter(|v| !v.is_null())
                    .map(|v| Self::parse_depth(v, doc, p, depth + 1).map(Box::new))
                    .transpose()?;
                Self::RuleBased(rules, fallback)
            }
            "weighted_state_provider" | "weighted" => {
                let mut entries = vec![];
                let mut total = 0i32;
                for e in v["entries"].as_array().ok_or("missing weighted states")? {
                    let weight = integer(e, "weight")?;
                    if weight < 0 {
                        return Err("negative state weight".into());
                    }
                    total = total.checked_add(weight).ok_or("state weight overflow")?;
                    entries.push((p.intern(&e["data"])?, weight));
                }
                if total <= 0 {
                    return Err("zero state weight".into());
                }
                Self::Weighted(entries, total)
            }
            "randomized_int_state_provider" | "randomized_int" => Self::RandomInt(
                Box::new(Self::parse_depth(&v["source"], doc, p, depth + 1)?),
                string(v, "property")?.into(),
                IntProvider::parse(&v["values"])?,
            ),
            "noise_provider" | "noise" => Self::Noise {
                noise: provider_noise(v, "noise")?,
                scale: number(v, "scale")? as f32 as f64,
                states: state_list(&v["states"], p)?,
            },
            "noise_threshold_provider" | "noise_threshold" => Self::Threshold {
                noise: provider_noise(v, "noise")?,
                scale: number(v, "scale")? as f32 as f64,
                threshold: number(v, "threshold")? as f32 as f64,
                chance: number(v, "high_chance")? as f32,
                default: p.intern(&v["default_state"])?,
                low: state_list(&v["low_states"], p)?,
                high: state_list(&v["high_states"], p)?,
            },
            "dual_noise_provider" | "dual_noise" => {
                let range = &v["variety"];
                let (a, b) = if range.is_array() {
                    (
                        range[0].as_i64().ok_or("invalid noise variety")? as i32,
                        range[1].as_i64().ok_or("invalid noise variety")? as i32,
                    )
                } else {
                    (
                        integer(range, "min_inclusive")?,
                        integer(range, "max_inclusive")?,
                    )
                };
                if a < 1 || b < a || b > 64 {
                    return Err("invalid noise variety".into());
                }
                Self::Dual {
                    noise: provider_noise(v, "noise")?,
                    scale: number(v, "scale")? as f32 as f64,
                    slow: provider_noise(v, "slow_noise")?,
                    slow_scale: number(v, "slow_scale")? as f32,
                    variety: [a, b],
                    states: state_list(&v["states"], p)?,
                }
            }
            s => return Err(format!("unsupported state provider {s}")),
        })
    }
    /// Modern providers can inspect the level, and rule providers may decline
    /// a write. getState uses the existing state when getOptionalState is null.
    pub fn state_in(&self, r: &mut Random, pos: Pos, w: &mut Volume) -> Result<StateId> {
        match self {
            Self::RuleBased(_, _) => Ok(self
                .optional_state(r, pos, w)?
                .unwrap_or_else(|| w.get(pos))),
            Self::CopyProperties(source) => {
                let mut state = source.state_in(r, pos, w)?;
                let old = w.get(pos);
                let properties = w.palette.state(old).properties.clone();
                for (key, value) in properties {
                    if w.palette.state(state).properties.contains_key(&key) {
                        if let Ok(updated) = w.palette.with(state, &key, &value) {
                            state = updated;
                        }
                    }
                }
                Ok(state)
            }
            Self::RotatedModern(source, fixed) => {
                // Direction is sampled before the nested provider in vanilla.
                let direction = fixed.unwrap_or_else(|| r.next_bounded(6) as usize);
                let mut state = source.state_in(r, pos, w)?;
                let axis = ["y", "y", "z", "z", "x", "x"][direction];
                for (key, value) in [
                    ("axis", axis),
                    (
                        "facing",
                        ["down", "up", "north", "south", "west", "east"][direction],
                    ),
                ] {
                    if w.palette.state(state).properties.contains_key(key) {
                        if let Ok(updated) = w.palette.with(state, key, value) {
                            state = updated;
                        }
                    }
                }
                Ok(state)
            }
            Self::RandomInt(source, key, values) => {
                let state = source.state_in(r, pos, w)?;
                if w.palette.state(state).properties.contains_key(key) {
                    w.palette.with(state, key, &values.sample(r).to_string())
                } else {
                    Ok(state)
                }
            }
            _ => self.state(r, pos, &mut w.palette),
        }
    }
    pub fn optional_state(
        &self,
        r: &mut Random,
        pos: Pos,
        w: &mut Volume,
    ) -> Result<Option<StateId>> {
        if let Self::RuleBased(rules, fallback) = self {
            for (predicate, provider) in rules {
                if predicate.test(w, pos)? {
                    if let Some(state) = provider.optional_state(r, pos, w)? {
                        return Ok(Some(state));
                    }
                }
            }
            return fallback
                .as_ref()
                .map_or(Ok(None), |provider| provider.optional_state(r, pos, w));
        }
        self.state_in(r, pos, w).map(Some)
    }
    pub fn state(&self, r: &mut Random, pos: Pos, p: &mut Palette) -> Result<StateId> {
        let sample = |noise: &ProviderNoise, scale: f64| {
            noise.sample(
                pos[0] as f64 * scale,
                pos[1] as f64 * scale,
                pos[2] as f64 * scale,
            )
        };
        Ok(match self {
            Self::RuleBased(_, _) | Self::RotatedModern(_, _) | Self::CopyProperties(_) => {
                return Err("state provider requires world context".into())
            }
            Self::Simple(id) => *id,
            Self::Weighted(entries, total) => {
                let mut v = r.next_bounded(*total);
                let mut state = entries[0].0;
                for &(id, w) in entries {
                    v -= w;
                    if v < 0 {
                        state = id;
                        break;
                    }
                }
                state
            }
            Self::Rotated(id) => {
                let axis = ["x", "y", "z"][r.next_bounded(3) as usize];
                if p.state(*id).properties.contains_key("axis") {
                    p.with(*id, "axis", axis)?
                } else {
                    *id
                }
            }
            Self::RandomInt(source, key, values) => {
                let id = source.state(r, pos, p)?;
                if p.state(id).properties.contains_key(key) {
                    let value = values.sample(r);
                    p.with(id, key, &value.to_string())?
                } else {
                    id
                }
            }
            Self::Noise {
                noise,
                scale,
                states,
            } => choose(states, sample(noise, *scale), noise.modern()),
            Self::Threshold {
                noise,
                scale,
                threshold,
                chance,
                default,
                low,
                high,
            } => {
                if sample(noise, *scale) < *threshold {
                    low[r.next_bounded(low.len() as i32) as usize]
                } else if r.next_float() < *chance {
                    high[r.next_bounded(high.len() as i32) as usize]
                } else {
                    *default
                }
            }
            Self::Dual {
                noise,
                scale,
                slow,
                slow_scale,
                variety,
                states,
            } => {
                let slow_at = |pos: Pos| {
                    slow.sample(
                        (pos[0] as f32 * slow_scale) as f64,
                        (pos[1] as f32 * slow_scale) as f64,
                        (pos[2] as f32 * slow_scale) as f64,
                    )
                };
                let count = clamped_lerp(
                    (slow_at(pos) + 1.) / 2.,
                    variety[0] as f64,
                    (variety[1] + 1) as f64,
                ) as usize;
                // Vanilla constructs a list; only its selected entry is needed.
                let index = if noise.modern() {
                    (((1.0f32 + sample(noise, *scale) as f32) / 2.).clamp(0., 0.9999)
                        * count as f32) as i32
                } else {
                    (((1. + sample(noise, *scale)) / 2.).clamp(0., 0.9999) * count as f64) as i32
                };
                choose(
                    states,
                    slow_at([
                        pos[0].wrapping_add(index * 54545),
                        pos[1],
                        pos[2].wrapping_add(index * 34234),
                    ]),
                    slow.modern(),
                )
            }
        })
    }
}
fn choose(states: &[StateId], noise: f64, modern: bool) -> StateId {
    let index = if modern {
        (((1.0f32 + noise as f32) / 2.).clamp(0., 0.9999) * states.len() as f32) as usize
    } else {
        (((1. + noise) / 2.).clamp(0., 0.9999) * states.len() as f64) as usize
    };
    states[index]
}
