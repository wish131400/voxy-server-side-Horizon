#version 450
#extension GL_ARB_gpu_shader_fp64 : require
// No fast math: every FP32 intermediate keeps the Rust/vanilla operation order.
layout(local_size_x=32) in;
layout(std430,binding=0) readonly buffer Inputs { ivec4 positions[]; };
layout(std430,binding=1) writeonly buffer Outputs { uint results[]; };
layout(std430,binding=2) readonly buffer WorldData { uint data[]; };
layout(push_constant) uniform Params { uint count; uint kind; int bottom; int top; } params;

struct Volume { ivec3 origin; ivec3 size; ivec3 step; };
Volume emptyVolume() { return Volume(ivec3(0),ivec3(1),ivec3(1)); }
Volume sliceVolume(Volume v,int axis,int coordinate) {v.origin[axis]=coordinate;v.size[axis]=1;return v;}
ivec3 volumeMax(Volume v) {return v.origin+(v.size-1)*v.step;}
// GLSL leaves negative % operands undefined; derive the remainder from the quotient.
int floorDiv(int a,int b) {int q=a/b;return q-((a!=q*b&&((a<0)!=(b<0)))?1:0);}
ivec3 floorDiv3(ivec3 a,ivec3 b) {return ivec3(floorDiv(a.x,b.x),floorDiv(a.y,b.y),floorDiv(a.z,b.z));}
int gradientOffset(int p,int a,int b,int tiling) {int range=b-a;int rel=p-a;if(tiling==0)return clamp(p,min(a,b),max(a,b))-a;int tile=floorDiv(rel,range);int local=rel-tile*range;return tiling==1||(tile&1)==0?local:range-local;}
int saturateInt(double v) {if(isnan(v))return 0;if(v>=2147483647.0LF)return 2147483647;if(v<=-2147483648.0LF)return int(0x80000000u);return int(v);}
bool mathValid=true;
float signExact(float v) {if(v==0.0||isnan(v))return v;return v<0.0?-1.0:1.0;}
float roundExact(float v,float multiple,int kind) {if(multiple==0.0)return v;precise float t=v/multiple;float r=kind==0?floor(t):kind==1?float(saturateInt(floor(double(t)+0.5LF))):kind==2?ceil(t):trunc(t);return r*multiple;}
float distanceExact(ivec3 p,int metric) {precise vec3 q=vec3(p);precise float s=q.x*q.x+q.y*q.y+q.z*q.z;return metric==0?float(sqrt(double(s))):metric==1?s:metric==2?abs(q.x)+abs(q.y)+abs(q.z):max(max(abs(q.x),abs(q.y)),abs(q.z));}

// FP32 inputs, FP64 range reduction/polynomial, one final rounding. Extreme
// arguments request CPU evaluation rather than relying on GPU trig precision.
float sinExact(float inputValue,bool cosine) {
    if(isnan(inputValue)||isinf(inputValue))return uintBitsToFloat(0x7fc00000u);
    if(abs(inputValue)>100000000.0){mathValid=false;return 0.0;}
    if(inputValue==0.0)return cosine?1.0:inputValue;
    precise double x=double(inputValue);
    double quadrant=roundEven(x*0.63661977236758134308LF);
    precise double r=(x-quadrant*1.57079632673412561417LF)-quadrant*6.07710050650619224932e-11LF;
    precise double s=r;precise double term=r;precise double c=1.0LF;precise double ct=1.0LF;
    for(int n=1;n<=10;n++){term*=-(r*r)/double((2*n)*(2*n+1));s+=term;ct*=-(r*r)/double((2*n-1)*(2*n));c+=ct;}
    int q=(int(quadrant)+(cosine?1:0))&3;
    return float(q==0?s:q==1?c:q==2?-s:-c);
}
double logExact(double x) {
    if(x==0.0LF)return -1.0LF/0.0LF;if(x<0.0LF||isnan(x))return packDouble2x32(uvec2(0u,0x7ff80000u));if(isinf(x))return x;
    int exponent;precise double m=frexp(x,exponent)*2.0LF;exponent--;
    precise double r=(m-1.0LF)/(m+1.0LF);precise double power=r;precise double sum=r;
    for(int i=3;i<=75;i+=2){power*=r*r;sum+=power/double(i);}
    return 2.0LF*sum+double(exponent)*0.69314718055994530942LF;
}
double expExact(double x) {
    if(x>710.0LF)return 1.0LF/0.0LF;if(x< -746.0LF)return 0.0LF;
    int n=int(roundEven(x*1.44269504088896340736LF));
    precise double r=(x-double(n)*0.69314718036912381649LF)-double(n)*1.90821492927058770002e-10LF;
    precise double sum=1.0LF;precise double term=1.0LF;for(int i=1;i<=20;i++){term*=r/double(i);sum+=term;}return ldexp(sum,n);
}
float powExact(float a,float b) {
    if(b==0.0)return 1.0;if(isnan(a)||isnan(b)||isinf(b)){mathValid=false;return 0.0;}
    bool odd=abs(b)<16777216.0&&(int(b)&1)!=0;
    if(a==0.0){float result=b<0.0?1.0/0.0:0.0;return odd&&floatBitsToUint(a)==0x80000000u?-result:result;}
    if(a<0.0&&trunc(b)!=b)return uintBitsToFloat(0x7fc00000u);
    precise double result=expExact(double(b)*logExact(abs(double(a))));return float(a<0.0&&odd?-result:result);
}
double readDouble(uint i) { return packDouble2x32(uvec2(data[i],data[i+1u])); }
float jmin(float a,float b) { if(isnan(a)) return a; if(isnan(b)) return b; if(a==0.0&&b==0.0) return uintBitsToFloat(floatBitsToUint(a)|floatBitsToUint(b)); return a<b?a:b; }
float jmax(float a,float b) { if(isnan(a)) return a; if(isnan(b)) return b; if(a==0.0&&b==0.0) return uintBitsToFloat(floatBitsToUint(a)&floatBitsToUint(b)); return a>b?a:b; }
float square(float v) { precise float r=v*v; return r; }
float cube(float v) { precise float r=v*v*v; return r; }
float negativeScale(float v,float scale) { precise float r=v>0.0?v:v*scale; return r; }
float squeeze(float v) { v=clamp(v,-1.0,1.0); precise float r=v/2.0-v*v*v/24.0; return r; }
float mixExact(float t,float a,float b) { if(t==0.0) return a; if(t==1.0) return b; precise float r=a+t*(b-a); return r; }
float lerpExact(float t,float a,float b) { precise float r=a+t*(b-a); return r; }
float fade(float t) { precise float r=t*t*t*(t*(t*6.0-15.0)+10.0); return r; }
float lerp3(float x,float y,float z,float v[8]) { return lerpExact(z,lerpExact(y,lerpExact(x,v[0],v[1]),lerpExact(x,v[2],v[3])),lerpExact(y,lerpExact(x,v[4],v[5]),lerpExact(x,v[6],v[7]))); }
double wrap(double v) { if(v>=-16777215.999999998LF&&v<16777215.999999998LF) return v; precise double r=v-floor(v/33554432.0LF+0.5LF)*33554432.0LF; return r; }
uint perm(uint b,int i) { return data[b+12u+uint(i&255)]; }
const ivec3 gradients[16]=ivec3[](ivec3(1,1,0),ivec3(-1,1,0),ivec3(1,-1,0),ivec3(-1,-1,0),ivec3(1,0,1),ivec3(-1,0,1),ivec3(1,0,-1),ivec3(-1,0,-1),ivec3(0,1,1),ivec3(0,-1,1),ivec3(0,1,-1),ivec3(0,-1,-1),ivec3(1,1,0),ivec3(0,-1,1),ivec3(-1,1,0),ivec3(0,-1,-1));
float perlin(uint b,dvec3 p,bool volume) {
    precise dvec3 q=dvec3(wrap(p.x)+readDouble(b),wrap(p.y)+readDouble(b+2u),wrap(p.z)+readDouble(b+4u));
    ivec3 i=ivec3(floor(q)); precise vec3 t=vec3(q-dvec3(i)); precise float relativeY=t.y;
    if(data[b+9u]!=0u) { double scale=readDouble(b+10u); double original=q.y-double(i.y); double limit=(p.y>=0.0LF&&p.y<original)?p.y:original; relativeY=float(original-floor(limit/scale+double(uintBitsToFloat(869711765u)))*scale); }
    int a=int(perm(b,i.x)); int c=int(perm(b,i.x+1));
    int aa=int(perm(b,a+i.y)); int ab=int(perm(b,a+i.y+1)); int ca=int(perm(b,c+i.y)); int cb=int(perm(b,c+i.y+1));
    float v[8];
    for(int n=0;n<8;n++) {
        int row=(n&1)==0?((n&2)==0?aa:ab):((n&2)==0?ca:cb);
        vec3 g=vec3(gradients[perm(b,row+i.z+((n>>2)&1))&15u]);
        precise float dx=t.x-float(n&1); precise float dy=relativeY-float((n>>1)&1); precise float dz=t.z-float((n>>2)&1);
        if(volume) { precise float r=g.x*dx+g.z*dz+g.y*dy; v[n]=r; }
        else { precise float r=g.x*dx+g.y*dy+g.z*dz; v[n]=r; }
    }
    return lerp3(fade(t.x),fade(t.y),fade(t.z),v);
}
