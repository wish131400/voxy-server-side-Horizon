//! Seeded 26.3 FP32 density export. Shared graph nodes are emitted once per
//! query, avoiding exponential shader function inlining.
use super::{
    modern::{Binary, Node as Modern, Unary},
    Graph, GraphNoise, Id, Node, Result, Spline,
};
use serde_json::{json, Value};
use std::{collections::HashSet, fmt::Write};

fn f(v: f32) -> String {
    format!("uintBitsToFloat({}u)", v.to_bits())
}
pub(crate) fn d(v: f64) -> String {
    let b = v.to_bits();
    format!("packDouble2x32(uvec2({}u,{}u))", b as u32, (b >> 32) as u32)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn seeded_program_reuses_code_and_exports_permutations_without_reseeding() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/worldgen263/overworld.json");
        let doc: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let a = Graph::from_document(42, &doc)
            .unwrap()
            .vulkan_program("final_density")
            .unwrap();
        let b = Graph::from_document(-917, &doc)
            .unwrap()
            .vulkan_program("final_density")
            .unwrap();
        assert_eq!(a["source"], b["source"]);
        assert_ne!(a["data"], b["data"]);
        let data = a["data"].as_array().unwrap();
        assert_eq!(data.len() % 268, 0);
        for layer in data.chunks_exact(268) {
            let mut values: Vec<_> = layer[12..].iter().map(|v| v.as_u64().unwrap()).collect();
            values.sort_unstable();
            assert_eq!(values, (0..256).collect::<Vec<_>>());
        }
    }
    #[test]
    fn legacy_falls_back_and_nested_volume_exports_explicit_context() {
        let legacy =
            json!({"settings":{"legacy_random_source":false,"noise_router":{"final_density":1.0}}});
        assert!(Graph::from_document(0, &legacy)
            .unwrap()
            .vulkan_program("final_density")
            .is_err());
        let wrap = |input| json!({"type":"minecraft:interpolated","cell_size_xz":4,"cell_size_y":8,"input":input});
        let doc = json!({"vss_density_semantics":"float-26.3","settings":{"legacy_random_source":false,"noise_router":{"final_density":wrap(wrap(json!(1.0)))}}});
        assert!(Graph::from_document(0, &doc)
            .unwrap()
            .vulkan_program("final_density")
            .is_ok());
    }
}
struct Emitter<'a> {
    graph: &'a Graph,
    code: String,
    data: Vec<u32>,
    functions: HashSet<Id>,
    nodes: HashSet<Id>,
    noises: HashSet<usize>,
    horizontal: HashSet<Id>,
    fastnoise: bool,
}
impl Graph {
    pub fn vulkan_program(&self, name: &str) -> Result<Value> {
        if !self.modern {
            return Err("Vulkan density requires 26.3 float semantics".into());
        }
        let root = self.root(name)?;
        let mut e = Emitter {
            graph: self,
            code: include_str!("vulkan.glsl").into(),
            data: vec![],
            functions: HashSet::new(),
            nodes: HashSet::new(),
            noises: HashSet::new(),
            horizontal: HashSet::new(),
            fastnoise: false,
        };
        e.code.push_str(include_str!("vulkan_mods.glsl"));
        e.function(root, false)?;
        // One workgroup per column: lanes evaluate the descending lattice in
        // parallel, then lane zero refines precisely the first positive bracket.
        writeln!(e.code,"shared int hit; shared uint invalidMath; void main() {{ if(params.kind==0u) {{uint i=gl_GlobalInvocationID.x; if(i<params.count) {{precise float v=graph{root}(positions[i].xyz,false,emptyVolume()); results[i]=mathValid?floatBitsToUint(v):0x7fc00000u;}} return;}} uint column=gl_WorkGroupID.x; if(column>=params.count) return; uint lane=gl_LocalInvocationID.x; ivec3 p=positions[column].xyz; if(lane==0u) {{hit=params.bottom-1;invalidMath=0u;}} barrier();").unwrap();
        // Display uses the chunk volume and Cell evaluation, not the scalar
        // preview policy. Scan every Y in descending groups: detached thin
        // layers cannot be skipped by a stride or a monotonicity assumption.
        writeln!(e.code,"if(params.kind==2u) {{Volume context=Volume(ivec3(floorDiv(p.x,16)*16,params.bottom,floorDiv(p.z,16)*16),ivec3(16,params.top-params.bottom+1,16),ivec3(1)); for(int base=params.top;base>=params.bottom;base-=256) {{for(int dy=0;dy<8;dy++) {{p.y=base-int(lane)*8-dy; if(p.y<params.bottom)break; if(graph{root}(p,true,context)>0.0) {{atomicMax(hit,p.y);break;}}}} barrier(); if(hit>=params.bottom) break;}} if(!mathValid)atomicOr(invalidMath,1u);barrier();if(lane==0u) results[column]=invalidMath==0u?uint(hit+1):0x80000000u;return;}}").unwrap();
        writeln!(e.code,"int last=(params.top-params.bottom+15)/16; for(int base=0;base<=last;base+=32) {{int index=base+int(lane); if(index<=last) {{p.y=max(params.top-index*16,params.bottom); if(graph{root}(p,false,emptyVolume())>0.0) atomicMax(hit,p.y);}} barrier(); if(hit>=params.bottom) break;}} if(!mathValid)atomicOr(invalidMath,1u);barrier(); if(lane==0u) {{int h=params.bottom; if(hit>=params.bottom) {{int solid=hit; int air=hit==params.top?params.top+1:params.top-((params.top-hit+15)/16-1)*16; while(air-solid>4) {{int mid=solid+(air-solid)/2; p.y=mid; if(graph{root}(p,false,emptyVolume())>0.0) solid=mid; else air=mid;}} h=solid+1;}} results[column]=mathValid&&invalidMath==0u?uint(h):0x80000000u;}} }}").unwrap();
        if e.code.len() > 4 * 1024 * 1024 {
            return Err("Vulkan program size".into());
        }
        if e.data.is_empty() {
            e.data.push(0);
        }
        Ok(
            json!({"schema":1,"source":e.code,"data":e.data,"nodes":e.nodes.len(),"float64_coordinates":true}),
        )
    }
}
impl Emitter<'_> {
    fn function(&mut self, id: Id, interpolated: bool) -> Result<()> {
        if self.functions.contains(&id) {
            return Ok(());
        }
        let mut done = HashSet::new();
        let mut body = String::new();
        self.node(id, interpolated, &mut done, &mut body)?;
        if self.functions.insert(id) {
            writeln!(
                self.code,
                "float graph{id}(ivec3 p,bool volume,Volume context) {{\n{body}return v{id};\n}}"
            )
            .unwrap();
        }
        Ok(())
    }
    fn call(
        &mut self,
        id: Id,
        interp: bool,
        done: &mut HashSet<Id>,
        body: &mut String,
    ) -> Result<String> {
        self.node(id, interp, done, body)?;
        Ok(format!("v{id}"))
    }
    fn noise(&mut self, index: usize) -> Result<String> {
        if self.noises.insert(index) {
            let GraphNoise::Modern(noise) = &self.graph.noises[index] else {
                return Err("legacy GPU noise".into());
            };
            let (offset, count) = noise.vulkan_data(&mut self.data);
            writeln!(self.code,"float noise{index}(dvec3 p,bool volume) {{precise float v=0.0; for(uint i=0u;i<{count}u;i++) {{uint b={offset}u+i*268u; precise float a=uintBitsToFloat(data[b+8u]); v=v+a*perlin(b,p*readDouble(b+6u),volume);}} return v;}}").unwrap();
        }
        Ok(format!("noise{index}"))
    }
    fn spline(
        &mut self,
        sp: &Spline,
        interp: bool,
        done: &mut HashSet<Id>,
        body: &mut String,
    ) -> Result<String> {
        match sp {
            Spline::Constant(v) => Ok(f(*v)),
            Spline::Multipoint { coordinate, points } => {
                let x = self.call(*coordinate, interp, done, body)?;
                let mut values = Vec::new();
                for (_, v, _) in points {
                    values.push(self.spline(v, interp, done, body)?);
                }
                if points.is_empty() {
                    return Err("empty GPU spline".into());
                }
                let name = format!("s{}", body.len());
                writeln!(
                    body,
                    "precise float {name}; {{precise float x={x}; int next=0; int remaining={};",
                    points.len()
                )
                .unwrap();
                let locations = points
                    .iter()
                    .map(|(v, _, _)| f(*v))
                    .collect::<Vec<_>>()
                    .join(",");
                writeln!(body,"const float loc[{}]=float[]({locations}); while(remaining>0) {{int h=remaining/2; int mid=next+h; if(x<loc[mid]) remaining=h; else {{next=mid+1; remaining-=h+1;}}}}",points.len()).unwrap();
                // Endpoint ordering is preserved, including duplicate knots.
                for (i, cond) in [
                    (0, "next==0".to_string()),
                    (points.len() - 1, format!("next=={}", points.len())),
                ] {
                    let (loc, _, derivative) = points[i];
                    let value = if derivative == 0. {
                        values[i].clone()
                    } else {
                        format!("{}+{}*(x-{})", values[i], f(derivative), f(loc))
                    };
                    writeln!(body, "if({cond}) {name}={value}; else").unwrap();
                }
                for i in 1..points.len() {
                    let (a, _, ad) = points[i - 1];
                    let (b, _, bd) = points[i];
                    writeln!(body,"if(next=={i}) {{precise float t=(x-{})/({}-{}); precise float a={}; precise float b={}; precise float d0={}*({}-{})-(b-a); precise float d1=-{}*({}-{})+(b-a); {name}=(a+t*(b-a))+t*(1.0-t)*(d0+t*(d1-d0));}} else",f(a),f(b),f(a),values[i-1],values[i],f(ad),f(b),f(a),f(bd),f(b),f(a)).unwrap();
                }
                writeln!(body, "{name}={}; }}", values.last().unwrap()).unwrap();
                Ok(name)
            }
        }
    }
    fn node(
        &mut self,
        id: Id,
        interp: bool,
        done: &mut HashSet<Id>,
        body: &mut String,
    ) -> Result<()> {
        if done.contains(&id) {
            return Ok(());
        }
        let Node::Modern(n) = &self.graph.nodes[id] else {
            return Err("non-float GPU node".into());
        };
        let expression = match &**n {
            Modern::Constant(v) => f(*v),
            Modern::Cache(n) => self.call(*n, interp, done, body)?,
            Modern::Unary(op, n) => {
                let v = self.call(*n, interp, done, body)?;
                match op {
                    Unary::Abs => format!("abs({v})"),
                    Unary::Square => format!("square({v})"),
                    Unary::Cube => format!("cube({v})"),
                    Unary::Half => format!("negativeScale({v},0.5)"),
                    Unary::Quarter => format!("negativeScale({v},0.25)"),
                    Unary::Reciprocal => format!("1.0/({v})"),
                    Unary::Negate => format!("-({v})"),
                    Unary::Squeeze => format!("squeeze({v})"),
                    Unary::Sqrt => format!("float(sqrt(double({v})))"),
                    Unary::Sign => format!("signExact({v})"),
                    Unary::Sin => format!("sinExact({v},false)"),
                    Unary::Cos => format!("sinExact({v},true)"),
                    Unary::Log => format!("float(logExact(double({v})))"),
                }
            }
            Modern::Binary(op, a, b) => {
                let a = self.call(*a, interp, done, body)?;
                let b = self.call(*b, interp, done, body)?;
                match op {
                    Binary::Add => format!("{a}+{b}"),
                    Binary::Sub => format!("{a}-{b}"),
                    Binary::Mul => format!("{a}==0.0?0.0:{a}*{b}"),
                    Binary::Div => format!("{a}==0.0?0.0:{a}/{b}"),
                    Binary::Min => format!("jmin({a},{b})"),
                    Binary::Max => format!("jmax({a},{b})"),
                    Binary::Pow => format!("powExact({a},{b})"),
                }
            }
            Modern::Clamp(n, a, b) => format!(
                "clamp({},{},{})",
                self.call(*n, interp, done, body)?,
                f(*a),
                f(*b)
            ),
            Modern::Gradient {
                axis,
                from,
                to,
                value,
                factor,
                tiling,
            } => {
                format!(
                    "{}+float(gradientOffset(p[{}],{from},{to},{tiling}))*{}",
                    f(*value),
                    axis,
                    f(*factor)
                )
            }
            Modern::Noise {
                noise,
                xz,
                y,
                shifts,
            } => {
                let name = self.noise(*noise)?;
                let shift = if let Some(ids) = shifts {
                    let mut s = Vec::new();
                    for n in ids {
                        s.push(self.call(*n, interp, done, body)?);
                    }
                    format!("dvec3({},{},{})", s[0], s[1], s[2])
                } else {
                    "dvec3(0.0)".into()
                };
                format!(
                    "{name}(dvec3(double(p.x)*{},double(p.y)*{},double(p.z)*{})+{shift},{})",
                    d(*xz),
                    d(*y),
                    d(*xz),
                    if shifts.is_some() { "false" } else { "volume" }
                )
            }
            Modern::Shift(n, kind) => {
                let name = self.noise(*n)?;
                let p = match kind {
                    0 => "dvec3(p)",
                    1 => "dvec3(p.x,0,p.z)",
                    _ => "dvec3(p.z,p.x,0)",
                };
                format!("{name}({p}*0.25,volume)*4.0")
            }
            Modern::TectonicNoise {
                noise,
                shifts,
                scale,
                multiplier,
                offset,
                smoother,
            } => {
                let noise = self.noise(*noise)?;
                let x = self.call(shifts[0], interp, done, body)?;
                let z = self.call(shifts[1], interp, done, body)?;
                let scale = f(*scale);
                let xz = if *smoother {
                    format!(
                        "dvec2(double(p.x)+double({x}),double(p.z)+double({z}))*double({scale})"
                    )
                } else {
                    format!("dvec2(double(p.x),double(p.z))*double({scale})+dvec2({x},{z})")
                };
                // Explicit FP32 temporaries prevent the mixed-precision conditional
                // from promoting scalar coordinate arithmetic to FP64.
                writeln!(body,"precise float tx{id}=float(p.x)*{scale};precise float tz{id}=float(p.z)*{scale};tx{id}=tx{id}+{x};tz{id}=tz{id}+{z};precise dvec2 tc{id}=volume?{xz}:dvec2(double(tx{id}),double(tz{id}));").unwrap();
                format!(
                    "({noise}(dvec3(tc{id}.x,0.0LF,tc{id}.y),false)*{})+({}?0.0:{})",
                    f(*multiplier),
                    if *smoother { "volume" } else { "false" },
                    f(*offset)
                )
            }
            Modern::CoordinateShift(input, shifts) => {
                let mut values = Vec::new();
                for n in shifts {
                    values.push(self.call(*n, interp, done, body)?);
                }
                self.function(*input, false)?;
                let shifted=format!("ivec3(volume?ivec3(saturateInt(double(p.x)+double({})),saturateInt(double(float(p.y)+{})),saturateInt(double(p.z)+double({}))):p+ivec3(saturateInt(double({})),saturateInt(double({})),saturateInt(double({}))))",values[0],values[1],values[2],values[0],values[1],values[2]);
                format!("graph{input}({shifted},false,context)")
            }
            Modern::FastNoise(noise, xz, y, shifts) => {
                use fastnoise_lite::{
                    CellularDistanceFunction as Distance, CellularReturnType as Return,
                    FractalType, NoiseType,
                };
                if !self.fastnoise {
                    self.code.push_str(include_str!("vulkan_fastnoise.glsl"));
                    self.fastnoise = true;
                }
                let kind = match noise.noise_type {
                    NoiseType::Perlin => 0,
                    NoiseType::OpenSimplex2S => 1,
                    NoiseType::Cellular => 2,
                    _ => return Err("unsupported Vulkan FastNoise algorithm".into()),
                };
                let fractal = match noise.fractal_type {
                    FractalType::FBm => 1,
                    FractalType::Ridged => 2,
                    FractalType::PingPong => 3,
                    _ => 0,
                };
                let distance = match noise.cellular_distance_function {
                    Distance::Euclidean => 0,
                    Distance::EuclideanSq => 1,
                    Distance::Manhattan => 2,
                    _ => 3,
                };
                let result = match noise.cellular_return_type {
                    Return::CellValue => 0,
                    Return::Distance => 1,
                    Return::Distance2 => 2,
                    Return::Distance2Add => 3,
                    Return::Distance2Sub => 4,
                    Return::Distance2Mul => 5,
                    _ => 6,
                };
                let mut amp = noise.gain.abs();
                let mut sum = 1.0f32;
                for _ in 1..noise.octaves {
                    sum += amp;
                    amp *= noise.gain.abs();
                }
                let offset = self.data.len();
                self.data.extend([
                    noise.seed as u32,
                    noise.frequency.to_bits(),
                    kind,
                    fractal,
                    noise.octaves as u32,
                    noise.lacunarity.to_bits(),
                    noise.gain.to_bits(),
                    noise.weighted_strength.to_bits(),
                    noise.ping_pong_strength.to_bits(),
                    (1.0 / sum).to_bits(),
                    distance,
                    result,
                    noise.cellular_jitter_modifier.to_bits(),
                ]);
                let shift = if let Some(ids) = shifts {
                    let mut s = Vec::new();
                    for n in ids {
                        s.push(self.call(*n, interp, done, body)?);
                    }
                    format!("dvec3({},{},{})", s[0], s[1], s[2])
                } else {
                    "dvec3(0.0)".into()
                };
                let value=format!("fastNoise({offset}u,dvec3(double(p.x)*{},double(p.y)*{},double(p.z)*{})+{shift})",d(*xz),d(*y),d(*xz));
                if shifts.is_none() {
                    format!("volume?0.0+{value}:{value}")
                } else {
                    value
                }
            }
            Modern::Cellular(noise, condition) => {
                let offset = self.data.len();
                self.data.extend(noise.vulkan_data(self.graph.seed));
                let name = format!("cell{id}");
                let mut code=format!("float {name}(ivec3 p) {{ivec3 nearest,second;precise float value=cellNoise({offset}u,p,nearest,second);");
                if let Some((input, min, max, fallback)) = condition.filter(|_| noise.result != 0) {
                    self.function(input, false)?;
                    for (index, pos) in [(0, "nearest"), (1, "second")] {
                        if (index == 0 && noise.result == 2) || (index == 1 && noise.result == 1) {
                            continue;
                        }
                        write!(code,"{{precise float v=graph{input}({pos},false,emptyVolume());if(floatCompare(v,{})<0||floatCompare(v,{})>0)value={};}}",f(min),f(max),f(fallback)).unwrap();
                    }
                }
                code.push_str("return value;}\n");
                if !self.nodes.contains(&id) {
                    self.code.push_str(&code);
                }
                format!("{name}(p)")
            }
            Modern::Range(n, a, b, yes, no) => {
                let n = self.call(*n, interp, done, body)?;
                let yes = self.call(*yes, interp, done, body)?;
                let no = self.call(*no, interp, done, body)?;
                format!("{n}>={}&&{n}<{}?{yes}:{no}", f(*a), f(*b))
            }
            Modern::Interval(n, thresholds, functions) => {
                let n = self.call(*n, interp, done, body)?;
                let mut value = self.call(
                    *functions.last().ok_or("empty GPU interval")?,
                    interp,
                    done,
                    body,
                )?;
                for (t, v) in thresholds.iter().zip(functions).rev() {
                    value = format!(
                        "{n}<{}?{}:({value})",
                        f(*t),
                        self.call(*v, interp, done, body)?
                    );
                }
                value
            }
            Modern::Lerp(t, a, b) => format!(
                "mixExact({},{},{})",
                self.call(*t, interp, done, body)?,
                self.call(*a, interp, done, body)?,
                self.call(*b, interp, done, body)?
            ),
            Modern::Slice(axis, value, n) => {
                self.function(*n, interp)?;
                let mut p = ["p.x".to_string(), "p.y".into(), "p.z".into()];
                p[*axis] = value.to_string();
                format!(
                    "graph{n}(ivec3({},{},{}),volume,sliceVolume(context,{},{}))",
                    p[0], p[1], p[2], axis, value
                )
            }
            Modern::Interpolated { input, xz, y, .. } => {
                self.function(*input, true)?;
                // Each lane walks adjacent Y values. Reuse the same eight
                // corners while it stays in one interpolation cell; include
                // the complete nested volume in the key to preserve mod codecs.
                if self.nodes.insert(id) {
                    writeln!(self.code,"bool cellValid{id}=false; ivec3 cellBase{id},cellOrigin{id},cellSize{id},cellStep{id}; float cellValues{id}[8];").unwrap();
                }
                writeln!(body,"precise float v{id}; {{ivec3 grid=ivec3({xz},{y},{xz}); ivec3 local=p-floorDiv3(p,grid)*grid; if(all(equal(local,ivec3(0)))&&!volume) v{id}=graph{input}(p,false,context); else if(volume&&all(bvec3(context.step.x==grid.x||context.size.x==1,context.step.y==grid.y||context.size.y==1,context.step.z==grid.z||context.size.z==1))&&all(equal(context.origin-floorDiv3(context.origin,grid)*grid,ivec3(0)))) v{id}=graph{input}(p,true,context); else {{ivec3 base=p-local; Volume blockContext=Volume(context.origin,context.size*context.step,ivec3(1)); Volume next=Volume(base,ivec3(2),grid); if(volume) {{next.origin=floorDiv3(blockContext.origin,grid)*grid; ivec3 maximum=volumeMax(blockContext);next.size=floorDiv3(maximum,grid)-floorDiv3(blockContext.origin,grid)+ivec3(1)+ivec3(notEqual(maximum-floorDiv3(maximum,grid)*grid,ivec3(0)));}} precise float v[8]; bool reuse=params.kind==2u&&cellValid{id}&&all(equal(base,cellBase{id}))&&all(equal(next.origin,cellOrigin{id}))&&all(equal(next.size,cellSize{id}))&&all(equal(next.step,cellStep{id})); if(reuse) {{for(int i=0;i<8;i++)v[i]=cellValues{id}[i];}} else {{for(int i=0;i<8;i++) v[i]=graph{input}(min(base+ivec3(i&1,(i>>1)&1,(i>>2)&1)*grid,volumeMax(next)),true,next); if(params.kind==2u) {{for(int i=0;i<8;i++)cellValues{id}[i]=v[i];cellBase{id}=base;cellOrigin{id}=next.origin;cellSize{id}=next.size;cellStep{id}=next.step;cellValid{id}=true;}}}} precise float x=float(local.x)/float(grid.x); precise float yy=float(local.y)/float(grid.y); precise float z=float(local.z)/float(grid.z); if(!volume) v{id}=lerp3(x,yy,z,v); else {{x=float(local.x)*(1.0/float(grid.x));z=float(local.z)*(1.0/float(grid.z));precise float a=lerpExact(x,lerpExact(z,v[0],v[4]),lerpExact(z,v[1],v[5]));precise float b=lerpExact(x,lerpExact(z,v[2],v[6]),lerpExact(z,v[3],v[7]));precise float step=(b-a)*(1.0/float(grid.y));int first=max(0,blockContext.origin.y-base.y);precise float value=a+step*float(first);for(int n=first;n<local.y;n++) value+=step;v{id}=value;}} }} }}").unwrap();
                done.insert(id);
                self.nodes.insert(id);
                return Ok(());
            }
            Modern::Spline(sp) => self.spline(sp, interp, done, body)?,
            Modern::Blended(n) => {
                let name = format!("blended{id}");
                if !self.nodes.contains(&id) {
                    self.code.push_str(&n.vulkan_code(&name, &mut self.data));
                }
                format!("{name}(p,volume)")
            }
            Modern::Distance(point, metric) => format!(
                "distanceExact(ivec3({},{},{})-p,{metric})",
                point[0], point[1], point[2]
            ),
            Modern::Round(kind, n, m) => format!(
                "roundExact({}, {}, {kind})",
                self.call(*n, interp, done, body)?,
                self.call(*m, interp, done, body)?
            ),
            Modern::End(noise) => {
                let name = format!("end{id}");
                if !self.nodes.contains(&id) {
                    let offset = self.data.len();
                    self.data.extend(noise.vulkan_permutation());
                    writeln!(
                        self.code,
                        "float {name}(ivec3 p) {{return endIslands({offset}u,p.x/8,p.z/8);}}"
                    )
                    .unwrap();
                }
                format!("{name}(p)")
            }
            Modern::Top {
                density,
                upper,
                lower,
                step,
            } => {
                self.function(*density, false)?;
                self.function(*upper, false)?;
                writeln!(body,"precise float v{id}={lower}.0; {{ivec3 q=ivec3(p.x,0,p.z);precise float bound=graph{upper}(q,volume,sliceVolume(context,1,0));int probe=saturateInt(floor(double(bound/{step}.0)))*{step};while(probe>={lower}) {{q.y=probe;if(graph{density}(q,false,context)>0.0) {{v{id}=float(probe);break;}}if(probe<{lower}+{step}) break;probe-={step};}}}}").unwrap();
                done.insert(id);
                self.nodes.insert(id);
                return Ok(());
            }
            Modern::Beard => "0.0".into(),
        };
        let horizontal = self.graph.column_plan.slots[id] != super::column_plan::NONE
            && !matches!(&**n, Modern::Constant(_) | Modern::Beard);
        if horizontal {
            if self.horizontal.insert(id) {
                writeln!(
                    self.code,
                    "ivec3 key{id}; Volume context{id}; float saved{id}; bool valid{id}=false;"
                )
                .unwrap();
            }
            writeln!(body,"precise float v{id}; if(valid{id}&&all(equal(key{id},ivec3(p.x,p.z,volume?1:0)))&&(!volume||(all(equal(context{id}.origin,context.origin))&&all(equal(context{id}.size,context.size))&&all(equal(context{id}.step,context.step))))) v{id}=saved{id}; else {{v{id}={expression}; key{id}=ivec3(p.x,p.z,volume?1:0);saved{id}=v{id};context{id}=context;valid{id}=true;}}").unwrap();
        } else {
            writeln!(body, "precise float v{id}={expression};").unwrap();
        }
        done.insert(id);
        self.nodes.insert(id);
        Ok(())
    }
}
