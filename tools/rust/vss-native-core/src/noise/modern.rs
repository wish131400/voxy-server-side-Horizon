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
    #[inline(always)]
    fn modern_sample<const VOLUME: bool, const LANES: u8>(
        &self,
        x: f64,
        y: f64,
        z: f64,
        smear: Option<f64>,
    ) -> f32 {
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
        let vals = {
            #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
            if LANES != 0 {
                // The const lane count is selected only by runtime-checked CPU entry points.
                unsafe {
                    if LANES == 8 {
                        gradient_eight::<VOLUME>(hashes, x, rel_y, z)
                    } else {
                        gradient_four::<VOLUME>(hashes, x, rel_y, z)
                    }
                }
            } else {
                std::array::from_fn(|i| {
                    let g = GRAD[(hashes[i] & 15) as usize];
                    let dx = x - (i & 1) as f32;
                    let dy = rel_y - ((i >> 1) & 1) as f32;
                    let dz = z - ((i >> 2) & 1) as f32;
                    if VOLUME {
                        g[0] as f32 * dx + g[2] as f32 * dz + g[1] as f32 * dy
                    } else {
                        g[0] as f32 * dx + g[1] as f32 * dy + g[2] as f32 * dz
                    }
                })
            }
            #[cfg(not(any(target_arch = "x86", target_arch = "x86_64")))]
            {
                std::array::from_fn(|i| {
                    let g = GRAD[(hashes[i] & 15) as usize];
                    let dx = x - (i & 1) as f32;
                    let dy = rel_y - ((i >> 1) & 1) as f32;
                    let dz = z - ((i >> 2) & 1) as f32;
                    if VOLUME {
                        g[0] as f32 * dx + g[2] as f32 * dz + g[1] as f32 * dy
                    } else {
                        g[0] as f32 * dx + g[1] as f32 * dy + g[2] as f32 * dz
                    }
                })
            }
        };
        lerp3(smooth(x), smooth(original_y as f32), smooth(z), vals)
    }
}
impl Noise {
    /// Seeded permutation/coordinate constants shared with Vulkan. FP64 is
    /// retained only for coordinate reduction; amplitudes and gradients are FP32.
    pub(crate) fn vulkan_data(&self, data: &mut Vec<u32>) -> (usize, usize) {
        let offset = data.len();
        let double = |data: &mut Vec<u32>, value: f64| {
            let bits = value.to_bits();
            data.extend([bits as u32, (bits >> 32) as u32]);
        };
        for layer in &self.layers {
            for value in layer.noise.offsets {
                double(data, value);
            }
            double(data, layer.frequency);
            data.push(layer.amplitude.to_bits());
            data.push(u32::from(layer.smear.is_some()));
            double(data, layer.smear.unwrap_or(0.));
            data.extend(layer.noise.p.iter().map(|v| *v as u32));
        }
        (offset, self.layers.len())
    }
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
        #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
        // Eight independent gradients share the same cell coordinates. Keep
        // each lane's original additions, then retain scalar octave sums.
        if std::arch::is_x86_feature_detected!("avx2") {
            // SAFETY: the CPU supports AVX2 before any eight-lane operation.
            return unsafe {
                if volume {
                    self.sample_avx2::<true>(x, y, z)
                } else {
                    self.sample_avx2::<false>(x, y, z)
                }
            };
        }

        #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
        if std::arch::is_x86_feature_detected!("sse4.1") {
            // SAFETY: SSE4.1 includes the SSSE3 shuffle used by gradient_four.
            // ARM and older x86 CPUs retain the generic kernel.
            return unsafe {
                if volume {
                    self.sample_sse41::<true>(x, y, z)
                } else {
                    self.sample_sse41::<false>(x, y, z)
                }
            };
        }
        // Select the arithmetic order once per stack. Specializing both loops
        // removes a mode branch from every gradient without changing FP32 sums.
        if volume {
            self.sample_inner::<true, 0>(x, y, z)
        } else {
            self.sample_inner::<false, 0>(x, y, z)
        }
    }
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    #[target_feature(enable = "sse4.1")]
    unsafe fn sample_sse41<const VOLUME: bool>(&self, x: f64, y: f64, z: f64) -> f32 {
        self.sample_inner::<VOLUME, 4>(x, y, z)
    }
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    #[target_feature(enable = "avx2")]
    unsafe fn sample_avx2<const VOLUME: bool>(&self, x: f64, y: f64, z: f64) -> f32 {
        self.sample_inner::<VOLUME, 8>(x, y, z)
    }
    #[inline(always)]
    fn sample_inner<const VOLUME: bool, const LANES: u8>(&self, x: f64, y: f64, z: f64) -> f32 {
        let mut v = 0f32;
        for layer in &self.layers {
            v += layer.amplitude
                * layer.noise.modern_sample::<VOLUME, LANES>(
                    x * layer.frequency,
                    y * layer.frequency,
                    z * layer.frequency,
                    layer.smear,
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
    pub(crate) fn vulkan_code(&self, name: &str, data: &mut Vec<u32>) -> String {
        let mut code = String::new();
        use std::fmt::Write;
        for (suffix, noise) in [("min", &self.min), ("max", &self.max), ("main", &self.main)] {
            let (offset, count) = noise.vulkan_data(data);
            writeln!(code,"float {name}_{suffix}(dvec3 p,bool volume) {{ precise float v=0.0; for(uint i=0u;i<{count}u;i++) {{uint b={offset}u+i*268u; precise float a=uintBitsToFloat(data[b+8u]); v=v+a*perlin(b,p*readDouble(b+6u),volume);}} return v; }}").unwrap();
        }
        let d = |v| super::super::density::vulkan::d(v);
        writeln!(code,"float {name}(ivec3 p,bool volume) {{ precise float alpha=clamp({name}_main(dvec3(double(p.x)*{},double(p.y)*{},double(p.z)*{}),volume)+0.5,0.0,1.0); dvec3 q=dvec3(double(p.x)*{},double(p.y)*{},double(p.z)*{}); if(alpha==0.0) return {name}_min(q,volume); if(alpha==1.0) return {name}_max(q,volume); precise float a={name}_min(q,volume); precise float b={name}_max(q,volume); return lerpExact(alpha,a,b); }}", d(self.main_xz),d(self.main_y),d(self.main_xz),d(self.xz),d(self.y),d(self.xz)).unwrap();
        code
    }
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
    /// Finite envelope for ordinary blended noise. Extreme coordinates or
    /// degenerate smear parameters keep the original unbounded evaluation.
    pub fn absolute_bound(&self) -> f64 {
        if [self.xz, self.y, self.main_xz, self.main_y]
            .into_iter()
            .any(|v| !v.is_finite() || v.abs() > 1e6)
            || self
                .min
                .layers
                .iter()
                .chain(&self.max.layers)
                .chain(&self.main.layers)
                .any(|layer| {
                    layer.smear.is_some_and(|v| {
                        !v.is_finite() || v.abs() < f64::MIN_POSITIVE || v.abs() > 1e6
                    })
                })
            || !self.main.absolute_bound().is_finite()
        {
            return f64::INFINITY;
        }
        // Cover the octave additions and final float lerp with ample headroom.
        (1. + self.min.absolute_bound().max(self.max.absolute_bound())) * 16.
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

#[cfg(all(test, any(target_arch = "x86", target_arch = "x86_64")))]
mod cpu_dispatch_tests {
    use super::*;
    #[test]
    fn runtime_dispatch_matches_generic_bits_at_noise_boundaries() {
        if !std::arch::is_x86_feature_detected!("sse4.1") {
            return;
        }
        let declaration = serde_json::json!({"base_octave":-4,"octave_count":4,
            "amplitude_modifiers":[1.,0.25,0.,0.5]});
        let mut checked = 0usize;
        for seed in [0, -917, i64::MIN, i64::MAX] {
            let mut random = Random::new(seed, 1);
            let stacks = [
                Noise::from_declaration(&mut random, &declaration).unwrap(),
                Noise::from_declaration_with_legacy(&mut random, &declaration, true).unwrap(),
                Noise::fbm(&mut random, -15, 684.412, 0.9999847412109375),
            ];
            let mut coords = vec![[0., 0., 0.], [-0., -0., -0.]];
            for edge in [
                -33554432.,
                -16777216.,
                -1.,
                0.,
                1.,
                16777216.,
                33554432.,
                2147483647.,
            ] {
                for delta in [-1e-7, 0., 1e-7] {
                    coords.push([edge + delta, 63.5, -edge - delta]);
                    coords.push([17.25, edge + delta, -31.875]);
                }
            }
            for stack in &stacks {
                for layer in &stack.layers {
                    for edge in [-1., 0., 1., 255., 256.] {
                        for delta in [-1e-10, 0., 1e-10] {
                            coords.push(std::array::from_fn(|i| {
                                (edge + delta - layer.noise.offsets[i]) / layer.frequency
                            }));
                        }
                    }
                }
            }
            for _ in 0..2048 {
                coords.push(std::array::from_fn(|_| {
                    (random.next_double() - 0.5) * 60000000.
                }));
            }
            for stack in &stacks {
                for [x, y, z] in coords.iter().copied() {
                    for volume in [false, true] {
                        let expected = if volume {
                            stack.sample_inner::<true, 0>(x, y, z)
                        } else {
                            stack.sample_inner::<false, 0>(x, y, z)
                        };
                        let actual = unsafe {
                            if volume {
                                stack.sample_sse41::<true>(x, y, z)
                            } else {
                                stack.sample_sse41::<false>(x, y, z)
                            }
                        };
                        assert_eq!(
                            actual.to_bits(),
                            expected.to_bits(),
                            "seed={seed} volume={volume} at {x},{y},{z}"
                        );
                        if std::arch::is_x86_feature_detected!("avx2") {
                            // SAFETY: feature checked immediately above.
                            let avx = unsafe {
                                if volume {
                                    stack.sample_avx2::<true>(x, y, z)
                                } else {
                                    stack.sample_avx2::<false>(x, y, z)
                                }
                            };
                            assert_eq!(
                                avx.to_bits(),
                                expected.to_bits(),
                                "AVX2 seed={seed} volume={volume} at {x},{y},{z}"
                            );
                        }
                        assert_eq!(stack.sample(x, y, z, volume).to_bits(), expected.to_bits());
                        checked += 1;
                    }
                }
            }
        }
        println!("generic/SSE4/AVX2 stack coordinates and modes={checked}");
    }
}

// These helpers must inline into the checked octave loop. Calling with four
// lanes requires SSE4.1; eight lanes requires AVX2. Neither path enables FMA.
#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
#[inline(always)]
unsafe fn gradient_four<const VOLUME: bool>(h: [i32; 8], x: f32, y: f32, z: f32) -> [f32; 8] {
    #[cfg(target_arch = "x86")]
    use std::arch::x86::*;
    #[cfg(target_arch = "x86_64")]
    use std::arch::x86_64::*;
    unsafe {
        let tx = _mm_setr_epi8(1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, 0, -1, 0);
        let ty = _mm_setr_epi8(1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1);
        let tz = _mm_setr_epi8(0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 1, 0, -1);
        let dx = _mm_setr_ps(x, x - 1., x, x - 1.);
        let dy = _mm_setr_ps(y, y, y - 1., y - 1.);
        let mut out = [0.; 8];
        for k in 0..2 {
            let j = 4 * k;
            let packed = h[j] | (h[j + 1] << 8) | (h[j + 2] << 16) | (h[j + 3] << 24);
            let idx = _mm_and_si128(_mm_cvtsi32_si128(packed), _mm_set1_epi8(15));
            let gx = _mm_cvtepi32_ps(_mm_cvtepi8_epi32(_mm_shuffle_epi8(tx, idx)));
            let gy = _mm_cvtepi32_ps(_mm_cvtepi8_epi32(_mm_shuffle_epi8(ty, idx)));
            let gz = _mm_cvtepi32_ps(_mm_cvtepi8_epi32(_mm_shuffle_epi8(tz, idx)));
            let vx = _mm_mul_ps(gx, dx);
            let vy = _mm_mul_ps(gy, dy);
            let vz = _mm_mul_ps(gz, _mm_set1_ps(z - k as f32));
            let v = if VOLUME {
                _mm_add_ps(_mm_add_ps(vx, vz), vy)
            } else {
                _mm_add_ps(_mm_add_ps(vx, vy), vz)
            };
            _mm_storeu_ps(out.as_mut_ptr().add(j), v);
        }
        out
    }
}

#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
#[inline(always)]
unsafe fn gradient_eight<const VOLUME: bool>(h: [i32; 8], x: f32, y: f32, z: f32) -> [f32; 8] {
    #[cfg(target_arch = "x86")]
    use std::arch::x86::*;
    #[cfg(target_arch = "x86_64")]
    use std::arch::x86_64::*;
    unsafe {
        let tx = _mm_setr_epi8(1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, 0, -1, 0);
        let ty = _mm_setr_epi8(1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1);
        let tz = _mm_setr_epi8(0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 1, 0, -1);
        let packed = h
            .iter()
            .enumerate()
            .fold(0u64, |a, (i, &v)| a | ((v as u64) << (8 * i)));
        let idx = _mm_and_si128(
            _mm_setr_epi32(packed as i32, (packed >> 32) as i32, 0, 0),
            _mm_set1_epi8(15),
        );
        let gx = _mm256_cvtepi32_ps(_mm256_cvtepi8_epi32(_mm_shuffle_epi8(tx, idx)));
        let gy = _mm256_cvtepi32_ps(_mm256_cvtepi8_epi32(_mm_shuffle_epi8(ty, idx)));
        let gz = _mm256_cvtepi32_ps(_mm256_cvtepi8_epi32(_mm_shuffle_epi8(tz, idx)));
        let dx = _mm256_setr_ps(x, x - 1., x, x - 1., x, x - 1., x, x - 1.);
        let dy = _mm256_setr_ps(y, y, y - 1., y - 1., y, y, y - 1., y - 1.);
        let dz = _mm256_setr_ps(z, z, z, z, z - 1., z - 1., z - 1., z - 1.);
        let vx = _mm256_mul_ps(gx, dx);
        let vy = _mm256_mul_ps(gy, dy);
        let vz = _mm256_mul_ps(gz, dz);
        let v = if VOLUME {
            _mm256_add_ps(_mm256_add_ps(vx, vz), vy)
        } else {
            _mm256_add_ps(_mm256_add_ps(vx, vy), vz)
        };
        let mut out = [0.; 8];
        _mm256_storeu_ps(out.as_mut_ptr(), v);
        out
    }
}

#[cfg(all(test, any(target_arch = "x86", target_arch = "x86_64")))]
mod vector_gradient_tests {
    use super::*;
    #[test]
    fn vector_gradients_match_scalar_bits_in_every_lane() {
        if !std::arch::is_x86_feature_detected!("sse4.1") {
            return;
        }
        let has_avx = std::arch::is_x86_feature_detected!("avx2");
        for hash in 0..256i32 {
            let hashes = std::array::from_fn(|i| (hash + i as i32 * 37) & 255);
            for p in [
                [0., 0., 0.],
                [-0., -0., -0.],
                [0.5, 0.25, 0.75],
                [1., 1., 1.],
                [f32::from_bits(1), -f32::from_bits(1), -1.],
                [f32::from_bits(0x3f7fffff), -7.25, 0.12345679],
            ] {
                for volume in [false, true] {
                    let expected: [f32; 8] = std::array::from_fn(|i| {
                        let g = GRAD[(hashes[i] & 15) as usize];
                        let dx = p[0] - (i & 1) as f32;
                        let dy = p[1] - ((i >> 1) & 1) as f32;
                        let dz = p[2] - ((i >> 2) & 1) as f32;
                        if volume {
                            g[0] as f32 * dx + g[2] as f32 * dz + g[1] as f32 * dy
                        } else {
                            g[0] as f32 * dx + g[1] as f32 * dy + g[2] as f32 * dz
                        }
                    });
                    // SAFETY: SSE4.1 was checked above.
                    let four = unsafe {
                        if volume {
                            gradient_four::<true>(hashes, p[0], p[1], p[2])
                        } else {
                            gradient_four::<false>(hashes, p[0], p[1], p[2])
                        }
                    };
                    assert_eq!(four.map(f32::to_bits), expected.map(f32::to_bits));
                    if has_avx {
                        // SAFETY: AVX2 was checked above.
                        let eight = unsafe {
                            if volume {
                                gradient_eight::<true>(hashes, p[0], p[1], p[2])
                            } else {
                                gradient_eight::<false>(hashes, p[0], p[1], p[2])
                            }
                        };
                        assert_eq!(eight.map(f32::to_bits), expected.map(f32::to_bits));
                    }
                }
            }
        }
    }
}
