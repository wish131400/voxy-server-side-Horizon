//! Released Lithostitched FastNoiseConfig and the 2.0 cellular density sampler.
use crate::density::{integer, number, string, Result};
use fastnoise_lite::{
    CellularDistanceFunction, CellularReturnType, FastNoiseLite, FractalType, NoiseType,
};
use serde_json::Value;

pub struct Cellular {
    pub horizontal: i32,
    pub vertical: i32,
    jitter_xz: i32,
    jitter_y: i32,
    pub result: u8,
    salt: i32,
}

impl Cellular {
    pub fn parse(value: &Value) -> Result<Self> {
        let grid = &value["grid_dimensions"];
        let (horizontal, vertical) = if let Some(size) = grid.as_i64() {
            let size = i32::try_from(size).map_err(|_| "invalid cellular grid")?;
            (size, size)
        } else {
            (
                integer(grid, "horizontal")?,
                grid.get("vertical")
                    .map_or(Ok(0), |_| integer(grid, "vertical"))?,
            )
        };
        let jitter = number(value, "jitter")? as f32;
        if horizontal <= 0 || vertical < 0 || !(0.0..=1.0).contains(&jitter) {
            return Err("invalid Lithostitched cellular grid/jitter".into());
        }
        let jitter_xz = (horizontal as f32 * jitter) as i32;
        let jitter_y = (vertical as f32 * jitter) as i32;
        if jitter_xz == i32::MAX || jitter_y == i32::MAX {
            return Err("cellular inclusive jitter bound overflows".into());
        }
        Ok(Self {
            horizontal,
            vertical,
            jitter_xz,
            jitter_y,
            result: match string(value, "return_type")? {
                "cell_value" => 0,
                "distance_1" => 1,
                "distance_2" => 2,
                "distance_2_add" => 3,
                "distance_2_sub" => 4,
                "distance_2_mul" => 5,
                "distance_2_div" => 6,
                kind => return Err(format!("invalid cellular return type {kind}")),
            },
            salt: integer(value, "salt")?,
        })
    }

    pub fn sample(&self, seed: i64, p: [i32; 3]) -> (f32, [[i32; 3]; 2]) {
        let grid = [
            p[0].div_euclid(self.horizontal),
            if self.vertical == 0 {
                0
            } else {
                p[1].div_euclid(self.vertical)
            },
            p[2].div_euclid(self.horizontal),
        ];
        let mut positions = [[0; 3]; 2];
        let mut distances = [f32::MAX; 2];
        let mut nearest_value = f32::MAX;
        let mut random = crate::random::Random::new(0, 2);
        let ys = if self.vertical == 0 { 0..=0 } else { -1..=1 };
        for xo in -1..=1 {
            for yo in ys.clone() {
                for zo in -1..=1 {
                    let cell = [
                        grid[0].wrapping_add(xo),
                        grid[1].wrapping_add(yo),
                        grid[2].wrapping_add(zo),
                    ];
                    random.set_seed(
                        (cell[0] as i64)
                            .wrapping_mul(341873128712)
                            .wrapping_add((cell[1] as i64).wrapping_mul(if self.vertical == 0 {
                                0
                            } else {
                                297845856431
                            }))
                            .wrapping_add((cell[2] as i64).wrapping_mul(132897987541))
                            .wrapping_add(seed)
                            .wrapping_add(self.salt as i64),
                    );
                    let target_x = cell[0]
                        .wrapping_mul(self.horizontal)
                        .wrapping_add(random.next_bounded(self.jitter_xz + 1));
                    let target_y = if self.vertical == 0 {
                        0
                    } else {
                        cell[1]
                            .wrapping_mul(self.vertical)
                            .wrapping_add(random.next_bounded(self.jitter_y + 1))
                    };
                    let target_z = cell[2]
                        .wrapping_mul(self.horizontal)
                        .wrapping_add(random.next_bounded(self.jitter_xz + 1));
                    let cell_value = random.next_float();
                    let dx = target_x.wrapping_sub(p[0]) as f32;
                    let dy = target_y.wrapping_sub(p[1]) as f32;
                    let dz = target_z.wrapping_sub(p[2]) as f32;
                    let squared = if self.vertical == 0 {
                        dx * dx + dz * dz
                    } else {
                        dx * dx + dy * dy + dz * dz
                    };
                    let distance = (squared as f64).sqrt() as f32;
                    if distance < distances[1] {
                        if distance < distances[0] {
                            distances[1] = distances[0];
                            positions[1] = positions[0];
                            distances[0] = distance;
                            positions[0] = [target_x, target_y, target_z];
                            nearest_value = cell_value;
                        } else {
                            distances[1] = distance;
                            positions[1] = [target_x, target_y, target_z];
                        }
                    }
                }
            }
        }
        let [first, second] = distances;
        let value = match self.result {
            0 => nearest_value,
            1 => first,
            2 => second,
            3 => (second + first) * 0.5,
            4 => second - first,
            5 => second * first * 0.5,
            _ => first / second,
        };
        (value, positions)
    }
}

pub fn noise(seed: i64, value: &Value, doc: &Value) -> Result<FastNoiseLite> {
    let config = if let Some(name) = value.as_str() {
        doc["custom_registries"]["lithostitched:fast_noise_config"]
            .get(name)
            .ok_or_else(|| format!("missing Lithostitched FastNoise config {name}"))?
    } else {
        value
    };
    let salt = if config.get("salt").is_some() {
        integer(config, "salt")?
    } else {
        0
    };
    let mut noise = FastNoiseLite::with_seed((seed as i32).wrapping_add(salt));
    noise.set_frequency(Some(number(config, "frequency")? as f32));
    noise.set_fractal_type(Some(FractalType::None));
    match string(config, "type")? {
        "lithostitched:perlin" => noise.set_noise_type(Some(NoiseType::Perlin)),
        "lithostitched:simplex" => {
            noise.set_noise_type(Some(NoiseType::OpenSimplex2S));
            noise.set_fractal_type(Some(
                match config["fractal_type"].as_str().unwrap_or("none") {
                    "none" => FractalType::None,
                    "fbm" => FractalType::FBm,
                    "ridged" => FractalType::Ridged,
                    "ping_pong" => FractalType::PingPong,
                    "domain_warp_progressive" => FractalType::DomainWarpProgressive,
                    "domain_warp_independent" => FractalType::DomainWarpIndependent,
                    kind => return Err(format!("unknown FastNoise fractal type {kind}")),
                },
            ));
            if config.get("octaves").is_some() {
                let octaves = integer(config, "octaves")?;
                if !(0..=128).contains(&octaves) {
                    return Err("FastNoise octave budget exceeded".into());
                }
                noise.set_fractal_octaves(Some(octaves));
            }
            if config.get("lacunarity").is_some() {
                noise.set_fractal_lacunarity(Some(number(config, "lacunarity")? as f32));
            }
            if config.get("gain").is_some() {
                noise.set_fractal_gain(Some(number(config, "gain")? as f32));
            }
        }
        "lithostitched:cellular" => {
            noise.set_noise_type(Some(NoiseType::Cellular));
            noise.set_cellular_distance_function(Some(
                match string(config, "distance_function")? {
                    "euclidean" => CellularDistanceFunction::Euclidean,
                    "euclidean_squared" => CellularDistanceFunction::EuclideanSq,
                    "manhattan" => CellularDistanceFunction::Manhattan,
                    "hybrid" => CellularDistanceFunction::Hybrid,
                    kind => return Err(format!("unknown FastNoise distance function {kind}")),
                },
            ));
            noise.set_cellular_return_type(Some(match string(config, "return_type")? {
                "cell_value" => CellularReturnType::CellValue,
                "distance" => CellularReturnType::Distance,
                "distance_2" => CellularReturnType::Distance2,
                "distance_2_add" => CellularReturnType::Distance2Add,
                "distance_2_sub" => CellularReturnType::Distance2Sub,
                "distance_2_mul" => CellularReturnType::Distance2Mul,
                "distance_2_div" => CellularReturnType::Distance2Div,
                kind => return Err(format!("unknown FastNoise return type {kind}")),
            }));
            let jitter = number(config, "jitter")?;
            if !(-1.0..=1.0).contains(&jitter) {
                return Err("invalid FastNoise jitter".into());
            }
            noise.set_cellular_jitter(Some(jitter as f32));
        }
        kind => return Err(format!("unsupported FastNoise config {kind}")),
    }
    Ok(noise)
}
