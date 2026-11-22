package dev.xantha.vss.common.worldgen;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;

/** Verified FTF-derived APIs and their release-specific contracts. */
public enum FreeTerraForgedVariant {
    CURRENT("freeterraforged", "etcodehome.freeterraforged.", "FTF"),
    LEGACY("reterraforged", "raccoonman.reterraforged.", "RTF"),
    ULTRA("ultraterraforged", "com.pandaismyname1.ultraterraforged.", "UTF");

    public final String namespace;
    public final String packagePrefix;
    public final String abbreviation;

    FreeTerraForgedVariant(String namespace, String packagePrefix, String abbreviation) {
        this.namespace = namespace;
        this.packagePrefix = packagePrefix;
        this.abbreviation = abbreviation;
    }

    /** These capabilities describe verified contracts, not arbitrary namespace aliases. */
    public boolean discoversContextFromMarkers() { return this == LEGACY || this == ULTRA; }
    public boolean usesNativeTileFilters() { return this != ULTRA; }
    public boolean singleArgumentCellFields() { return this == ULTRA; }

    /** Runtime lookups which are not represented by serialized holder references. */
    public java.util.List<String> additionalRuntimeRegistries() {
        return this == ULTRA ? java.util.List.of("worldgen/structure_rule") : java.util.List.of();
    }

    public String worldgenClass(String name) { return packagePrefix + "world.worldgen." + name; }
    public String stateClass() { return worldgenClass(abbreviation + "RandomState"); }

    public boolean hasPreset(RegistryAccess registries) {
        var key = ResourceKey.createRegistryKey(Identifier.tryParse(namespace + ":worldgen/preset"));
        return registries.registry(key).map(registry -> registry.containsKey(
                Identifier.tryParse(namespace + ":preset"))).orElse(false);
    }

    public static FreeTerraForgedVariant fromClass(Class<?> type) throws ClassNotFoundException {
        for (var variant : values()) {
            if (type.getName().startsWith(variant.packagePrefix)) return variant;
        }
        throw new ClassNotFoundException("Unknown FreeTerraForged API: " + type.getName());
    }

    public static FreeTerraForgedVariant detect(ClassLoader loader) throws ClassNotFoundException {
        for (var variant : values()) {
            try {
                Class.forName(variant.stateClass(), false, loader);
                return variant;
            } catch (ClassNotFoundException absent) { }
        }
        throw new ClassNotFoundException("FreeTerraForged RandomState API unavailable");
    }
}
