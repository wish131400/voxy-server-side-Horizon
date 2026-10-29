package dev.xantha.vss.networking;

import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Keeps summaries within vanilla's 32 KiB serverbound payload limit, even without compression. */
public final class RegionPresenceTransfer {
    // Reserve space for a custom dimension identifier and packet framing.
    private static final int RAW_BYTES_PER_PACKET = 30 * 1024;
    private static final int RAW_HEADER_BYTES = Integer.BYTES;
    private static final int REGION_HEADER_BYTES = 3 * Integer.BYTES
            + RegionPresenceC2SPayload.REGION_BITMAP_LONGS * Long.BYTES;
    private static final int COLUMN_BYTES = Short.BYTES + Long.BYTES;

    private RegionPresenceTransfer() { }

    public static void send(ResourceKey<Level> dimension, boolean reset,
            List<RegionPresenceC2SPayload.RegionEntry> entries, boolean allowZstd,
            Consumer<RegionPresenceC2SPayload> sender) {
        ArrayList<RegionPresenceC2SPayload.RegionEntry> batch = new ArrayList<>();
        int rawBytes = RAW_HEADER_BYTES;
        boolean nextReset = reset;
        for (var entry : entries) {
            if (entry.count() < 0 || entry.count() > RegionPresenceC2SPayload.REGION_SLOT_COUNT)
                throw new IllegalArgumentException("Invalid region column count: " + entry.count());
            int entryBytes = REGION_HEADER_BYTES + entry.count() * COLUMN_BYTES;
            if (!batch.isEmpty() && rawBytes + entryBytes > RAW_BYTES_PER_PACKET) {
                sender.accept(RegionPresenceC2SPayload.create(dimension, nextReset, batch, allowZstd));
                nextReset = false;
                batch.clear();
                rawBytes = RAW_HEADER_BYTES;
            }
            batch.add(entry);
            rawBytes += entryBytes;
        }
        if (!batch.isEmpty() || reset) {
            sender.accept(RegionPresenceC2SPayload.create(dimension, nextReset, batch, allowZstd));
        }
    }
}
