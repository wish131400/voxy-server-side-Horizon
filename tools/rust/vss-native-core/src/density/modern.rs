//! The 26.3 graph uses float operations and per-node interpolation grids.
//! It is separate from the double NoiseChunk graph so old version fixtures keep
//! their original rounding, cache ordering and random streams.
use super::{
    integer, number, string, Builder, Graph, Id, Mode, ModernVolume, Result, Scratch, Spline,
};
use crate::{
    noise::{modern::Blended, SimplexNoise},
    random::Random,
};
use serde_json::Value;
#[derive(Clone, Copy)]
pub enum Unary {
    Abs,
    Square,
    Cube,
    Sqrt,
    Half,
    Quarter,
    Reciprocal,
    Negate,
    Squeeze,
    Log,
    Sign,
}
#[derive(Clone, Copy)]
pub enum Binary {
    Add,
    Sub,
    Mul,
    Div,
    Min,
    Max,
    Pow,
}
pub enum Node {
    Constant(f32),
    Unary(Unary, Id),
    Binary(Binary, Id, Id),
    Clamp(Id, f32, f32),
    Gradient {
        axis: usize,
        from: i32,
        to: i32,
        value: f32,
        factor: f32,
        tiling: u8,
    },
    Noise {
        noise: usize,
        xz: f64,
        y: f64,
        shifts: Option<[Id; 3]>,
    },
    Shift(usize, u8),
    Range(Id, f32, f32, Id, Id),
    Interval(Id, Vec<f32>, Vec<Id>),
    Lerp(Id, Id, Id),
    Slice(usize, i32, Id),
    Distance([i32; 3], u8),
    Interpolated {
        slot: usize,
        input: Id,
        xz: i32,
        y: i32,
    },
    Cache(Id),
    Spline(Spline),
    End(Box<SimplexNoise>),
    Blended(Box<Blended>),
    Top {
        density: Id,
        upper: Id,
        lower: i32,
        step: i32,
    },
    Beard,
    Round(u8, Id, Id),
}
fn lerp(t: f32, a: f32, b: f32) -> f32 {
    a + t * (b - a)
}
fn java_min(a: f32, b: f32) -> f32 {
    super::java_min(a as f64, b as f64) as f32
}
fn java_max(a: f32, b: f32) -> f32 {
    super::java_max(a as f64, b as f64) as f32
}
impl Node {
    pub fn uses_y(&self, d: &[bool]) -> bool {
        match self {
            Self::Constant(_) | Self::End(_) => false,
            Self::Gradient { axis, .. } => *axis == 1,
            Self::Noise { y, shifts, .. } => {
                *y != 0. || shifts.is_some_and(|s| s.iter().any(|n| d[*n]))
            }
            Self::Shift(_, kind) => *kind == 0,
            Self::Unary(_, n) | Self::Clamp(n, ..) | Self::Cache(n) | Self::Slice(0 | 2, _, n) => {
                d[*n]
            }
            Self::Slice(1, _, _) | Self::Top { .. } => false,
            Self::Slice(_, _, _) => unreachable!(),
            Self::Binary(_, a, b) | Self::Round(_, a, b) => d[*a] || d[*b],
            Self::Range(a, _, _, b, c) | Self::Lerp(a, b, c) => d[*a] || d[*b] || d[*c],
            Self::Interval(n, _, f) => d[*n] || f.iter().any(|n| d[*n]),
            Self::Spline(sp) => spline_y(sp, d),
            Self::Distance(..) | Self::Interpolated { .. } | Self::Blended(_) | Self::Beard => true,
        }
    }
    pub fn compute(&self, g: &Graph, p: [i32; 3], mode: Mode, s: &mut Scratch) -> f32 {
        let ev = |n, s: &mut Scratch| g.compute(n, p, mode, s) as f32;
        match self {
            Self::Constant(v) => *v,
            Self::Unary(op, n) => {
                let v = ev(*n, s);
                match op {
                    Unary::Abs => v.abs(),
                    Unary::Square => v * v,
                    Unary::Cube => v * v * v,
                    Unary::Sqrt => (v as f64).sqrt() as f32,
                    Unary::Half => {
                        if v > 0. {
                            v
                        } else {
                            v * 0.5
                        }
                    }
                    Unary::Quarter => {
                        if v > 0. {
                            v
                        } else {
                            v * 0.25
                        }
                    }
                    Unary::Reciprocal => 1. / v,
                    Unary::Negate => -v,
                    Unary::Squeeze => {
                        let v = v.clamp(-1., 1.);
                        v / 2. - v * v * v / 24.
                    }
                    Unary::Log => (v as f64).ln() as f32,
                    Unary::Sign => {
                        if v == 0. || v.is_nan() {
                            v
                        } else {
                            v.signum()
                        }
                    }
                }
            }
            Self::Binary(op, a, b) => {
                let a = ev(*a, s);
                match op {
                    Binary::Add => a + ev(*b, s),
                    Binary::Sub => a - ev(*b, s),
                    Binary::Mul => {
                        if a == 0. {
                            0.
                        } else {
                            a * ev(*b, s)
                        }
                    }
                    Binary::Div => {
                        if a == 0. {
                            0.
                        } else {
                            a / ev(*b, s)
                        }
                    }
                    Binary::Min => java_min(a, ev(*b, s)),
                    Binary::Max => java_max(a, ev(*b, s)),
                    Binary::Pow => (a as f64).powf(ev(*b, s) as f64) as f32,
                }
            }
            Self::Clamp(n, a, b) => ev(*n, s).clamp(*a, *b),
            Self::Gradient {
                axis,
                from,
                to,
                value,
                factor,
                tiling,
            } => {
                let range = to.wrapping_sub(*from);
                let coordinate = p[*axis];
                let relative = match tiling {
                    0 => coordinate
                        .clamp((*from).min(*to), (*from).max(*to))
                        .wrapping_sub(*from),
                    1 => java_floor_mod(coordinate.wrapping_sub(*from), range),
                    _ => {
                        let rel = coordinate.wrapping_sub(*from);
                        let tile = java_floor_div(rel, range);
                        let local = rel.wrapping_sub(tile.wrapping_mul(range));
                        if tile & 1 == 0 {
                            local
                        } else {
                            range.wrapping_sub(local)
                        }
                    }
                };
                *value + relative as f32 * *factor
            }
            Self::Noise {
                noise,
                xz,
                y,
                shifts,
            } => {
                let shift = shifts.map_or([0.; 3], |n| n.map(|n| ev(n, s) as f64));
                g.noise_for_volume(
                    *noise,
                    p[0] as f64 * xz + shift[0],
                    p[1] as f64 * y + shift[1],
                    p[2] as f64 * xz + shift[2],
                    shifts.is_none() && is_volume(mode),
                ) as f32
            }
            Self::Shift(n, kind) => {
                let [x, y, z] = match kind {
                    0 => p,
                    1 => [p[0], 0, p[2]],
                    _ => [p[2], p[0], 0],
                };
                g.noise_for_volume(
                    *n,
                    x as f64 * 0.25,
                    y as f64 * 0.25,
                    z as f64 * 0.25,
                    is_volume(mode),
                ) as f32
                    * 4.
            }
            Self::Range(n, a, b, yes, no) => {
                let v = ev(*n, s);
                ev(if v >= *a && v < *b { *yes } else { *no }, s)
            }
            Self::Interval(n, thresholds, functions) => {
                let v = ev(*n, s);
                let i = thresholds
                    .iter()
                    .position(|t| v < *t)
                    .unwrap_or(functions.len() - 1);
                ev(functions[i], s)
            }
            Self::Lerp(alpha, a, b) => {
                let t = ev(*alpha, s);
                if t == 0. {
                    ev(*a, s)
                } else if t == 1. {
                    ev(*b, s)
                } else {
                    lerp(t, ev(*a, s), ev(*b, s))
                }
            }
            Self::Slice(axis, value, n) => {
                let mut q = p;
                q[*axis] = *value;
                let original = s.modern_volume;
                if is_volume(mode) {
                    s.modern_volume = original.map(|v| v.slice(*axis, *value));
                }
                let value = g.compute(*n, q, mode, s) as f32;
                s.modern_volume = original;
                value
            }
            Self::Distance(point, metric) => {
                let [x, y, z] = std::array::from_fn(|i| point[i].wrapping_sub(p[i]) as f32);
                match metric {
                    0 => ((x * x + y * y + z * z) as f64).sqrt() as f32,
                    1 => x * x + y * y + z * z,
                    2 => x.abs() + y.abs() + z.abs(),
                    _ => java_max(java_max(x.abs(), y.abs()), z.abs()),
                }
            }
            Self::Interpolated { slot, input, xz, y } => {
                let local = [
                    p[0].rem_euclid(*xz),
                    p[1].rem_euclid(*y),
                    p[2].rem_euclid(*xz),
                ];
                if local == [0, 0, 0] && !is_volume(mode) {
                    return g.compute(*input, p, Mode::Single, s) as f32;
                }
                let grid = [*xz, *y, *xz];
                let original = s.modern_volume;
                if is_volume(mode)
                    && original.is_some_and(|v| {
                        (0..3).all(|i| {
                            (v.step[i] == grid[i] || v.size[i] == 1)
                                && v.origin[i].rem_euclid(grid[i]) == 0
                        })
                    })
                {
                    return g.compute(*input, p, mode, s) as f32;
                }
                let base = std::array::from_fn(|i| p[i] - local[i]);
                let block_volume = original.map(|v| ModernVolume {
                    size: std::array::from_fn(|i| v.size[i] * v.step[i]),
                    origin: v.origin,
                    step: [1; 3],
                });
                let input_volume = if is_volume(mode) {
                    block_volume.map(|v| {
                        let origin =
                            std::array::from_fn(|i| v.origin[i].div_euclid(grid[i]) * grid[i]);
                        let size = std::array::from_fn(|i| {
                            v.max(i).div_euclid(grid[i]) - v.origin[i].div_euclid(grid[i])
                                + 1
                                + i32::from(v.max(i).rem_euclid(grid[i]) != 0)
                        });
                        ModernVolume {
                            size,
                            origin,
                            step: grid,
                        }
                    })
                } else {
                    None
                }
                .unwrap_or(ModernVolume {
                    size: [2; 3],
                    origin: base,
                    step: grid,
                });
                let values = if let Some((_, _, values)) =
                    s.modern_cells[*slot].filter(|(q, v, _)| *q == base && *v == input_volume)
                {
                    values
                } else {
                    let mut values = [0f32; 8];
                    for (i, v) in values.iter_mut().enumerate() {
                        let q = std::array::from_fn(|axis| {
                            (base[axis] + ((i as i32 >> axis) & 1) * grid[axis])
                                .min(input_volume.max(axis))
                        });
                        let key = (*input, q, input_volume);
                        *v = if let Some(v) = s.modern_corners.get(&key) {
                            *v
                        } else {
                            s.modern_volume = Some(input_volume);
                            let v = g.compute(*input, q, Mode::Slice, s) as f32;
                            s.modern_volume = original;
                            if s.modern_corners.len() < 262144 {
                                s.modern_corners.insert(key, v);
                            }
                            v
                        };
                    }
                    s.modern_cells[*slot] = Some((base, input_volume, values));
                    values
                };
                let fx = local[0] as f32 / *xz as f32;
                let fy = local[1] as f32 / *y as f32;
                let fz = local[2] as f32 / *xz as f32;
                if is_volume(mode) {
                    let fz = local[2] as f32 * (1. / *xz as f32);
                    let fx = local[0] as f32 * (1. / *xz as f32);
                    let a = lerp(
                        fx,
                        lerp(fz, values[0], values[4]),
                        lerp(fz, values[1], values[5]),
                    );
                    let b = lerp(
                        fx,
                        lerp(fz, values[2], values[6]),
                        lerp(fz, values[3], values[7]),
                    );
                    let step = (b - a) * (1. / *y as f32);
                    let first = block_volume.map_or(0, |v| (v.origin[1] - base[1]).max(0));
                    let mut value = a + step * first as f32;
                    for _ in first..local[1] {
                        value += step;
                    }
                    value
                } else {
                    lerp(
                        fz,
                        lerp(
                            fy,
                            lerp(fx, values[0], values[1]),
                            lerp(fx, values[2], values[3]),
                        ),
                        lerp(
                            fy,
                            lerp(fx, values[4], values[5]),
                            lerp(fx, values[6], values[7]),
                        ),
                    )
                }
            }
            Self::Cache(n) => ev(*n, s),
            Self::Spline(sp) => g.spline(sp, p, mode, s),
            Self::End(noise) => end(noise, p[0] / 8, p[2] / 8),
            Self::Blended(noise) => noise.sample(p, is_volume(mode)),
            Self::Top {
                density,
                upper,
                lower,
                step,
            } => {
                let mut q = p;
                q[1] = 0;
                let original = s.modern_volume;
                if is_volume(mode) {
                    s.modern_volume = original.map(|v| v.slice(1, 0));
                }
                let bound = g.compute(
                    *upper,
                    q,
                    if is_volume(mode) { mode } else { Mode::Single },
                    s,
                ) as f32;
                s.modern_volume = original;
                let mut probe = (bound / *step as f32).floor() as i32 * *step;
                while probe > *lower {
                    q[1] = probe;
                    if g.compute(*density, q, Mode::Single, s) > 0. {
                        return probe as f32;
                    }
                    probe = probe.saturating_sub(*step);
                }
                if probe == *lower {
                    q[1] = probe;
                    if g.compute(*density, q, Mode::Single, s) > 0. {
                        return probe as f32;
                    }
                }
                *lower as f32
            }
            Self::Beard => s.beard as f32,
            Self::Round(kind, n, m) => {
                let input = ev(*n, s);
                let multiple = ev(*m, s);
                if multiple == 0. {
                    return input;
                }
                let v = input / multiple;
                let rounded = match kind {
                    0 => (v as f64).floor() as f32,
                    1 => java_round(v),
                    2 => (v as f64).ceil() as f32,
                    _ => v.trunc(),
                };
                rounded * multiple
            }
        }
    }
}
fn java_round(v: f32) -> f32 {
    (v as f64 + 0.5)
        .floor()
        .clamp(i32::MIN as f64, i32::MAX as f64) as i32 as f32
}
pub(super) fn is_volume(m: Mode) -> bool {
    matches!(m, Mode::Block | Mode::Cell | Mode::Slice)
}
fn java_floor_div(a: i32, b: i32) -> i32 {
    let q = a / b;
    let r = a % b;
    if r != 0 && (a ^ b) < 0 {
        q - 1
    } else {
        q
    }
}
fn java_floor_mod(a: i32, b: i32) -> i32 {
    a - java_floor_div(a, b) * b
}
fn spline_y(s: &Spline, d: &[bool]) -> bool {
    match s {
        Spline::Constant(_) => false,
        Spline::Multipoint { coordinate, points } => {
            d[*coordinate] || points.iter().any(|(_, s, _)| spline_y(s, d))
        }
    }
}
fn end(noise: &SimplexNoise, x: i32, z: i32) -> f32 {
    let cx = x / 2;
    let cz = z / 2;
    let sx = x % 2;
    let sz = z % 2;
    let mut out = -100f32;
    for xo in -12..=12 {
        for zo in -12..=12 {
            let xx = (cx + xo) as i64;
            let zz = (cz + zo) as i64;
            if xx * xx + zz * zz <= 4096 || noise.sample2(xx as f64, zz as f64) as f32 >= -0.9 {
                continue;
            }
            let size = (xx.abs() as f32 * 3439. + zz.abs() as f32 * 147.) % 13. + 9.;
            let dx = (sx - xo * 2) as f32;
            let dz = (sz - zo * 2) as f32;
            let next = 100. - ((dx * dx + dz * dz) as f64).sqrt() as f32 * size;
            out = java_max(out, next.clamp(-100., 80.));
        }
    }
    (out - 8.) / 128.
}
fn axis(v: &Value) -> Result<usize> {
    match v.as_str() {
        Some("x") => Ok(0),
        Some("y") => Ok(1),
        Some("z") => Ok(2),
        _ => Err("invalid modern axis".into()),
    }
}
impl Builder<'_> {
    pub(super) fn parse_modern(&mut self, v: &Value) -> Result<Node> {
        if let Some(c) = v.as_f64() {
            return Ok(Node::Constant(c as f32));
        }
        let t = string(v, "type")?;
        let t = t
            .strip_prefix("minecraft:")
            .ok_or_else(|| format!("unsupported modern density {t}"))?;
        Ok(match t {
            "constant" => Node::Constant(number(v, "value")? as f32),
            "blend_alpha" => Node::Constant(1.),
            "blend_offset" => Node::Constant(0.),
            "beardifier" => Node::Beard,
            "blend_density" | "cache" => Node::Cache(self.parse(&v["input"])?),
            "abs" | "square" | "cube" | "sqrt" | "half_negative" | "quarter_negative"
            | "reciprocal" | "negate" | "squeeze" | "log" | "sign" => Node::Unary(
                match t {
                    "abs" => Unary::Abs,
                    "square" => Unary::Square,
                    "cube" => Unary::Cube,
                    "sqrt" => Unary::Sqrt,
                    "half_negative" => Unary::Half,
                    "quarter_negative" => Unary::Quarter,
                    "reciprocal" => Unary::Reciprocal,
                    "negate" => Unary::Negate,
                    "squeeze" => Unary::Squeeze,
                    "log" => Unary::Log,
                    _ => Unary::Sign,
                },
                self.parse(&v["input"])?,
            ),
            "add" | "sub" | "mul" | "div" | "min" | "max" => Node::Binary(
                match t {
                    "add" => Binary::Add,
                    "sub" => Binary::Sub,
                    "mul" => Binary::Mul,
                    "div" => Binary::Div,
                    "min" => Binary::Min,
                    _ => Binary::Max,
                },
                self.parse(&v["left"])?,
                self.parse(&v["right"])?,
            ),
            "pow" => Node::Binary(
                Binary::Pow,
                self.parse(&v["base"])?,
                self.parse(&v["exponent"])?,
            ),
            "floor" | "round" | "ceil" | "truncate" => Node::Round(
                match t {
                    "floor" => 0,
                    "round" => 1,
                    "ceil" => 2,
                    _ => 3,
                },
                self.parse(&v["input"])?,
                self.parse(v.get("multiple").unwrap_or(&Value::from(1)))?,
            ),
            "clamp" => {
                let a = number(v, "min")? as f32;
                let b = number(v, "max")? as f32;
                if a > b {
                    return Err("inverted modern clamp".into());
                }
                Node::Clamp(self.parse(&v["input"])?, a, b)
            }
            "gradient" => {
                let from = integer(v, "from_coordinate")?;
                let to = integer(v, "to_coordinate")?;
                if from == to {
                    return Err("empty modern gradient".into());
                }
                let a = number(v, "from_value")? as f32;
                let b = number(v, "to_value")? as f32;
                let tiling = match v
                    .get("tiling")
                    .and_then(Value::as_str)
                    .unwrap_or("clamp_to_edge")
                {
                    "clamp_to_edge" => 0,
                    "repeat" => 1,
                    "mirrored_repeat" => 2,
                    _ => return Err("invalid tiling mode".into()),
                };
                Node::Gradient {
                    axis: axis(&v["axis"])?,
                    from,
                    to,
                    value: a,
                    factor: (b - a) / (to - from) as f32,
                    tiling,
                }
            }
            "noise" => {
                let n = self.graph.register_modern_noise(&v["noise"], self.doc)?;
                let shifts = if ["shift_x", "shift_y", "shift_z"]
                    .iter()
                    .any(|k| v.get(k).is_some())
                {
                    Some([
                        self.parse(v.get("shift_x").unwrap_or(&Value::from(0)))?,
                        self.parse(v.get("shift_y").unwrap_or(&Value::from(0)))?,
                        self.parse(v.get("shift_z").unwrap_or(&Value::from(0)))?,
                    ])
                } else {
                    None
                };
                Node::Noise {
                    noise: n,
                    xz: number(v, "xz_scale")?,
                    y: number(v, "y_scale")?,
                    shifts,
                }
            }
            "shift_a" | "shift_b" => Node::Shift(
                self.graph.register_modern_noise(&v["noise"], self.doc)?,
                if t == "shift_a" { 1 } else { 2 },
            ),
            "range_choice" => Node::Range(
                self.parse(&v["input"])?,
                number(v, "min_inclusive")? as f32,
                number(v, "max_exclusive")? as f32,
                self.parse(&v["when_in_range"])?,
                self.parse(&v["when_out_of_range"])?,
            ),
            "interval_select" => {
                let n = self.parse(&v["input"])?;
                let thresholds = v["thresholds"]
                    .as_array()
                    .ok_or("missing modern thresholds")?
                    .iter()
                    .map(|v| {
                        v.as_f64()
                            .filter(|v| v.is_finite())
                            .map(|v| v as f32)
                            .ok_or("invalid modern threshold".into())
                    })
                    .collect::<Result<Vec<_>>>()?;
                let functions = v["functions"]
                    .as_array()
                    .ok_or("missing modern intervals")?
                    .iter()
                    .map(|v| self.parse(v))
                    .collect::<Result<Vec<_>>>()?;
                if functions.len() != thresholds.len() + 1
                    || functions.len() < 2
                    || thresholds.windows(2).any(|v| v[0] > v[1])
                {
                    return Err("invalid modern interval arity/order".into());
                }
                Node::Interval(n, thresholds, functions)
            }
            "lerp" => Node::Lerp(
                self.parse(&v["alpha"])?,
                self.parse(&v["first"])?,
                self.parse(&v["second"])?,
            ),
            "slice" => Node::Slice(
                axis(&v["axis"])?,
                integer(v, "coordinate")?,
                self.parse(&v["input"])?,
            ),
            "distance_to_point" => {
                let a = v["point"]
                    .as_array()
                    .filter(|v| v.len() == 3)
                    .ok_or("invalid distance point")?;
                let mut p = [0; 3];
                for i in 0..3 {
                    p[i] = a[i]
                        .as_i64()
                        .and_then(|v| i32::try_from(v).ok())
                        .ok_or("invalid point coordinate")?;
                }
                Node::Distance(
                    p,
                    match string(v, "metric")? {
                        "euclidean" => 0,
                        "euclidean_squared" => 1,
                        "manhattan" => 2,
                        "chebyshev" => 3,
                        _ => return Err("invalid distance metric".into()),
                    },
                )
            }
            "interpolated" => {
                let xz = integer(v, "cell_size_xz")?;
                let y = integer(v, "cell_size_y")?;
                if !(1..=64).contains(&xz) || !(1..=64).contains(&y) {
                    return Err("invalid modern interpolation grid".into());
                }
                let slot = self.graph.interpolator_count;
                self.graph.interpolator_count += 1;
                Node::Interpolated {
                    slot,
                    input: self.parse(&v["input"])?,
                    xz,
                    y,
                }
            }
            "find_top_surface" => {
                let step = integer(v, "cell_height")?;
                if step <= 0 {
                    return Err("invalid surface cell height".into());
                }
                Node::Top {
                    density: self.parse(&v["density"])?,
                    upper: self.parse(&v["upper_bound"])?,
                    lower: integer(v, "lower_bound")?,
                    step,
                }
            }
            "spline" => Node::Spline(self.parse_spline(&v["spline"], 0)?),
            "end_outer_islands" => {
                let mut r = Random::new(self.graph.seed, 0);
                r.consume(17292);
                Node::End(Box::new(SimplexNoise::new(&mut r)))
            }
            "old_blended_noise" => {
                let mut r = if self.graph.legacy {
                    Random::new(self.graph.seed, 0)
                } else {
                    self.graph.random.from_hash("minecraft:terrain")
                };
                Node::Blended(Box::new(Blended::new(&mut r, v)?))
            }
            _ => return Err(format!("unsupported modern density codec {t}")),
        })
    }
}

#[cfg(test)]
mod reference_tests {
    use super::*;
    #[test]
    fn vanilla_263_worldgen_scalar_and_volume_bits() {
        let mut errors = Vec::new();
        let mut total = 0;
        for name in ["overworld", "nether", "end"] {
            let doc: Value = serde_json::from_str(
                &std::fs::read_to_string(
                    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                        .join(format!("tests/fixtures/worldgen263/{name}.json")),
                )
                .unwrap(),
            )
            .unwrap();
            let samples = doc["reference_samples"].as_array().unwrap();
            for seed in [0, 42, -917] {
                let graph = Graph::from_document(seed, &doc).unwrap();
                let mut scratch = graph.scratch(-16, 32, 4, 8).unwrap();
                for sample in samples.iter().filter(|v| v["seed"].as_i64() == Some(seed)) {
                    let root = sample["root"].as_str().unwrap();
                    let p: [i32; 3] =
                        std::array::from_fn(|i| sample["p"][i].as_i64().unwrap() as i32);
                    let (kind, mode) = if sample.get("single").is_some() {
                        ("single", Mode::Single)
                    } else {
                        ("volume", Mode::Block)
                    };
                    let expected = sample[kind].as_i64().unwrap() as u32;
                    let actual = (graph.compute(graph.root(root).unwrap(), p, mode, &mut scratch)
                        as f32)
                        .to_bits();
                    total += 1;
                    if expected != actual && errors.len() < 25 {
                        errors.push(format!("{name} seed{seed} {root} {p:?} {kind}: {:08x} != {actual:08x} ({:?}!={:?})",expected,f32::from_bits(expected),f32::from_bits(actual)));
                    }
                }
                for volume in doc["reference_volumes"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .filter(|v| v["seed"].as_i64() == Some(seed))
                {
                    let root = volume["root"].as_str().unwrap();
                    let origin: [i32; 3] =
                        std::array::from_fn(|i| volume["origin"][i].as_i64().unwrap() as i32);
                    let size: [i32; 3] =
                        std::array::from_fn(|i| volume["size"][i].as_i64().unwrap() as i32);
                    let mut index = 0;
                    let step: [i32; 3] =
                        std::array::from_fn(|i| volume["step"][i].as_i64().unwrap_or(1) as i32);
                    scratch.set_modern_volume(ModernVolume { size, origin, step });
                    for z in 0..size[2] {
                        for x in 0..size[0] {
                            for y in 0..size[1] {
                                let p = [
                                    origin[0] + x * step[0],
                                    origin[1] + y * step[1],
                                    origin[2] + z * step[2],
                                ];
                                let expected = volume["bits"][index].as_i64().unwrap() as u32;
                                index += 1;
                                let actual = (graph.compute(
                                    graph.root(root).unwrap(),
                                    p,
                                    Mode::Block,
                                    &mut scratch,
                                ) as f32)
                                    .to_bits();
                                total += 1;
                                if expected != actual && errors.len() < 25 {
                                    errors.push(format!("{name} seed{seed} {root} {p:?} volume: {expected:08x} != {actual:08x} ({:?}!={:?})",f32::from_bits(expected),f32::from_bits(actual)));
                                }
                            }
                        }
                    }
                }
                let terrain = crate::terrain::Terrain::from_document(seed, &doc).unwrap();
                for column in doc["reference_columns"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .filter(|v| v["seed"].as_i64() == Some(seed))
                {
                    let x = column["p"][0].as_i64().unwrap() as i32;
                    let z = column["p"][1].as_i64().unwrap() as i32;
                    let mut job = terrain.job(x, z, true).unwrap();
                    for (i, block) in column["blocks"].as_array().unwrap().iter().enumerate() {
                        let y = terrain.min_y + i as i32;
                        let actual = job.block([x, y, z]);
                        let expected = if let Some(id) = block.as_u64() {
                            doc["reference_block_names"][id as usize].as_str().unwrap()
                        } else {
                            block.as_str().unwrap()
                        };
                        total += 1;
                        if actual.block_name(&terrain.default_block) != expected
                            && errors.len() < 25
                        {
                            errors.push(format!(
                                "{name} seed{seed} column {x},{y},{z}: {expected} != {actual:?}"
                            ));
                        }
                    }
                }
            }
        }
        assert!(
            errors.is_empty(),
            "{total} reference samples, errors:\n{}",
            errors.join("\n")
        );
    }
}
