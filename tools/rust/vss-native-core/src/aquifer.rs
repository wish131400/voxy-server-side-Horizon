//! Minecraft 26.3 aquifer inputs and the data-driven fluid-level decision.
//! Centre selection and pressure remain in terrain::Job and share its caches.
use crate::density::{Graph, Id, Result};
use serde_json::Value;

pub const WAY_BELOW_MIN_Y: i32 = -32512;

pub struct Config {
    pub barrier: Id,
    pub floodedness: Id,
    pub spread: Id,
    pub lava: Id,
    pub exclusion: Id,
    pub surface_level: Id,
}
impl Config {
    /// A missing/null modern aquifer config disables aquifers. Legacy callers
    /// retain their older noise-router roots instead of calling this parser.
    pub fn from_document(graph: &mut Graph, document: &Value) -> Result<Option<Self>> {
        let value = &document["settings"]["aquifers"];
        if value.is_null() { return Ok(None); }
        if !value.is_object() { return Err("invalid aquifer configuration".into()); }
        let mut root = |name: &str| graph.add_root(&format!("vss:aquifer/{name}"), &value[name], document);
        Ok(Some(Self {
            barrier: root("barrier")?,
            floodedness: root("fluid_level_floodedness")?,
            spread: root("fluid_level_spread")?,
            lava: root("lava")?,
            exclusion: root("exclusion")?,
            surface_level: root("surface_level")?,
        }))
    }
}

/// Aquifer surface samples retain the four-block X/Z grid, whereas material
/// rules sample their independent chunk surface function at the exact column.
pub fn surface_position(x: i32, z: i32) -> [i32; 3] { [x & !3, 0, z & !3] }

pub fn randomized_level(position: [i32; 3], lowest_surface: i32, spread: f64) -> i32 {
    let cell_y = position[1].div_euclid(40);
    // sampleValue and multiplication are floats in 26.3 before Mth.quantize
    // receives a double. Multiplying promoted doubles changes boundary cells.
    let offset = ((spread as f32 * 10.0_f32) as f64 / 3.0).floor() as i32 * 3;
    lowest_surface.min(cell_y * 40 + 20 + offset)
}

pub fn fluid_level(position: [i32; 3], global_level: i32, lowest_surface: i32,
    submerged_center: bool, exclusion: f64, floodedness: f64, spread: f64) -> i32 {
    if exclusion > 0.0 { return WAY_BELOW_MIN_Y; }
    let distance = lowest_surface.wrapping_add(8).wrapping_sub(position[1]);
    let amount = if submerged_center { 1.0 - (distance as f64 / 64.0).clamp(0.0, 1.0) } else { 0.0 };
    let noise = floodedness.clamp(-1.0, 1.0);
    let partial = noise - (-0.8 + (1.0 - amount) * (0.4 - -0.8));
    let full = noise - (-0.3 + (1.0 - amount) * (0.8 - -0.3));
    if full > 0.0 { global_level }
    else if partial > 0.0 { randomized_level(position, lowest_surface, spread) }
    else { WAY_BELOW_MIN_Y }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn custom_exclusion_controls_fluid_independently_of_climate() {
        let p = [0, -20, 0];
        assert_eq!(fluid_level(p, 63, 75, true, 0.001, 1.0, 0.0), WAY_BELOW_MIN_Y);
        assert_eq!(fluid_level(p, 63, 75, true, 0.0, 1.0, 0.0), 63);
        assert_eq!(fluid_level(p, 63, 75, true, -2.0, 1.0, 0.0), 63);
    }
    #[test]
    fn negative_coordinates_and_float_spread_keep_minecraft_quantization() {
        assert_eq!(surface_position(-1, -5), [-4, 0, -8]);
        // 0.3f * 10f is exactly 3f; promotion before multiplication falls
        // below that bin for this input and chooses the neighbouring level.
        let spread = 0.3_f64 - 1e-10;
        assert_eq!(randomized_level([-1, -1, -1], 100, spread), -17);
        assert_eq!(randomized_level([-1, -41, -1], 100, -spread), -63);
        assert_eq!(randomized_level([0, 0, 0], 10, 1.0), 10);
    }
    #[test]
    fn absent_surface_water_and_thresholds_preserve_dry_caves() {
        assert_eq!(fluid_level([0, 0, 0], 63, 80, false, 0.0, 0.3, 0.0), WAY_BELOW_MIN_Y);
        assert_eq!(fluid_level([0, 0, 0], 63, 80, false, 0.0, 0.6, 0.0), 20);
        assert_eq!(fluid_level([0, 0, 0], 63, 80, false, 0.0, 0.9, 0.0), 63);
    }
}
