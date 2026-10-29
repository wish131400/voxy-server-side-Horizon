package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class NoiseSeedAliasesTest {
    @Test void onlyTheInstalledTectonicMixinChangesParameterSeeds() {
        var keys=Set.of(Identifier.parse("tectonic:parameter/continentalness"),
                Identifier.parse("tectonic:island/continents_a"),Identifier.parse("minecraft:continentalness"),
                Identifier.parse("other:parameter/continentalness"));
        assertTrue(WorldgenCodecSnapshot.noiseSeedAliases(keys,false).isEmpty());
        var aliases=WorldgenCodecSnapshot.noiseSeedAliases(keys,true);
        assertEquals(1,aliases.size());
        assertEquals("minecraft:continentalness",aliases.get("tectonic:parameter/continentalness").getAsString());
    }
}
