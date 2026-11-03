//! Sign proofs for the float graph. Bounds follow the released f32 operators;
//! unknown or non-finite inputs never become a proof through a clamp or min.
use super::{
    modern::{self, Binary, Node, Unary},
    Graph, Id, Mode, Scratch,
};

type Range = (f64, f64);
const UNKNOWN: Range = (f64::NEG_INFINITY, f64::INFINITY);

fn finite(r: Range) -> bool {
    r.0.is_finite() && r.1.is_finite() && r.0 <= r.1
}
fn checked(a: f32, b: f32) -> Range {
    if a.is_finite() && b.is_finite() {
        (a as f64, b as f64)
    } else {
        UNKNOWN
    }
}
// A parent's old-double padding can lie between two floats. Round outward
// before using a child interval as the input of a float operation.
fn floats(r: Range) -> Option<(f32, f32)> {
    if !finite(r) {
        return None;
    }
    let (mut a, mut b) = (r.0 as f32, r.1 as f32);
    if a as f64 > r.0 {
        a = a.next_down();
    }
    if (b as f64) < r.1 {
        b = b.next_up();
    }
    (a.is_finite() && b.is_finite()).then_some((a, b))
}
fn union(a: Range, b: Range) -> Range {
    if finite(a) && finite(b) {
        (a.0.min(b.0), a.1.max(b.1))
    } else {
        UNKNOWN
    }
}
fn unary(op: Unary, r: Range) -> Range {
    let Some((a, b)) = floats(r) else {
        return UNKNOWN;
    };
    match op {
        Unary::Abs => checked(
            if a <= 0. && b >= 0. {
                0.
            } else {
                a.abs().min(b.abs())
            },
            a.abs().max(b.abs()),
        ),
        Unary::Square => checked(
            if a <= 0. && b >= 0. {
                0.
            } else {
                (a * a).min(b * b)
            },
            (a * a).max(b * b),
        ),
        Unary::Cube => checked(a * a * a, b * b * b),
        Unary::Sqrt if a >= 0. => checked((a as f64).sqrt() as f32, (b as f64).sqrt() as f32),
        Unary::Half => checked(
            if a > 0. { a } else { a * 0.5 },
            if b > 0. { b } else { b * 0.5 },
        ),
        Unary::Quarter => checked(
            if a > 0. { a } else { a * 0.25 },
            if b > 0. { b } else { b * 0.25 },
        ),
        Unary::Negate => checked(-b, -a),
        Unary::Reciprocal if a > 0. || b < 0. => checked(1. / b, 1. / a),
        Unary::Squeeze => {
            let f = |v: f32| {
                let v = v.clamp(-1., 1.);
                v / 2. - v * v * v / 24.
            };
            // Endpoint evaluation plus an absolute arithmetic error bound. The
            // mathematical transform is monotone, its rounded subtractions need
            // not be. Near zero remains inconclusive and uses normal sampling.
            let e = (a.clamp(-1., 1.).abs().max(b.clamp(-1., 1.).abs()) as f64
                * f32::EPSILON as f64
                + f32::MIN_POSITIVE as f64)
                * 8.;
            (f(a) as f64 - e, f(b) as f64 + e)
        }
        Unary::Log if a > 0. => checked((a as f64).ln() as f32, (b as f64).ln() as f32),
        Unary::Sin | Unary::Cos => (-1., 1.),
        Unary::Sign => checked(
            if a == 0. { a } else { a.signum() },
            if b == 0. { b } else { b.signum() },
        ),
        _ => UNKNOWN,
    }
}
fn binary(op: Binary, a: Range, b: Range) -> Range {
    if matches!(op, Binary::Mul | Binary::Div) && a == (0., 0.) {
        return (0., 0.);
    }
    let (Some((a, b)), Some((c, d))) = (floats(a), floats(b)) else {
        return UNKNOWN;
    };
    match op {
        Binary::Add => checked(a + c, b + d),
        Binary::Sub => checked(a - d, b - c),
        Binary::Min => checked(a.min(c), b.min(d)),
        Binary::Max => checked(a.max(c), b.max(d)),
        Binary::Mul | Binary::Div => {
            if matches!(op, Binary::Div) && c <= 0. && d >= 0. {
                return UNKNOWN;
            }
            let f = |x, y| {
                if matches!(op, Binary::Mul) {
                    x * y
                } else {
                    x / y
                }
            };
            let values = [f(a, c), f(a, d), f(b, c), f(b, d)];
            if values.iter().any(|v| !v.is_finite()) {
                return UNKNOWN;
            }
            checked(
                values.into_iter().fold(f32::INFINITY, f32::min),
                values.into_iter().fold(f32::NEG_INFINITY, f32::max),
            )
        }
        _ => UNKNOWN,
    }
}
fn clamp(r: Range, a: f32, b: f32) -> Range {
    floats(r).map_or(UNKNOWN, |(lo, hi)| checked(lo.clamp(a, b), hi.clamp(a, b)))
}
fn lerp(t: Range, a: Range, b: Range) -> Range {
    if t == (0., 0.) {
        return a;
    }
    if t == (1., 1.) {
        return b;
    }
    binary(
        Binary::Add,
        a,
        binary(Binary::Mul, t, binary(Binary::Sub, b, a)),
    )
}
fn noise_bound(g: &Graph, noise: usize, multiplier: f64) -> Range {
    let v = g.noises[noise].absolute_bound() * multiplier.abs();
    // The sampler is float, including its volume accumulation. The noise
    // implementation's absolute bound already covers its released layers.
    let v = (v as f32).next_up();
    checked(-v, v)
}
fn interpolation_hull(r: Range, height: i32) -> Range {
    if !finite(r) {
        return UNKNOWN;
    }
    let magnitude = r.0.abs().max(r.1.abs());
    if magnitude >= f32::MAX as f64 / 256. {
        return UNKNOWN;
    }
    // Volume lerp repeatedly adds the rounded Y step. Cover those additions,
    // the X/Z lerps and subnormal absolute errors without changing that order.
    let e =
        (magnitude * f32::EPSILON as f64 + f32::MIN_POSITIVE as f64) * (32. + 4. * height as f64);
    (r.0 - e, r.1 + e)
}

pub(super) fn global(g: &Graph, node: &Node, bounds: &[Range]) -> Range {
    match node {
        Node::Constant(v) => checked(*v, *v),
        // Wrapped i32 differences remain finite after conversion to f32.
        // Three squared differences total less than 2e19, including rounding.
        Node::Distance(_, metric) => {
            if *metric == 1 {
                (0., 2e19)
            } else {
                (0., 1e10)
            }
        }
        // Each island candidate is clamped to [-100, 80] before (value-8)/128.
        Node::End(_) => (-0.84375, 0.5625),
        Node::Unary(op, n) => unary(*op, bounds[*n]),
        Node::Binary(op, a, b) => binary(*op, bounds[*a], bounds[*b]),
        Node::Clamp(n, a, b) => clamp(bounds[*n], *a, *b),
        Node::Gradient {
            from,
            to,
            value,
            factor,
            tiling: 0,
            ..
        } => {
            // A wrapping i32 coordinate subtraction can break monotonicity.
            // Only publish bounds when the complete gradient span is ordinary.
            to.checked_sub(*from).map_or(UNKNOWN, |_| {
                let end = *value + to.wrapping_sub(*from) as f32 * *factor;
                checked(value.min(end), value.max(end))
            })
        }
        Node::Noise {
            noise,
            xz,
            y,
            shifts,
        } if xz.abs() < 1e100
            && y.abs() < 1e100
            && shifts.is_none_or(|ids| ids.iter().all(|id| finite(bounds[*id]))) =>
        {
            noise_bound(g, *noise, 1.)
        }
        Node::Shift(n, _) => noise_bound(g, *n, 4.),
        Node::Blended(noise) => {
            let b = noise.absolute_bound();
            interpolation_hull((-b, b), 0)
        }
        Node::TectonicNoise {
            noise,
            shifts,
            scale,
            multiplier,
            offset,
            ..
        } if scale.is_finite() && shifts.iter().all(|id| finite(bounds[*id])) => {
            let value = binary(
                Binary::Mul,
                noise_bound(g, *noise, 1.),
                checked(*multiplier, *multiplier),
            );
            // Scalar and volume may omit/add offset, so cover both.
            union(value, binary(Binary::Add, value, checked(*offset, *offset)))
        }
        Node::Range(_, _, _, yes, no) => union(bounds[*yes], bounds[*no]),
        Node::Interval(_, _, functions) => functions
            .iter()
            .fold(bounds[functions[0]], |r, id| union(r, bounds[*id])),
        Node::Lerp(t, a, b) => lerp(bounds[*t], bounds[*a], bounds[*b]),
        Node::Cache(n) | Node::Slice(_, _, n) | Node::CoordinateShift(n, _) => bounds[*n],
        Node::Interpolated { input, y, .. } => interpolation_hull(bounds[*input], *y),
        Node::Spline(s) => super::column_plan::spline_bound(s, bounds).map_or(UNKNOWN, |v| (-v, v)),
        _ => UNKNOWN,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        density::ModernVolume,
        terrain::{Substance, Terrain},
    };
    use serde_json::{json, Value};

    fn document() -> Value {
        serde_json::from_str(include_str!(
            "../../tests/fixtures/worldgen263/overworld.json"
        ))
        .unwrap()
    }
    fn gradient(a: i32, b: i32, lo: f64, hi: f64) -> Value {
        json!({"type":"minecraft:gradient","axis":"y","from_coordinate":a,"to_coordinate":b,
            "from_value":lo,"to_value":hi})
    }
    fn interpolated(input: Value, xz: i32, y: i32) -> Value {
        json!({"type":"minecraft:interpolated","input":input,"cell_size_xz":xz,"cell_size_y":y})
    }
    #[test]
    fn scalar_surface_search_rejects_corners_from_a_different_volume() {
        let document = |density: Value| {
            json!({"vss_density_semantics":"float-26.3",
            "settings":{"legacy_random_source":false,
                "noise_router":{"final_density":density}}})
        };
        let original = ModernVolume::blocks([16, 384, 16], [0, -64, 0]);
        let inner = interpolated(gradient(-100, 400, -0.13, 0.87), 6, 3);
        let inner_graph = Graph::from_document(0, &document(inner.clone())).unwrap();
        let inner_root = inner_graph.root("final_density").unwrap();
        let (_, scalar_lattice) =
            modern::interpolation_context(Some(original), [0, 8, 0], [4, 8, 4], Mode::Single);
        let mut scalar = inner_graph.scratch(0, 0, 4, 8).unwrap();
        scalar.set_modern_volume(scalar_lattice);
        let boundary = inner_graph.compute(inner_root, [0, 8, 0], Mode::Slice, &mut scalar) as f32;
        // The nested volume changes repeated-addition rounding by one ULP.
        // This narrow branch makes that difference affect the density sign.
        let threshold = json!({"type":"minecraft:range_choice","input":inner,
            "min_inclusive":boundary as f64,"max_exclusive":boundary.next_up() as f64,
            "when_in_range":1.,"when_out_of_range":-1.});
        let graph = Graph::from_document(0, &document(interpolated(threshold, 4, 8))).unwrap();
        let root = graph.root("final_density").unwrap();
        let mut warmed = graph.scratch(0, 0, 4, 8).unwrap();
        warmed.set_modern_volume(original);
        assert_eq!(
            graph.compute(root, [1, 9, 1], Mode::Block, &mut warmed),
            -1.
        );
        let mut oracle = graph.scratch(0, 0, 4, 8).unwrap();
        oracle.set_modern_volume(original);
        oracle.disable_column_plan();
        let expected = (9..=15)
            .rev()
            .find(|&y| graph.compute(root, [0, y, 0], Mode::Single, &mut oracle) > 0.);
        assert_eq!(expected, Some(11));
        assert_eq!(
            graph.first_above(root, 0, 0, 9, 15, 1, 0., &mut warmed),
            expected
        );
        let range = graph.single_range(root, [0, 9, 0], 15, &mut warmed);
        for y in 9..=15 {
            let value = graph.compute(root, [0, y, 0], Mode::Single, &mut oracle);
            assert!(
                range.0 <= value && value <= range.1,
                "range={range:?} excludes scalar={value} at y={y}"
            );
        }
    }
    fn check_range(doc: &Value, cell_height: i32) {
        let g = Graph::from_document(-917, doc).unwrap();
        let id = g.root("final_density").unwrap();
        for (x, z) in [(-17, 15), (-4, -4), (0, 0), (5, 31)] {
            for base in [-64, -8, 0, 64, 240, 312] {
                for whole in [false, true] {
                    let mut s = g.scratch(x, z, 4, 8).unwrap();
                    let mut oracle = g.scratch(x, z, 4, 8).unwrap();
                    oracle.disable_column_plan();
                    let volume = ModernVolume {
                        origin: [x, base, z],
                        size: [4, cell_height, 4],
                        step: [1; 3],
                    };
                    s.set_modern_volume(volume);
                    oracle.set_modern_volume(volume);
                    let (a, b) = g.spatial_range(
                        id,
                        [x, base, z],
                        base + cell_height - 1,
                        Mode::Cell,
                        &mut s,
                        whole,
                    );
                    let width = if whole { 4 } else { 1 };
                    for xx in x..x + width {
                        for zz in z..z + width {
                            for y in base..base + cell_height {
                                let p = [xx, y, zz];
                                let expected = g.compute(id, p, Mode::Cell, &mut oracle);
                                assert!(
                                    expected.is_nan() || expected >= a && expected <= b,
                                    "{p:?} whole={whole}: {expected} outside {a}..{b}"
                                );
                                assert_eq!(
                                    expected.to_bits(),
                                    g.compute(id, p, Mode::Cell, &mut s).to_bits(),
                                    "proof changed subsequent {p:?}"
                                );
                            }
                        }
                    }
                }
            }
        }
    }
    #[test]
    fn modern_ranges_enclose_released_float_volume_and_nested_grids() {
        let mut d = document();
        check_range(&d, 8);
        for scale in [1., 1e-38, 1e20] {
            d["settings"]["noise_router"]["final_density"] = interpolated(
                json!({"type":"minecraft:add",
                "left":gradient(-64,320,scale,-scale),
                "right":interpolated(json!({"type":"minecraft:noise","noise":"minecraft:temperature",
                    "xz_scale":0.1,"y_scale":0.1}),8,16)}),
                4,
                8,
            );
            check_range(&d, 8);
        }
    }
    #[test]
    fn modern_surface_proofs_skip_air_without_losing_thin_solid_layers() {
        let mut d = document();
        let mut skipped = 0;
        for density in [
            None,
            Some(
                json!({"type":"minecraft:range_choice","input":gradient(-64,320,-64.,320.),
            "min_inclusive":104.,"max_exclusive":105.,"when_in_range":1.,"when_out_of_range":-1.}),
            ),
        ] {
            if let Some(v) = density {
                d["settings"]["noise_router"]["final_density"] = v;
            }
            let t = Terrain::from_document(0, &d).unwrap();
            for (x, z) in [(0, 0), (-17, 31), (100000, -100000), (257, 251)] {
                let mut fast = t.job(x, z, true).unwrap();
                let mut linear = t.job(x, z, true).unwrap();
                let mut y = t.min_y + t.height - 1;
                while y >= t.min_y {
                    if let Some(bottom) = fast.proven_air_bottom([x, y, z]) {
                        for yy in bottom..=y {
                            assert_eq!(
                                linear.block([x, yy, z]),
                                Substance::Air,
                                "false air {x},{yy},{z}"
                            );
                            skipped += 1;
                        }
                        y = bottom - 1;
                    } else {
                        assert_eq!(fast.block([x, y, z]), linear.block([x, y, z]));
                        y -= 1;
                    }
                }
                let mut oracle = t.job(x, z, true).unwrap();
                let expected = (t.min_y..t.min_y + t.height).rev().find_map(|y| {
                    let b = oracle.block([x, y, z]);
                    (b != Substance::Air).then_some((y, b))
                });
                assert_eq!(t.job(x, z, true).unwrap().surface_top(x, z), expected);
            }
        }
        assert!(skipped > 1000, "must actually skip modern density cells");
    }
    #[test]
    fn modern_top_search_preserves_lattice_and_lower_fallback() {
        for lower in [-64, -61, 0, 3, 100] {
            for upper in [0., 96., 128.] {
                for step in [1, 7, 8, 16] {
                    let d = json!({"vss_density_semantics":"float-26.3","settings":{"legacy_random_source":false,
                        "noise_router":{"final_density":{"type":"minecraft:find_top_surface",
                            "upper_bound":upper,"lower_bound":lower,"cell_height":step,
                            "density":{"type":"minecraft:range_choice","input":gradient(-128,320,-128.,320.),
                                "min_inclusive":73.,"max_exclusive":80.,"when_in_range":1.,"when_out_of_range":-1.}}}}});
                    let g = Graph::from_document(0, &d).unwrap();
                    let root = g.root("final_density").unwrap();
                    let super::super::Node::Modern(node) = &g.nodes[root] else {
                        panic!()
                    };
                    let Node::Top { density, .. } = **node else {
                        panic!()
                    };
                    let mut s = g.scratch(0, 0, 4, 8).unwrap();
                    let mut p = (upper as f32 / step as f32).floor() as i32 * step;
                    let mut expected = lower;
                    while p >= lower {
                        if g.compute(density, [0, p, 0], Mode::Single, &mut s) > 0. {
                            expected = p;
                            break;
                        }
                        p -= step;
                    }
                    assert_eq!(
                        g.compute(root, [0, 0, 0], Mode::Single, &mut s),
                        expected as f64,
                        "lower={lower} upper={upper} step={step}"
                    );
                }
            }
        }
    }
    #[test]
    fn nonfinite_second_operand_cannot_be_short_circuited_by_min() {
        let mut d = document();
        d["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:min","left":-1.,
            "right":{"type":"minecraft:sqrt","input":-1.}});
        let g = Graph::from_document(0, &d).unwrap();
        let mut s = g.scratch(0, 0, 4, 8).unwrap();
        let id = g.root("final_density").unwrap();
        assert_eq!(g.column_range(id, [0, 0, 0], 7, &mut s), UNKNOWN);
        assert!(g.compute(id, [0, 0, 0], Mode::Cell, &mut s).is_nan());
    }

    #[test]
    fn narrow_lattice_proof_never_replaces_the_full_cell_envelope() {
        let mut d = document();
        d["settings"]["noise_router"]["final_density"] = interpolated(
            json!({"type":"minecraft:gradient", "axis":"x", "from_coordinate":0,
                "to_coordinate":4, "from_value":-1., "to_value":1.}),
            4,
            8,
        );
        let g = Graph::from_document(0, &d).unwrap();
        let id = g.root("final_density").unwrap();
        let mut s = g.scratch(0, 0, 4, 8).unwrap();
        s.set_modern_volume(ModernVolume::blocks([16, 384, 16], [0, -64, 0]));
        let column = g.spatial_range(id, [0, 64, 0], 71, Mode::Cell, &mut s, false);
        assert!(column.1 < 0.);
        // A previous two-corner query must not populate the eight-corner cache.
        let cell = g.spatial_range(id, [0, 64, 0], 71, Mode::Cell, &mut s, true);
        assert!(cell.0 < 0. && cell.1 > 0.);
        for x in 0..4 {
            for z in 0..4 {
                for y in 64..72 {
                    let value = g.compute(id, [x, y, z], Mode::Cell, &mut s);
                    assert!(value >= cell.0 && value <= cell.1);
                }
            }
        }
    }

    #[test]
    fn zero_weight_nonfinite_corners_still_prevent_a_surface_proof() {
        let mut d = document();
        d["settings"]["noise_router"]["final_density"] = interpolated(
            json!({"type":"minecraft:range_choice",
                "input":{"type":"minecraft:gradient", "axis":"x", "from_coordinate":0,
                    "to_coordinate":4, "from_value":0., "to_value":4.},
                "min_inclusive":4., "max_exclusive":5.,
                "when_in_range":{"type":"minecraft:sqrt", "input":-1.}, "when_out_of_range":-1.}),
            4,
            8,
        );
        let g = Graph::from_document(0, &d).unwrap();
        let id = g.root("final_density").unwrap();
        let mut s = g.scratch(0, 0, 4, 8).unwrap();
        s.set_modern_volume(ModernVolume::blocks([16, 384, 16], [0, -64, 0]));
        assert!(g.compute(id, [0, 64, 0], Mode::Cell, &mut s).is_nan());
        assert_eq!(
            g.spatial_range(id, [0, 64, 0], 71, Mode::Cell, &mut s, false),
            UNKNOWN
        );
    }
}

pub(super) fn spatial(
    g: &Graph,
    id: Id,
    node: &Node,
    bottom: [i32; 3],
    top: i32,
    mode: Mode,
    s: &mut Scratch,
    whole_cell: bool,
) -> Range {
    let range = |n, s: &mut Scratch| g.spatial_range(n, bottom, top, mode, s, whole_cell);
    match node {
        Node::Constant(v) => checked(*v, *v),
        Node::Gradient {
            axis,
            from,
            to,
            tiling: 0,
            ..
        } if to.checked_sub(*from).is_some() => {
            let mut end = bottom;
            end[*axis] = if *axis == 1 {
                top
            } else if whole_cell {
                bottom[*axis] + s.range_width - 1
            } else {
                bottom[*axis]
            };
            let a = g.compute(id, bottom, mode, s) as f32;
            let b = g.compute(id, end, mode, s) as f32;
            checked(a.min(b), a.max(b))
        }
        Node::Unary(op, n) => unary(*op, range(*n, s)),
        Node::Binary(op, a, b) => {
            let a = range(*a, s);
            if matches!(op, Binary::Mul | Binary::Div) && a == (0., 0.) {
                return a;
            }
            // A finite bound on the other operand also excludes NaN, while
            // avoiding cave/noodle evaluation for an already empty interval.
            if (matches!(op, Binary::Min) && a.1 < 0. || matches!(op, Binary::Max) && a.0 > 0.)
                && finite(g.column_plan.bounds[*b])
            {
                return binary(*op, a, g.column_plan.bounds[*b]);
            }
            binary(*op, a, range(*b, s))
        }
        Node::Clamp(n, a, b) => clamp(range(*n, s), *a, *b),
        Node::Range(n, a, b, yes, no) => {
            let n = range(*n, s);
            // Separate branches avoid aliasing mutable scratch in closures.
            if finite(n) && n.0 >= *a as f64 && n.1 < *b as f64 {
                range(*yes, s)
            } else if finite(n) && (n.1 < *a as f64 || n.0 >= *b as f64) {
                range(*no, s)
            } else {
                union(range(*yes, s), range(*no, s))
            }
        }
        Node::Interval(n, thresholds, functions) => {
            let n = range(*n, s);
            let mut result = None;
            for (i, child) in functions.iter().enumerate() {
                if finite(n)
                    && ((i > 0 && n.1 < thresholds[i - 1] as f64)
                        || (i < thresholds.len() && n.0 >= thresholds[i] as f64))
                {
                    continue;
                }
                let r = range(*child, s);
                result = Some(result.map_or(r, |previous| union(previous, r)));
            }
            result.unwrap_or(UNKNOWN)
        }
        Node::Lerp(t, a, b) => {
            let t = range(*t, s);
            if t == (0., 0.) {
                range(*a, s)
            } else if t == (1., 1.) {
                range(*b, s)
            } else {
                lerp(t, range(*a, s), range(*b, s))
            }
        }
        Node::Cache(n) => range(*n, s),
        Node::Slice(1, y, n) => {
            let original = s.modern_volume;
            if modern::is_volume(mode) {
                s.modern_volume = original.map(|v| v.slice(1, *y));
            }
            let r = g.spatial_range(*n, [bottom[0], *y, bottom[2]], *y, mode, s, whole_cell);
            s.modern_volume = original;
            r
        }
        Node::Interpolated { slot, input, xz, y } => {
            let grid = [*xz, *y, *xz];
            let original = s.modern_volume;
            if modern::is_volume(mode)
                && original.is_some_and(|v| {
                    (0..3).all(|i| {
                        (v.step[i] == grid[i] || v.size[i] == 1)
                            && v.origin[i].rem_euclid(grid[i]) == 0
                    })
                })
            {
                return range(*input, s);
            }
            let mut end = [bottom[0], top, bottom[2]];
            if whole_cell {
                end[0] += s.range_width - 1;
                end[2] += s.range_width - 1;
            }
            // A proof may not cross a node's own grid or extrapolate outside
            // its volume. Nested interpolators need not share NoiseChunk cells.
            if (0..3).any(|i| bottom[i].div_euclid(grid[i]) != end[i].div_euclid(grid[i]))
                || (modern::is_volume(mode)
                    && original.is_some_and(|v| {
                        (0..3).any(|i| bottom[i] < v.origin[i] || end[i] > v.max(i))
                    }))
            {
                return UNKNOWN;
            }
            // In a volume the final Y interpolation is repeated addition of
            // one fixed step. It is monotone over this node's single cell.
            if !whole_cell && modern::is_volume(mode) {
                let a = g.compute(id, bottom, mode, s) as f32;
                let b = g.compute(id, end, mode, s) as f32;
                return if a.is_finite() && b.is_finite() {
                    checked(a.min(b), a.max(b))
                } else {
                    UNKNOWN
                };
            }
            if whole_cell && modern::is_volume(mode) {
                let base = std::array::from_fn(|i| bottom[i].div_euclid(grid[i]) * grid[i]);
                let (_, volume) = modern::interpolation_context(original, base, grid, mode);
                let values = modern::interpolation_corners(g, *input, *slot, base, grid, volume, s);
                if values.iter().any(|v| !v.is_finite()) {
                    return UNKNOWN;
                }
                return interpolation_hull(
                    (
                        values.into_iter().fold(f32::INFINITY, f32::min) as f64,
                        values.into_iter().fold(f32::NEG_INFINITY, f32::max) as f64,
                    ),
                    *y,
                );
            }
            let mut probe = bottom;
            if !modern::is_volume(mode) && (0..3).all(|i| probe[i].rem_euclid(grid[i]) == 0) {
                if top == bottom[1] {
                    let v = g.compute(id, bottom, mode, s) as f32;
                    return checked(v, v);
                }
                probe[1] += 1;
            }
            g.compute(id, probe, mode, s);
            let base = std::array::from_fn(|i| bottom[i].div_euclid(grid[i]) * grid[i]);
            let (_, volume) = modern::interpolation_context(original, base, grid, mode);
            // A two-corner query need not populate modern_cells. A prior full
            // cell at this base can belong to another nested volume, whose
            // rounding and range-choice results are not valid for this query.
            s.modern_cells[*slot]
                .filter(|(q, v, _)| *q == base && *v == volume)
                .map_or(UNKNOWN, |(_, _, values)| {
                    if values.iter().any(|v| !v.is_finite()) {
                        return UNKNOWN;
                    }
                    interpolation_hull(
                        (
                            values.into_iter().fold(f32::INFINITY, f32::min) as f64,
                            values.into_iter().fold(f32::NEG_INFINITY, f32::max) as f64,
                        ),
                        *y,
                    )
                })
        }
        Node::Beard => checked(s.beard as f32, s.beard as f32),
        _ => g.column_plan.bounds[id],
    }
}

#[cfg(test)]
mod finite_geometry_bounds {
    use super::*;
    use serde_json::json;
    #[test]
    fn new_finite_bounds_enclose_wrapped_coordinate_extremes() {
        for metric in ["euclidean", "euclidean_squared", "manhattan", "chebyshev"] {
            let doc = json!({"vss_density_semantics":"float-26.3","settings":{"legacy_random_source":false,
                "noise_router":{"final_density":{"type":"minecraft:distance_to_point",
                    "metric":metric,"point":[2147483647,-2147483648,17]}}}});
            let g = Graph::from_document(0, &doc).unwrap();
            let id = g.root("final_density").unwrap();
            let bound = g.column_plan.bounds[id];
            assert!(finite(bound));
            let mut s = g.scratch(0, 0, 4, 8).unwrap();
            for x in [i32::MIN, -1, 0, 1, i32::MAX] {
                for y in [i32::MIN, -1, 0, 1, i32::MAX] {
                    for z in [i32::MIN, -1, 0, 1, i32::MAX] {
                        let v = g.compute(id, [x, y, z], Mode::Raw, &mut s);
                        assert!(v >= bound.0 && v <= bound.1, "{metric}: {v} {bound:?}");
                    }
                }
            }
        }
    }
    #[test]
    fn actual_end_interpolation_input_has_finite_range() {
        let doc: serde_json::Value =
            serde_json::from_str(include_str!("../../tests/fixtures/worldgen263/end.json"))
                .unwrap();
        let g = Graph::from_document(0, &doc).unwrap();
        let mut found = 0;
        for n in &g.nodes {
            if let super::super::Node::Modern(n) = n {
                if let Node::Interpolated { input, .. } = &**n {
                    assert!(
                        finite(g.column_plan.bounds[*input]),
                        "input remains unknown"
                    );
                    found += 1;
                }
            }
        }
        assert!(found > 0);
    }
}
