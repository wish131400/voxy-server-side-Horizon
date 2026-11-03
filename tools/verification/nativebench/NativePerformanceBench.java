import dev.xantha.vss.client.prediction.RustWorldgenBackend;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** CPU/JNI benchmark of a supplied ABI-6 library and worldgen snapshot.
 * Measures no Java game scheduling, decorations, disk restoration, rendering, or GPU work.
 * Run each library in a fresh JVM; JNI bindings must never mix library instances.
 */
public final class NativePerformanceBench {
    static final int BATCHES = 16, POINTS = 64;
    static volatile long sink;
    static long worldSeed;
    static String queryMode;
    static final long PARTIAL_MASK = ~0x0055005500550055L;
    static int originX = 100000, originZ = 100000;
    static final ByteBuffer[] inputs = new ByteBuffer[BATCHES];
    static final ByteBuffer output = ByteBuffer.allocateDirect(POINTS * 40).order(ByteOrder.LITTLE_ENDIAN);
    static void coordinates(int step) {
        for (int region = 0; region < BATCHES; region++) {
            var input = ByteBuffer.allocateDirect(POINTS * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < POINTS; i++) {
                if (queryMode.startsWith("partial-") && (PARTIAL_MASK & (1L << i)) == 0) continue;
                input.putInt(originX + region * 4096 + (i % 8) * step);
                input.putInt(originZ + (i / 8) * step);
            }
            inputs[region] = input.clear();
        }
    }
    static long query(long world, String mode, int step) {
        long hash = 0;
        for (int region = 0; region < BATCHES; region++) {
            boolean partial = mode.startsWith("partial-");
            boolean grid = mode.endsWith("grid");
            boolean exact = mode.startsWith("exact-");
            int requested = partial ? Long.bitCount(PARTIAL_MASK) : POINTS;
            int count = exact ? RustWorldgenBackend.surfacePoints(world, inputs[region], output, requested) : grid
                    ? RustWorldgenBackend.displayGrid(world, originX + region * 4096, originZ, step,
                            partial ? PARTIAL_MASK : -1L, output)
                    : RustWorldgenBackend.displayPoints(world, inputs[region], output, requested);
            if (count != (grid ? POINTS : requested)) throw new AssertionError("native output count " + count);
            int packed = 0;
            for (int i = 0; i < POINTS; i++) {
                if (partial && (PARTIAL_MASK & (1L << i)) == 0) continue;
                int record = grid ? i : packed++;
                for (int j = 0; j < 10; j++) hash = hash * 31 + output.getInt(record * 40 + j * 4);
            }
        }
        sink = hash;
        return hash;
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        RustWorldgenBackend.load(Path.of(args[0]));
        String document = Files.readString(Path.of(args[1]));
        String mode = args.length > 2 ? args[2] : "points";
        if (!Set.of("points", "grid", "partial-points", "partial-grid", "exact-points").contains(mode))
            throw new IllegalArgumentException("unknown query mode");
        queryMode = mode;
        int rounds = args.length > 3 ? Integer.parseInt(args[3]) : 7;
        int[] steps = args.length > 4 ? Arrays.stream(args[4].split(",")).mapToInt(Integer::parseInt).toArray()
                : new int[]{1, 2, 4, 8, 16, 64};
        if (args.length > 5) worldSeed = Long.parseLong(args[5]);
        if (args.length > 6) originX = Integer.parseInt(args[6]);
        if (args.length > 7) originZ = Integer.parseInt(args[7]);
        if (mode.endsWith("grid") && Arrays.stream(steps).anyMatch(step -> step < 1 || step > 4))
            throw new IllegalArgumentException("displayGrid only accepts spacing 1..4");
        // Load and warm the Java wrapper and native entry points outside timing.
        coordinates(4);
        long warm = RustWorldgenBackend.create(worldSeed, 0, document);
        query(warm, mode, 4);
        RustWorldgenBackend.close(warm);
        for (int round = 0; round < rounds; round++) {
            for (int index = 0; index < steps.length; index++) {
                int step = steps[round % 2 == 0 ? index : steps.length - 1 - index];
                coordinates(step);
                long createStart = System.nanoTime();
                long world = RustWorldgenBackend.create(worldSeed, 0, document);
                double createMs = (System.nanoTime() - createStart) / 1e6;
                long start = System.nanoTime();
                long checksum = query(world, mode, step);
                double coldMs = (System.nanoTime() - start) / 1e6;
                start = System.nanoTime();
                long cached = query(world, mode, step);
                double cachedMs = (System.nanoTime() - start) / 1e6;
                if (checksum != cached) throw new AssertionError("cold/cache records differ");
                RustWorldgenBackend.close(world);
                System.out.printf("{\"round\":%d,\"step\":%d,\"mode\":\"%s\",\"columns\":%d,\"createMs\":%.6f,\"coldMs\":%.6f,\"cachedMs\":%.6f,\"checksum\":\"%s\"}%n",
                        round, step, mode, BATCHES * (mode.startsWith("partial-") ? 48 : 64), createMs, coldMs, cachedMs, Long.toUnsignedString(checksum));
            }
        }
    }
}
