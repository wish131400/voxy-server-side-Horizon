package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

class WorldgenTemplateCatalogFixtureTest {
    @Test void completeAuditedAtmTemplateSetFitsManifestAndNoNamespaceIsDropped() throws Exception {
        String directory = System.getProperty("vss.templateCatalogDirectory");
        assumeTrue(directory != null, "Supply audited ATM mod jars with -PvssTemplateCatalogDirectory");
        var jars = new java.util.ArrayList<JarFile>();
        var entries = new java.util.LinkedHashMap<Identifier, Resource>();
        try (var files = Files.list(Path.of(directory))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) {
                var jar = new JarFile(file.toFile()); jars.add(jar);
                for (var entry : jar.stream().filter(item -> item.getName().matches("data/[^/]+/structure/.+\\.nbt")).toList()) {
                    String[] parts = entry.getName().split("/", 3);
                    Identifier key = Identifier.parse(parts[1] + ":" + parts[2]);
                    entries.put(key, new Resource(null, () -> jar.getInputStream(entry)));
                }
            }
            var manager = new ResourceManager() {
                @Override public Set<String> getNamespaces() { return entries.keySet().stream().map(Identifier::getNamespace).collect(java.util.stream.Collectors.toSet()); }
                @Override public Optional<Resource> getResource(Identifier id) { return Optional.ofNullable(entries.get(id)); }
                @Override public List<Resource> getResourceStack(Identifier id) { return getResource(id).stream().toList(); }
                @Override public Map<Identifier, Resource> listResources(String dir, ResourceManager.Selector filter) { return entries; }
                @Override public Map<Identifier, List<Resource>> listResourceStacks(String dir, ResourceManager.Selector filter) { throw new UnsupportedOperationException(); }
                @Override public Stream<PackResources> listPacks() { return Stream.empty(); }
            };
            WorldgenTemplateCatalog.clear();
            var snapshot = WorldgenTemplateCatalog.snapshot(manager);
            assertEquals(12_508, entries.size(), "Use the 57 verified JARs from the ATM 10 8.2 audit");
            assertEquals(entries.size(), snapshot.entries().size());
            var encoded = dev.xantha.vss.common.worldgen.WorldgenJson.bytes(snapshot.manifest());
            byte[] compressed = dev.xantha.vss.common.processing.LodByteCompression.compressForNetwork(encoded, false).bytes();
            assertTrue(encoded.length < 32 * 1024 * 1024);
            assertTrue(compressed.length < 8 * 1024 * 1024);
            for (Identifier resource : entries.keySet()) {
                String path = resource.getPath();
                var id = resource.withPath(path.substring("structure/".length(), path.length() - 4));
                assertTrue(snapshot.manifest().has(id.toString()), id.toString());
            }
            System.out.println("TEMPLATE_CATALOG entries=" + entries.size() + " raw=" + encoded.length + " compressed=" + compressed.length);
        } finally {
            WorldgenTemplateCatalog.clear();
            for (var jar : jars) jar.close();
        }
    }
}
