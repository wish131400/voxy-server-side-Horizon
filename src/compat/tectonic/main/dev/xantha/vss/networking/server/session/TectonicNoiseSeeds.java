package dev.xantha.vss.networking.server.session;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

final class TectonicNoiseSeeds {
    private TectonicNoiseSeeds() { }

    // Tectonic's NoisesMixin changes only the positional RNG name. The
    // parameters still belong to the original noise registry entry. A data
    // pack with the same namespace, without this mixin, must keep its own seed.
    static JsonObject noiseSeedAliases(java.util.Set<Identifier> noiseIds, boolean tectonicLoaded) {
        JsonObject aliases = new JsonObject();
        if (tectonicLoaded) noiseIds.stream().sorted().forEach(id -> {
            if (id.getNamespace().equals("tectonic") && id.getPath().startsWith("parameter/")) {
                aliases.addProperty(id.toString(), Identifier.withDefaultNamespace(
                        id.getPath().substring("parameter/".length())).toString());
            }
        });
        return aliases;
    }
}
