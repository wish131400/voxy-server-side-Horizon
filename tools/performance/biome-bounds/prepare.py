"""Install the isolated biome-absence experiment into a frozen version-loading snapshot."""
from pathlib import Path
import argparse,hashlib,json,shutil
p=argparse.ArgumentParser();p.add_argument('snapshot',type=Path);a=p.parse_args()
here=Path(__file__).resolve().parent
snapshot=a.snapshot.resolve();package=snapshot/'main-java/dev/xantha/vss/client/prediction'
vegetation=package/'PredictionVegetation.java'
if not vegetation.is_file():raise SystemExit('Expected a frozen version-loading main-java snapshot')
text=vegetation.read_text(encoding='utf-8')
if 'PrototypeBiomeExclusion' in text:raise SystemExit('Snapshot already contains the experiment')
patches=[
 ('    private final PredictionTreeModels treeModels;',
  '    private final PredictionTreeModels treeModels;\n    private final PrototypeBiomeExclusion biomeExclusion;'),
 ('        this.context = terrain.decorationContext();',
  '        this.context = terrain.decorationContext();\n        this.biomeExclusion = new PrototypeBiomeExclusion(terrain, context);'),
 ('                    if (!reusableTree && nativeStage != null && nativeStage.place(index)) continue;',
  '                    if (biomeExclusion.excludes(feature, level, origin, current)) continue;\n                    if (!reusableTree && nativeStage != null && nativeStage.place(index)) continue;'),
 ('                Map.entry("treeModels",treeModels == null ? Map.of() : treeModels.performanceSnapshot()),',
  '                Map.entry("biomeExclusion",biomeExclusion.snapshot()),\n                Map.entry("treeModels",treeModels == null ? Map.of() : treeModels.performanceSnapshot()),')]
for old,new in patches:
 if text.count(old)!=1:raise SystemExit('Snapshot patch anchor mismatch: '+old)
 text=text.replace(old,new)
vegetation.write_text(text,encoding='utf-8')
for name in ['PredictionFeatureBiomeBounds.java','PrototypeBiomeExclusion.java']:shutil.copy2(here/name,package/name)
evidence={
 'scope':'Frozen benchmark only; no production change or release modification',
 'eligibility':'constant count >=128; pristine audited modifiers/pure predicates/providers; terminal BiomeFilter',
 'bounds':'Conservative block AABB -> all eight BiomeManager quart corners',
 'cache':'512 LRU pages, 4x4x32 quart; <=4096 biome palette; batch256; complete pages only',
 'sources':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in [vegetation,package/'PredictionFeatureBiomeBounds.java',package/'PrototypeBiomeExclusion.java']}}
(snapshot/'biome-exclusion-prototype.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
print('Patched frozen snapshot:',snapshot)
