//! Column layers and overlays preserve the actual 26.3 FeaturePlacer order.
use super::*;

pub enum Columns {
    Overlay(Vec<Placed>),
    Column {
        layers: Vec<(IntProvider, StateProvider)>,
        direction: Pos,
        allowed: Predicate,
        prioritize_tip: bool,
    },
}

impl Columns {
    pub fn parse(
        kind: &str,
        v: &Value,
        doc: &Value,
        p: &mut Palette,
        depth: usize,
    ) -> Result<Self> {
        if depth > 64 {
            return Err("column feature recursion limit".into());
        }
        match kind {
            "overlay" => {
                let values = v["features"]
                    .as_array()
                    .ok_or("unsupported overlay holder set")?;
                if values.is_empty() {
                    return Err("empty overlay features".into());
                }
                Ok(Self::Overlay(
                    values
                        .iter()
                        .map(|v| Placed::parse(v, doc, p, depth + 1))
                        .collect::<Result<_>>()?,
                ))
            }
            "block_column" => {
                let layers = v["layers"]
                    .as_array()
                    .ok_or("missing block column layers")?
                    .iter()
                    .map(|layer| {
                        Ok((
                            IntProvider::parse(&layer["height"])?,
                            StateProvider::parse_with_registries(&layer["provider"], doc, p)?,
                        ))
                    })
                    .collect::<Result<_>>()?;
                let direction = match string(v, "direction")? {
                    "down" => [0, -1, 0],
                    "up" => [0, 1, 0],
                    "north" => [0, 0, -1],
                    "south" => [0, 0, 1],
                    "west" => [-1, 0, 0],
                    "east" => [1, 0, 0],
                    name => return Err(format!("unsupported block column direction {name}")),
                };
                Ok(Self::Column {
                    layers,
                    direction,
                    allowed: Predicate::parse(&v["allowed_placement"], p, depth + 1)?,
                    prioritize_tip: v["prioritize_tip"]
                        .as_bool()
                        .ok_or("missing column prioritize_tip")?,
                })
            }
            _ => Err(format!("unsupported column feature {kind}")),
        }
    }

    pub fn deterministic(&self) -> Result<()> {
        if let Self::Overlay(features) = self {
            for feature in features {
                feature.deterministic()?;
            }
        }
        Ok(())
    }

    pub fn place(
        &self,
        w: &mut Volume,
        r: &mut Random,
        p: Pos,
        c: &mut PlacementContext<'_>,
    ) -> Result<bool> {
        match self {
            Self::Overlay(features) => {
                // Each nested FeaturePlacer.place creates an empty top-feature
                // context. Preserve the outer context even on a failed placement.
                let outer = c.top_feature;
                c.top_feature = "";
                let result = (|| {
                    let mut placed = false;
                    for feature in features {
                        placed |= feature.place(w, r, p, c)?;
                    }
                    Ok(placed)
                })();
                c.top_feature = outer;
                result
            }
            Self::Column {
                layers,
                direction,
                allowed,
                prioritize_tip,
            } => {
                let mut heights = Vec::with_capacity(layers.len());
                let mut total = 0i32;
                // Every layer height consumes its random sample before any
                // predicate or block-state provider executes.
                for (height, _) in layers {
                    let height = height.sample(r);
                    if height < 0 {
                        return Err("negative block column layer height".into());
                    }
                    total = total
                        .checked_add(height)
                        .ok_or("block column height overflow")?;
                    heights.push(height);
                }
                if total == 0 {
                    return Ok(false);
                }
                let mut probe = offset(p, direction[0], direction[1], direction[2]);
                for length in 0..total {
                    c.charge()?;
                    if !allowed.test(w, probe)? {
                        let mut remove = total - length;
                        if *prioritize_tip {
                            for height in &mut heights {
                                let amount = (*height).min(remove);
                                *height -= amount;
                                remove -= amount;
                                if remove == 0 {
                                    break;
                                }
                            }
                        } else {
                            for height in heights.iter_mut().rev() {
                                let amount = (*height).min(remove);
                                *height -= amount;
                                remove -= amount;
                                if remove == 0 {
                                    break;
                                }
                            }
                        }
                        break;
                    }
                    probe = offset(probe, direction[0], direction[1], direction[2]);
                }
                let mut target = p;
                for ((_, provider), height) in layers.iter().zip(heights) {
                    for _ in 0..height {
                        c.charge()?;
                        let state = provider.state_in(r, target, w)?;
                        w.set(target, state);
                        target = offset(target, direction[0], direction[1], direction[2]);
                    }
                }
                // A nonzero requested height reports success even if an immediate
                // obstruction truncated every layer to zero.
                Ok(true)
            }
        }
    }
}
