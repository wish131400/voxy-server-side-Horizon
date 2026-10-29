import com.google.gson.*;
import com.mojang.serialization.*;
import java.nio.file.*;
import net.minecraft.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;
public class Minecraft263NativeReference {
 static final Gson GSON=new Gson();
 static <T> JsonObject registry(HolderLookup.Provider l, net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>> key,Codec<T> codec){
  JsonObject out=new JsonObject();var ops=l.createSerializationContext(JsonOps.INSTANCE);
  l.lookupOrThrow(key).listElements().forEach(h->out.add(h.key().identifier().toString(),codec.encodeStart(ops,h.value()).getOrThrow()));return out;
 }
 public static void main(String[] args)throws Exception {
  SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  var lookup=VanillaRegistries.createWorldLookup();var ops=lookup.createSerializationContext(JsonOps.INSTANCE);
  var df=registry(lookup,Registries.DENSITY_FUNCTION,DensityFunctions.DIRECT_CODEC);
  var noises=registry(lookup,Registries.NOISE,net.minecraft.world.level.levelgen.synth.NormalNoise.DIRECT_CODEC);
  Path output=Path.of(args[0]);Files.createDirectories(output);
  exportBeard(output);
  for(String name:new String[]{"overworld","nether","end"}){
   var settings=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(ResourceKey.create(Registries.NOISE_SETTINGS,Identifier.withDefaultNamespace(name))).value();
   JsonObject doc=new JsonObject();doc.addProperty("vss_density_semantics","float-26.3");
   doc.add("settings",NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops,settings).getOrThrow());doc.add("density_functions",df);doc.add("noises",noises);
   JsonArray samples=new JsonArray();doc.add("reference_samples",samples);
   JsonArray volumes=new JsonArray();doc.add("reference_volumes",volumes);
   JsonArray columns=new JsonArray();doc.add("reference_columns",columns);
   var blockNames=new java.util.ArrayList<String>();JsonArray encodedNames=new JsonArray();doc.add("reference_block_names",encodedNames);
   var router=settings.noiseRouter();String[] roots={"temperature","vegetation","continents","erosion","depth","ridges","chunk_surface_level","final_density","test_interp","test_nested_interp","test_slice","test_top"};
   var noise=DensityFunctions.noise(lookup.lookupOrThrow(Registries.NOISE).getOrThrow(Noises.SURFACE),1.25,.5);
   var interp=DensityFunctions.interpolated(noise,6,10);
   var custom=java.util.Map.of("test_interp",interp,"test_nested_interp",DensityFunctions.interpolated(DensityFunctions.interpolated(noise,4,8),6,10),
    "test_slice",DensityFunctions.sliceY(interp,17),"test_top",DensityFunctions.findTopSurface(DensityFunctions.yClampedGradient(-64,192,1,-1),interp.mul(80).add(100),-64,1));
   for(var entry:custom.entrySet())doc.getAsJsonObject("settings").getAsJsonObject("noise_router").add(entry.getKey(),DensityFunctions.DIRECT_CODEC.encodeStart(ops,entry.getValue()).getOrThrow());
   for(long seed:new long[]{0,42,-917}){
    var state=RandomState.create(lookup.lookupOrThrow(Registries.NOISE),seed,settings);
    var generator=new NoiseBasedChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)),Holder.direct(settings));
    var columnPositions=new java.util.ArrayList<int[]>();
    for(int[] p:new int[][]{{0,0},{-17,31},{2050,-2095},{-2048,2048}})columnPositions.add(p);
    for(int z=-64;z<=64;z+=32)for(int x=-64;x<=64;x+=32)if(x!=0||z!=0)columnPositions.add(new int[]{x,z});
    for(int[] p:columnPositions){
     var h=net.minecraft.world.level.LevelHeightAccessor.create(settings.noiseSettings().minY(),settings.noiseSettings().height());
     var column=generator.getBaseColumn(p[0],p[1],h,state);JsonObject ref=new JsonObject();ref.addProperty("seed",seed);ref.add("p",GSON.toJsonTree(p));JsonArray blocks=new JsonArray();ref.add("blocks",blocks);
     for(int y=h.getMinY();y<=h.getMaxY();y++){
      String block=BuiltInRegistries.BLOCK.getKey(column.getBlock(y).getBlock()).toString();int id=blockNames.indexOf(block);
      if(id<0){id=blockNames.size();blockNames.add(block);encodedNames.add(block);}blocks.add(id);
     }columns.add(ref);
    }

    for(String root:roots){
     String accessor=switch(root){case "chunk_surface_level"->"chunkSurfaceLevel";case "final_density"->"finalDensity";default->root;};
     DensityFunction f=custom.containsKey(root)?custom.get(root):(DensityFunction)NoiseRouter.class.getMethod(accessor).invoke(router);var sampler=state.getSampler(f);
     for(int[] p:new int[][]{{0,64,0},{-17,-64,31},{100005,67,-100009},{1234,0,-7890}}){
      JsonObject sample=new JsonObject();sample.addProperty("seed",seed);sample.addProperty("root",root);sample.add("p",GSON.toJsonTree(p));sample.addProperty("single",Float.floatToIntBits(sampler.sampleValue(SamplerContext.EMPTY_UNCACHED,p[0],p[1],p[2])));samples.add(sample);
     }
     for(int[] request:new int[][]{{-16,-64,32,4,32,4,1,1,1},{-16,48,32,4,32,4,1,1,1},{2048,48,-2096,4,32,4,1,1,1},{-32,0,-48,4,32,4,1,1,1},{-17,49,31,4,32,4,1,1,1},{2047,-59,-2097,5,17,3,3,2,5},{-12,20,18,3,4,2,6,10,6}}){
      DensityVolume volume=new DensityVolume(request[3],request[4],request[5],request[0],request[1],request[2],request[6],request[7],request[8]);var buffer=DensityBuffer.createUnpooled(volume.size());sampler.sampleVolume(SamplerContext.EMPTY_UNCACHED,buffer,volume);
      JsonObject sample=new JsonObject();sample.addProperty("seed",seed);sample.addProperty("root",root);sample.add("origin",GSON.toJsonTree(java.util.Arrays.copyOfRange(request,0,3)));sample.add("size",GSON.toJsonTree(java.util.Arrays.copyOfRange(request,3,6)));sample.add("step",GSON.toJsonTree(java.util.Arrays.copyOfRange(request,6,9)));JsonArray bits=new JsonArray();sample.add("bits",bits);
      for(int z=0;z<volume.sizeZ();z++)for(int x=0;x<volume.sizeX();x++)for(int y=0;y<volume.sizeY();y++)bits.add(Float.floatToIntBits(buffer.get(volume.indexUnchecked(x,y,z))));
      volumes.add(sample);
     }
    }
   }
   Files.writeString(output.resolve(name+".json"),GSON.toJson(doc));System.out.println("Exported "+name+" "+samples.size());
  }
 }
 static void exportBeard(Path output)throws Exception {
  var pieces=new java.util.ArrayList<Beardifier.Rigid>();JsonObject doc=new JsonObject();JsonArray values=new JsonArray();doc.add("pieces",values);
  int i=0;for(var adjustment:net.minecraft.world.level.levelgen.structure.TerrainAdjustment.values()){
   var box=new net.minecraft.world.level.levelgen.structure.BoundingBox(-5+i,3+i,-9+i,2+i,17+i,4+i);int ground=i-2;
   pieces.add(new Beardifier.Rigid(box,adjustment,ground));JsonObject p=new JsonObject();p.addProperty("adjustment",adjustment.getSerializedName());p.addProperty("ground_delta",ground);p.add("box",GSON.toJsonTree(new int[]{box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()}));values.add(p);i++;
  }
  var junctions=java.util.List.of(new net.minecraft.world.level.levelgen.structure.pools.JigsawJunction(1,8,-2,0,net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.Projection.RIGID),
   new net.minecraft.world.level.levelgen.structure.pools.JigsawJunction(-8,19,5,0,net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.Projection.TERRAIN_MATCHING));
  doc.add("junctions",GSON.toJsonTree(junctions.stream().map(j->new int[]{j.getSourceX(),j.getSourceGroundY(),j.getSourceZ()}).toList()));
  var beard=new Beardifier(pieces,junctions,new net.minecraft.world.level.levelgen.structure.BoundingBox(-100,-100,-100,100,100,100));
  JsonArray samples=new JsonArray();doc.add("samples",samples);
  for(int z=-18;z<=18;z+=3)for(int x=-18;x<=18;x+=3)for(int y=-10;y<=36;y+=2){JsonObject sample=new JsonObject();sample.add("p",GSON.toJsonTree(new int[]{x,y,z}));sample.addProperty("bits",Float.floatToIntBits(beard.sampleValue(SamplerContext.EMPTY_UNCACHED,x,y,z)));samples.add(sample);}
  Files.writeString(output.resolve("beard.json"),GSON.toJson(doc));
 }
}
