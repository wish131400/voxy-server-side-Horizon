//! Modern feature records. Keep vanilla's write order and shared random stream.
use super::*;

mod columns;

pub enum ModernFeature {
    Projected {
        block: StateProvider,
        through: Predicate,
        size: IntProvider,
        max_height: i32,
    },
    Neighbor {
        block: StateProvider,
        accepted: BlockSet,
        replace: Predicate,
        attempts: IntProvider,
        xz: IntProvider,
        y: IntProvider,
    },
    Pillar {
        block: StateProvider,
        replace: Predicate,
        direction: i32,
        chance: f32,
        cap: Option<Box<Placed>>,
    },
    Sequence(Vec<Placed>),
    Columns(columns::Columns),
}

pub enum BlockSet {
    Tag(String),
    Blocks(Vec<String>),
}
impl BlockSet {
    fn parse(v: &Value) -> Result<Self> {
        if let Some(name) = v.as_str() {
            if let Some(tag) = name.strip_prefix('#') {
                return Ok(Self::Tag(qualified(tag)));
            }
            return Ok(Self::Blocks(vec![qualified(name)]));
        }
        Ok(Self::Blocks(
            v.as_array()
                .ok_or("invalid block holder set")?
                .iter()
                .map(|v| {
                    v.as_str()
                        .map(qualified)
                        .ok_or_else(|| "invalid block holder".into())
                })
                .collect::<Result<_>>()?,
        ))
    }
    fn contains(&self, palette: &Palette, state: StateId) -> bool {
        match self {
            Self::Tag(tag) => palette.tag(state, tag),
            Self::Blocks(names) => names.iter().any(|name| palette.is(state, name)),
        }
    }
}
fn qualified(name: &str) -> String {
    if name.contains(':') {
        name.into()
    } else {
        format!("minecraft:{name}")
    }
}

impl ModernFeature {
    pub fn parse(
        kind: &str,
        v: &Value,
        doc: &Value,
        p: &mut Palette,
        depth: usize,
    ) -> Result<Self> {
        if depth > 64 {
            return Err("modern feature recursion limit".into());
        }
        Ok(match kind {
            "projected_random_patchy_square" => {
                let max_height = integer(v, "max_projection_height")?;
                if max_height < 0 {
                    return Err("negative projection height".into());
                }
                Self::Projected {
                    block: StateProvider::parse_with_registries(&v["block"], doc, p)?,
                    through: Predicate::parse(&v["project_through"], p, depth + 1)?,
                    size: IntProvider::parse(&v["size"])?,
                    max_height,
                }
            }
            "random_neighbor_spread" => Self::Neighbor {
                block: StateProvider::parse_with_registries(&v["block"], doc, p)?,
                accepted: BlockSet::parse(&v["accepted_neighbors"])?,
                replace: Predicate::parse(&v["can_replace"], p, depth + 1)?,
                attempts: IntProvider::parse(&v["attempts"])?,
                xz: IntProvider::parse(&v["xz_offset"])?,
                y: IntProvider::parse(&v["y_offset"])?,
            },
            "single_block_pillar" => {
                let direction = match string(v, "direction")? {
                    "up" => 1,
                    "down" => -1,
                    _ => return Err("pillar direction must be vertical".into()),
                };
                let chance = v
                    .get("chance_to_continue")
                    .map(|v| {
                        v.as_f64()
                            .ok_or_else(|| "invalid pillar chance".to_string())
                    })
                    .transpose()?
                    .unwrap_or(1.) as f32;
                if !(0.0..=1.0).contains(&chance) {
                    return Err("invalid pillar chance".into());
                }
                Self::Pillar {
                    block: StateProvider::parse_with_registries(&v["block"], doc, p)?,
                    replace: v
                        .get("can_replace")
                        .map(|v| Predicate::parse(v, p, depth + 1))
                        .transpose()?
                        .unwrap_or(Predicate::True),
                    direction,
                    chance,
                    cap: v
                        .get("cap_feature")
                        .map(|v| Placed::parse(v, doc, p, depth + 1).map(Box::new))
                        .transpose()?,
                }
            }
            "sequence" => {
                let list = v["features"]
                    .as_array()
                    .ok_or("missing sequence features")?;
                if list.is_empty() {
                    return Err("empty sequence features".into());
                }
                Self::Sequence(
                    list.iter()
                        .map(|v| Placed::parse(v, doc, p, depth + 1))
                        .collect::<Result<_>>()?,
                )
            }
            "overlay" | "block_column" => {
                Self::Columns(columns::Columns::parse(kind, v, doc, p, depth)?)
            }
            _ => return Err(format!("unsupported modern feature {kind}")),
        })
    }
    pub fn deterministic(&self) -> Result<()> {
        match self {
            Self::Sequence(features) => {
                for feature in features {
                    feature.deterministic()?;
                }
                Ok(())
            }
            Self::Pillar { cap: Some(cap), .. } => cap.deterministic(),
            Self::Columns(columns) => columns.deterministic(),
            _ => Ok(()),
        }
    }
    pub fn place(
        &self,
        w: &mut Volume,
        r: &mut Random,
        p: Pos,
        c: &mut PlacementContext<'_>,
    ) -> Result<bool> {
        match self {
            Self::Projected {
                block,
                through,
                size,
                max_height,
            } => {
                let size = size.sample(r);
                if !(1..=16).contains(&size) {
                    return Err("projected square size outside codec range".into());
                }
                let bound = size * size + 1;
                for x in -size..=size {
                    for z in -size..=size {
                        c.charge()?;
                        if r.next_bounded(bound) >= bound - x.abs() * z.abs() {
                            continue;
                        }
                        let mut q = offset(p, x, 0, z);
                        let mut remaining = *max_height;
                        // The predicate is checked before decrementing, including a
                        // single step when the codec's allowed maximum is zero.
                        while through.test(w, offset(q, 0, -1, 0))? {
                            c.charge()?;
                            q[1] -= 1;
                            remaining -= 1;
                            if remaining <= 0 {
                                break;
                            }
                        }
                        if let Some(state) = block.optional_state(r, q, w)? {
                            w.set(q, state);
                        }
                    }
                }
                Ok(true)
            }
            Self::Neighbor {
                block,
                accepted,
                replace,
                attempts,
                xz,
                y,
            } => {
                let state = block.state_in(r, p, w)?;
                w.set(p, state);
                let attempts = attempts.sample(r);
                if !(1..=3000).contains(&attempts) {
                    return Err("neighbor attempts outside codec range".into());
                }
                for _ in 0..attempts {
                    c.charge()?;
                    let q = offset(p, xz.sample(r), y.sample(r), xz.sample(r));
                    if !replace.test(w, q)? {
                        continue;
                    }
                    let mut neighbors = 0;
                    for direction in [
                        [0, -1, 0],
                        [0, 1, 0],
                        [0, 0, -1],
                        [0, 0, 1],
                        [-1, 0, 0],
                        [1, 0, 0],
                    ] {
                        let state = w.get(offset(q, direction[0], direction[1], direction[2]));
                        if accepted.contains(&w.palette, state) {
                            neighbors += 1;
                            if neighbors > 1 {
                                break;
                            }
                        }
                    }
                    if neighbors == 1 {
                        let state = block.state_in(r, q, w)?;
                        w.set(q, state);
                    }
                }
                Ok(true)
            }
            Self::Pillar {
                block,
                replace,
                direction,
                chance,
                cap,
            } => {
                let mut q = p;
                while replace.test(w, q)? && r.next_float() < *chance {
                    if q[1] < w.origin[1] || q[1] >= w.origin[1] + w.size[1] as i32 {
                        break;
                    }
                    c.charge()?;
                    let state = block.state_in(r, q, w)?;
                    w.set(q, state);
                    q[1] += direction;
                }
                // Vanilla invokes the cap even when no pillar block was placed.
                q[1] -= direction;
                if let Some(cap) = cap {
                    let outer = c.top_feature;
                    c.top_feature = "";
                    let result = cap.place(w, r, q, c);
                    c.top_feature = outer;
                    result?;
                }
                Ok(true)
            }
            Self::Sequence(features) => {
                let outer = c.top_feature;
                c.top_feature = "";
                let result = (|| {
                    for feature in features {
                        if !feature.place(w, r, p, c)? {
                            return Ok(false);
                        }
                    }
                    Ok(true)
                })();
                c.top_feature = outer;
                result
            }
            Self::Columns(columns) => columns.place(w, r, p, c),
        }
    }
}
