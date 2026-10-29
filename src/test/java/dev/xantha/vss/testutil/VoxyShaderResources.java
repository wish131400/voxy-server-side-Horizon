package dev.xantha.vss.testutil;

import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

/** Read shader imports from the actual dependency owning each resource namespace. */
public final class VoxyShaderResources {
    private VoxyShaderResources() { }

    public static String read(ZipFile voxy, String id) throws Exception {
        String[] parts = id.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Expected namespaced shader ID: " + id);
        String path = "assets/" + parts[0] + "/shaders/" + parts[1];
        if (parts[0].equals("sodium")) {
            String configured = System.getProperty("vss.sodiumJar");
            if (configured == null || configured.isBlank())
                throw new IllegalStateException("The actual Sodium JAR is required for shader import " + id);
            try (var sodium = new ZipFile(configured)) {
                return readEntry(sodium, path);
            }
        }
        return readEntry(voxy, path);
    }

    private static String readEntry(ZipFile jar, String path) throws Exception {
        var entry = jar.getEntry(path);
        if (entry == null) throw new IllegalArgumentException("Missing shader " + path + " in " + jar.getName());
        try (var input = jar.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
