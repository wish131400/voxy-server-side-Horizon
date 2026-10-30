package dev.xantha.vss.testutil;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

/** The fixed release input may contain V1 and V2, or only V2 in newer releases. */
public final class ReleasedNbtTreeFeatures {
    private static final String PREFIX = "dev.corgitaco.ohthetreesyoullgrow.world.level.levelgen.feature.";

    private ReleasedNbtTreeFeatures() { }

    public static List<String> classNames(JarFile jar) {
        var targets = List.of(PREFIX + "TreeFromStructureNBTFeature", PREFIX + "TreeFromStructureNBTFeatureV2")
                .stream().filter(name -> jar.getJarEntry(name.replace('.', '/') + ".class") != null).toList();
        assertFalse(targets.isEmpty(), "The released OTTYG JAR must contain at least one supported tree feature");
        System.out.println("[optional-compat] " + Path.of(jar.getName()).getFileName() + " tree targets: " + targets);
        return targets;
    }
}
