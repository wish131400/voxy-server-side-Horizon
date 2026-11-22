package dev.xantha.vss.mixin;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class FreeTerraForgedDecorationMixinTest {
    @Test
    @EnabledIfSystemProperty(named="vss.freeTerraForgedJar",matches=".+")
    void productionPluginActuallyAdaptsReleasedDecorationClasses() throws Exception {
        var harness = new VoxyMixinHarness(System.getProperty("vss.freeTerraForgedJar"),
                "{\"required\":true,\"minVersion\":\"0.8\",\"package\":\"dev.xantha.vss.mixin.client\","
                + "\"plugin\":\"dev.xantha.vss.mixin.voxy.VoxyPredictionPlugin\","
                + "\"client\":[\"FreeTerraForgedPredictionDecorationMixin\"],\"injectors\":{\"defaultRequire\":1}}");
        int changed = 0;
        for (String suffix : java.util.List.of("placement.poisson.FastPoissonModifier",
                "placement.UndergroundFeatureEnclosure", "RiverGasketFeature", "DecorateSnowFeature", "template.TemplateFeature")) {
            String name = "etcodehome.freeterraforged.world.worldgen.feature." + suffix;
            var node = new ClassNode();new ClassReader(harness.apply(name)).accept(node,0);
            for (var method : node.methods) {
                long bridges = java.util.stream.StreamSupport.stream(method.instructions.spliterator(),false)
                        .filter(i -> i instanceof MethodInsnNode c && c.owner.endsWith("/FreeTerraForgedDecorationContext")).count();
                changed += (int) bridges;
                if (bridges != 0) new Analyzer<>(new BasicVerifier()).analyze(node.name,method);
            }
        }
        assertEquals(5,changed,"Sponge's real preApply must enable every released read-only context bridge");
    }
}
