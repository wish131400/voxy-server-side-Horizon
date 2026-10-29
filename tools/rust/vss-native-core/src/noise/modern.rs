//! Minecraft 26.3 float noise kernels. Random permutations and coordinates remain
//! double precision; gradient arithmetic, octave amplitudes and sums are floats.
use super::{ImprovedNoise, GRAD};
use crate::{
    density::{integer, number, Result},
    random::Random,
};
use serde_json::Value;
#[derive(Clone)]
struct Layer {
    noise: ImprovedNoise,
    frequency: f64,
    amplitude: f32,
    smear: Option<f64>,
}
#[derive(Clone)]
pub struct Noise {
    layers: Vec<Layer>,
    bound: f64,
}
fn lerp(t: f32, a: f32, b: f32) -> f32 {
    a + t * (b - a)
}
fn smooth(t: f32) -> f32 {
    t * t * t * (t * (t * 6. - 15.) + 10.)
}
fn lerp3(x: f32, y: f32, z: f32, v: [f32; 8]) -> f32 {
    lerp(
        z,
        lerp(y, lerp(x, v[0], v[1]), lerp(x, v[2], v[3])),
        lerp(y, lerp(x, v[4], v[5]), lerp(x, v[6], v[7])),
    )
}
fn wrap(v: f64) -> f64 {
    if (-16777215.999999998..16777215.999999998).contains(&v) {
        v
    } else {
        v - (v / 33554432. + 0.5).floor() * 33554432.
    }
}
impl ImprovedNoise {
    fn modern_sample(&self, x: f64, y: f64, z: f64, smear: Option<f64>, volume: bool) -> f32 {
        let fx = wrap(x) + self.offsets[0];
        let fy = wrap(y) + self.offsets[1];
        let fz = wrap(z) + self.offsets[2];
        let ix = fx.floor() as i32;
        let iy = fy.floor() as i32;
        let iz = fz.floor() as i32;
        let x = (fx - ix as f64) as f32;
        let original_y = fy - iy as f64;
        let z = (fz - iz as f64) as f32;
        let rel_y = if let Some(scale) = smear {
            let limit = if y >= 0. && y < original_y {
                y
            } else {
                original_y
            };
            (original_y - (limit / scale + 1.0e-7f32 as f64).floor() * scale) as f32
        } else {
            original_y as f32
        };
        let a = self.p(ix);
        let b = self.p(ix + 1);
        let aa = self.p(a + iy);
        let ab = self.p(a + iy + 1);
        let ba = self.p(b + iy);
        let bb = self.p(b + iy + 1);
        let hashes = [
            self.p(aa + iz),
            self.p(ba + iz),
            self.p(ab + iz),
            self.p(bb + iz),
            self.p(aa + iz + 1),
            self.p(ba + iz + 1),
            self.p(ab + iz + 1),
            self.p(bb + iz + 1),
        ];
        let vals = std::array::from_fn(|i| {
            let g = GRAD[(hashes[i] & 15) as usize];
            let dx = x - (i & 1) as f32;
            let dy = rel_y - ((i >> 1) & 1) as f32;
            let dz = z - ((i >> 2) & 1) as f32;
            if volume {
                g[0] as f32 * dx + g[2] as f32 * dz + g[1] as f32 * dy
            } else {
                g[0] as f32 * dx + g[1] as f32 * dy + g[2] as f32 * dz
            }
        });
        lerp3(smooth(x), smooth(original_y as f32), smooth(z), vals)
    }
}
impl Noise {
    pub fn from_declaration(random: &mut Random, v: &Value) -> Result<Self> {
        Self::from_declaration_with_legacy(random, v, false)
    }
    pub fn from_declaration_with_legacy(
        random: &mut Random,
        v: &Value,
        legacy_nether: bool,
    ) -> Result<Self> {
        let base = integer(v, "base_octave")?;
        let count = v
            .get("octave_count")
            .map_or(Ok(1), |_| integer(v, "octave_count"))?;
        if !(-32..=32).contains(&base) || !(1..=32).contains(&count) {
            return Err("invalid modern noise octaves".into());
        }
        let base_amplitude = v
            .get("base_amplitude")
            .map_or(Ok(1.), |_| number(v, "base_amplitude"))?;
        let normalize = v.get("normalize").unwrap_or(&Value::Bool(true));
        let enabled = normalize != &Value::Bool(false);
        let legacy = normalize.as_str() == Some("legacy");
        let modifiers = match v.get("amplitude_modifiers") {
            Some(Value::Array(a)) if !a.is_empty() => {
                if a.len() != count as usize {
                    return Err("noise modifiers/count differ".into());
                }
                a.iter()
                    .map(|v| {
                        v.as_f64()
                            .filter(|v| v.is_finite() && *v >= 0.)
                            .ok_or("invalid noise amplitude".into())
                    })
                    .collect::<Result<Vec<_>>>()?
            }
            None | Some(Value::Array(_)) => vec![1.; count as usize],
            _ => return Err("invalid amplitude modifiers".into()),
        };
        let mut frequency = 2f64.powi(base);
        let mut amplitude = base_amplitude;
        if enabled {
            amplitude *= 0.5f64.powi(-(count - 1)) / (0.5f64.powi(-count) - 1.);
        }
        let mut octaves = Vec::new();
        for (i, modifier) in modifiers.iter().enumerate() {
            if *modifier != 0. {
                octaves.push((base + i as i32, frequency, amplitude * modifier));
            }
            frequency *= 2.;
            amplitude *= 0.5;
        }
        let target: f64 = octaves.iter().map(|(_, _, a)| a.abs()).sum();
        let variance: f64 = octaves
            .iter()
            .map(|(_, _, a)| (0.2702247831245211 * a.abs()).powi(2))
            .sum();
        let mut normalization = if variance == 0. {
            0.
        } else {
            (target * (1. / 3.)) / (variance.sqrt() * 2f64.sqrt())
        };
        if legacy && normalization != 0. {
            let first = modifiers.iter().position(|v| *v != 0.).unwrap();
            let last = modifiers.iter().rposition(|v| *v != 0.).unwrap();
            normalization =
                base_amplitude * 0.5 * (1. / 3.) / (0.1 * (1. + 1. / ((last - first + 1) as f64)));
        }
        if legacy_nether {
            let first = Self::legacy_fbm(random, base, &modifiers)?;
            let second = Self::legacy_fbm(random, base, &modifiers)?;
            let factor = (normalization * base_amplitude) as f32;
            let mut layers = Vec::new();
            let mut bound = 0.;
            for (stack, frequency) in [(first, 1.), (second, 1.0181268882175227)] {
                for mut layer in stack {
                    layer.frequency *= frequency;
                    layer.amplitude *= factor;
                    bound += 2. * layer.amplitude.abs() as f64;
                    layers.push(layer);
                }
            }
            return Ok(Self { layers, bound });
        }
        let first = random.positional();
        let second = random.positional();
        let mut layers = Vec::new();
        let mut bound = 0.;
        for (octave, frequency, amplitude) in octaves {
            let key = format!("octave_{octave}");
            let amp = (normalization * amplitude) as f32;
            layers.push(Layer {
                noise: ImprovedNoise::new(&mut first.from_hash(&key)),
                frequency,
                amplitude: amp,
                smear: None,
            });
            layers.push(Layer {
                noise: ImprovedNoise::new(&mut second.from_hash(&key)),
                frequency: frequency * 1.0181268882175227,
                amplitude: amp,
                smear: None,
            });
            bound += 4. * amp.abs() as f64;
        }
        Ok(Self { layers, bound })
    }
    fn legacy_fbm(random: &mut Random, first: i32, amplitudes: &[f64]) -> Result<Vec<Layer>> {
        let count = amplitudes.len();
        let zero = -first;
        let mut levels = vec![None; count];
        let source = ImprovedNoise::new(random);
        if zero >= 0 && (zero as usize) < count && amplitudes[zero as usize] != 0. {
            levels[zero as usize] = Some(source);
        }
        for i in (0..zero).rev() {
            if (i as usize) < count && amplitudes[i as usize] != 0. {
                levels[i as usize] = Some(ImprovedNoise::new(random));
            } else {
                random.consume(262);
            }
        }
        if zero < count as i32 - 1 {
            return Err("positive legacy noise octaves unsupported".into());
        }
        let mut frequency = 2f64.powi(-zero);
        let mut amplitude = 2f64.powi(count as i32 - 1) / (2f64.powi(count as i32) - 1.);
        let mut layers = Vec::new();
        for (i, noise) in levels.into_iter().enumerate() {
            if let Some(noise) = noise {
                layers.push(Layer {
                    noise,
                    frequency,
                    amplitude: (amplitude * amplitudes[i]) as f32,
                    smear: None,
                });
            }
            frequency *= 2.;
            amplitude /= 2.;
        }
        Ok(layers)
    }
    fn fbm(random: &mut Random, first: i32, smear: f64, mut amplitude: f64) -> Self {
        let count = -first + 1;
        amplitude /= 2f64.powi(count) - 1.;
        let mut frequency = 1.;
        let mut layers = Vec::new();
        let mut bound = 0.;
        for _ in (0..count).rev() {
            let scale = smear * frequency;
            let amp = amplitude as f32;
            layers.push(Layer {
                noise: ImprovedNoise::new(random),
                frequency,
                amplitude: amp,
                smear: Some(scale),
            });
            bound += (scale.abs() + 2.) * amp.abs() as f64;
            frequency /= 2.;
            amplitude *= 2.;
        }
        Self { layers, bound }
    }
    pub fn sample(&self, x: f64, y: f64, z: f64, volume: bool) -> f32 {
        let mut v = 0f32;
        for layer in &self.layers {
            v += layer.amplitude
                * layer.noise.modern_sample(
                    x * layer.frequency,
                    y * layer.frequency,
                    z * layer.frequency,
                    layer.smear,
                    volume,
                );
        }
        v
    }
    pub fn absolute_bound(&self) -> f64 {
        self.bound
    }
}
pub struct Blended {
    min: Noise,
    max: Noise,
    main: Noise,
    xz: f64,
    y: f64,
    main_xz: f64,
    main_y: f64,
}
impl Blended {
    pub fn new(random: &mut Random, v: &Value) -> Result<Self> {
        let xz = 684.412 * number(v, "xz_scale")?;
        let y = 684.412 * number(v, "y_scale")?;
        let yf = number(v, "y_factor")?;
        let smear = y * number(v, "smear_scale_multiplier")?;
        Ok(Self {
            min: Noise::fbm(random, -15, smear, 0.9999847412109375),
            max: Noise::fbm(random, -15, smear, 0.9999847412109375),
            main: Noise::fbm(random, -7, smear / yf, 12.75),
            xz,
            y,
            main_xz: xz / number(v, "xz_factor")?,
            main_y: y / yf,
        })
    }
    pub fn sample(&self, p: [i32; 3], volume: bool) -> f32 {
        let [x, y, z] = p.map(|v| v as f64);
        let alpha = (self
            .main
            .sample(x * self.main_xz, y * self.main_y, z * self.main_xz, volume)
            + 0.5)
            .clamp(0., 1.);
        if alpha == 0. {
            return self
                .min
                .sample(x * self.xz, y * self.y, z * self.xz, volume);
        }
        if alpha == 1. {
            return self
                .max
                .sample(x * self.xz, y * self.y, z * self.xz, volume);
        }
        let a = self
            .min
            .sample(x * self.xz, y * self.y, z * self.xz, volume);
        let b = self
            .max
            .sample(x * self.xz, y * self.y, z * self.xz, volume);
        lerp(alpha, a, b)
    }
}
