package dev.xantha.vss.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VSSConstantsTest {

    @Test
    void protocolVersionIncludesScopedColumnMetadata() {
        // Current worldgen/template wire format; incompatible peers must not handshake.
        String target = System.getProperty("vss.minecraftTarget", "1.21.11");
        int expectedProtocol = switch (target) {
            case "1.21.11" -> 54;
            case "26.1" -> 55;
            case "26.1.1" -> 56;
            case "26.1.2" -> 57;
            case "26.2" -> 58;
            case "26.3" -> 59;
            case "1.21.1" -> 60;
            case "1.20.1" -> 61;
            default -> throw new AssertionError("Unverified Minecraft target: " + target);
        };
        assertEquals(expectedProtocol, VSSConstants.PROTOCOL_VERSION);
        assertEquals(expectedProtocol - 48,
                dev.xantha.vss.common.processing.EncodedColumnData.SCHEMA_VERSION);
    }

    @Test
    void columnVersionIsStrictlyMonotonic() {
        long previous = VSSConstants.columnVersion();

        for (int i = 0; i < 1000; i++) {
            long next = VSSConstants.columnVersion();
            assertTrue(next > previous);
            previous = next;
        }
    }
}
