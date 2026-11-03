// Additional exact worldgen kernels for the existing CPU-compatible mod paths.
uint simplexPerm(uint b,int i) {return data[b+uint(i&255)];}
double simplexCorner(int hash,double x,double y) {
    precise double t=0.5LF-x*x-y*y;if(t<0.0LF)return 0.0LF;t*=t;
    ivec3 g=gradients[hash%12];precise double dotValue=double(g.x)*x+double(g.y)*y+double(g.z)*0.0LF;
    return t*t*dotValue;
}
double simplex2(uint b,double x,double y) {
    const double f=0.3660254037844386LF,g=0.21132486540518713LF;
    precise double skew=(x+y)*f;int i=saturateInt(floor(x+skew)),j=saturateInt(floor(y+skew));
    precise double unskew=double(i+j)*g;precise double dx=x-(double(i)-unskew),dy=y-(double(j)-unskew);
    int a=dx>dy?1:0,c=1-a;i&=255;j&=255;
    precise double n0=simplexCorner(int(simplexPerm(b,i+int(simplexPerm(b,j)))),dx,dy);
    precise double n1=simplexCorner(int(simplexPerm(b,i+a+int(simplexPerm(b,j+c)))),dx-double(a)+g,dy-double(c)+g);
    precise double n2=simplexCorner(int(simplexPerm(b,i+1+int(simplexPerm(b,j+1)))),dx-1.0LF+2.0LF*g,dy-1.0LF+2.0LF*g);
    return 70.0LF*(n0+n1+n2);
}
float endIslands(uint b,int x,int z) {
    int cx=x/2,cz=z/2,sx=x-cx*2,sz=z-cz*2;precise float result=-100.0;
    for(int xo=-12;xo<=12;xo++)for(int zo=-12;zo<=12;zo++) {
        int xx=cx+xo,zz=cz+zo;
        if(double(xx)*double(xx)+double(zz)*double(zz)<=4096.0LF||float(simplex2(b,double(xx),double(zz)))>=-0.9)continue;
        precise float size=float(abs(xx))*3439.0+float(abs(zz))*147.0;size=float(double(size)-trunc(double(size)/13.0LF)*13.0LF)+9.0;
        precise float dx=float(sx-xo*2),dz=float(sz-zo*2);
        precise float next=100.0-float(sqrt(double(dx*dx+dz*dz)))*size;
        result=jmax(result,clamp(next,-100.0,80.0));
    }
    return (result-8.0)/128.0;
}
// Java's legacy generator needs only its low 48 bits. Pair arithmetic avoids
// introducing an additional shaderInt64 hardware requirement.
uvec2 addPair(uvec2 a,uvec2 b) {uint lo=a.x+b.x;return uvec2(lo,a.y+b.y+uint(lo<a.x));}
uvec2 mulPair(uvec2 a,uvec2 b) {uint hi,lo;umulExtended(a.x,b.x,hi,lo);return uvec2(lo,hi+a.x*b.y+a.y*b.x);}
uvec2 signedPair(int v) {return uvec2(uint(v),v<0?0xffffffffu:0u);}
uint randomBits(inout uvec2 seed,int count) {seed=addPair(mulPair(seed,uvec2(0xdeece66du,5u)),uvec2(11u,0u));seed.y&=65535u;return count<=16?seed.y>>uint(16-count):(seed.y<<uint(count-16))|(seed.x>>uint(48-count));}
int randomBound(inout uvec2 seed,int bound) {
    if((bound&-bound)==bound){uint hi,lo;umulExtended(uint(bound),randomBits(seed,31),hi,lo);return int((hi<<1)|(lo>>31));}
    for(;;){int value=int(randomBits(seed,31)),r=value%bound;if(value-r+bound-1>=0)return r;}
}
float cellNoise(uint b,ivec3 p,out ivec3 nearest,out ivec3 second) {
    int horizontal=int(data[b]),vertical=int(data[b+1u]),jx=int(data[b+2u]),jy=int(data[b+3u]),kind=int(data[b+4u]);
    ivec3 grid=ivec3(floorDiv(p.x,horizontal),vertical==0?0:floorDiv(p.y,vertical),floorDiv(p.z,horizontal));
    precise float firstDistance=3.402823466e38,secondDistance=3.402823466e38,value=firstDistance;nearest=ivec3(0);second=ivec3(0);
    for(int xo=-1;xo<=1;xo++)for(int yo=vertical==0?0:-1;yo<=(vertical==0?0:1);yo++)for(int zo=-1;zo<=1;zo++) {
        ivec3 cell=grid+ivec3(xo,yo,zo);
        uvec2 seed=addPair(mulPair(signedPair(cell.x),uvec2(0x9939f508u,79u)),mulPair(signedPair(cell.z),uvec2(0xf1565bd5u,30u)));
        if(vertical!=0)seed=addPair(seed,mulPair(signedPair(cell.y),uvec2(0x58ff18afu,69u)));
        seed=addPair(seed,uvec2(data[b+5u],data[b+6u]));seed^=uvec2(0xdeece66du,5u);seed.y&=65535u;
        int tx=cell.x*horizontal+randomBound(seed,jx+1);
        int ty=vertical==0?0:cell.y*vertical+randomBound(seed,jy+1);
        int tz=cell.z*horizontal+randomBound(seed,jx+1);
        precise float cellValue=float(randomBits(seed,24))*(1.0/16777216.0);
        precise float dx=float(tx-p.x),dy=float(ty-p.y),dz=float(tz-p.z);
        precise float squareDistance=vertical==0?dx*dx+dz*dz:dx*dx+dy*dy+dz*dz;precise float distanceValue=float(sqrt(double(squareDistance)));
        if(distanceValue<secondDistance) {if(distanceValue<firstDistance){secondDistance=firstDistance;second=nearest;firstDistance=distanceValue;nearest=ivec3(tx,ty,tz);value=cellValue;}else{secondDistance=distanceValue;second=ivec3(tx,ty,tz);}}
    }
    return kind==0?value:kind==1?firstDistance:kind==2?secondDistance:kind==3?(secondDistance+firstDistance)*0.5:kind==4?secondDistance-firstDistance:kind==5?secondDistance*firstDistance*0.5:firstDistance/secondDistance;
}
int floatCompare(float a,float b) {if(a<b)return -1;if(a>b)return 1;int aa=isnan(a)?int(0x7fc00000u):floatBitsToInt(a),bb=isnan(b)?int(0x7fc00000u):floatBitsToInt(b);return aa<bb?-1:aa>bb?1:0;}
