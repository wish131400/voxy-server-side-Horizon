package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class VulkanPipelineTest {
    @Test void independentPendingBuffersMatchRustAndCanBeClosedWhileActive() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("vss.testVulkan"));
        RustWorldgenBackend.load(Path.of(System.getProperty("vss.testNativeLibrary")));
        dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap();
        long world = RustWorldgenBackend.create(42, 0, ModernNativeDocuments.overworld().toString());
        try (var gpu = new VulkanDensityDevice(JsonParser.parseString(RustWorldgenBackend.vulkanProgram(world)).getAsJsonObject())) {
            int[][] xyz = {positions(-50003, 16), positions(90007, 4)};
            var first = gpu.submit(xyz[0], 256, 2, -64, 319);
            var second = gpu.submit(xyz[1], 256, 2, -64, 319);
            assertFalse(gpu.hasFreeSlot());
            assertThrows(IllegalStateException.class, () -> gpu.submit(xyz[0], 256, 2, -64, 319));
            // Deliberately consume in reverse order. Neither result may read
            // another pending submission's input or output storage.
            assertArrayEquals(reference(world, xyz[1]), gpu.await(second));
            assertArrayEquals(reference(world, xyz[0]), gpu.await(first));
            assertTrue(gpu.hasFreeSlot());
            var consumed = first;
            assertThrows(IllegalStateException.class, () -> gpu.await(consumed));

            long[] serial = new long[8], pipelined = new long[8];
            for (int round = 0; round < 10; round++) {
                for (int phase = 0; phase < 2; phase++) {
                    boolean overlap = ((round + phase) & 1) != 0;
                    long start = System.nanoTime();
                    for (int batch = 0; batch < 4; batch++) {
                        if (overlap) {
                            first = gpu.submit(xyz[0], 256, 2, -64, 319);
                            second = gpu.submit(xyz[1], 256, 2, -64, 319);
                            gpu.await(first); gpu.await(second);
                        } else {
                            gpu.dispatch(xyz[0], 256, 2, -64, 319);
                            gpu.dispatch(xyz[1], 256, 2, -64, 319);
                        }
                    }
                    long elapsed = System.nanoTime() - start;
                    if (round >= 2) (overlap ? pipelined : serial)[round - 2] = elapsed;
                }
            }
            Arrays.sort(serial); Arrays.sort(pipelined);
            System.out.println("Vulkan pipeline GPU=" + gpu.name + " columns=2048 serialMedianMs="
                    + serial[4] / 1e6 + " pipelinedMedianMs=" + pipelined[4] / 1e6);
            gpu.submit(xyz[0], 256, 2, -64, 319);
            gpu.submit(xyz[1], 256, 2, -64, 319);
            // try-with-resources waits on this background test thread before
            // reclaiming both buffers, just as the production compute worker.
        } finally { RustWorldgenBackend.close(world); }
    }

    private static int[] positions(int x, int step) {
        int[] xyz = new int[256 * 3];
        for (int i = 0; i < 256; i++) { xyz[i * 3] = x + i % 16 * step; xyz[i * 3 + 2] = -75001 + i / 16 * step; }
        return xyz;
    }
    private static int[] reference(long world, int[] xyz) {
        int[] result = new int[256];
        var xz = ByteBuffer.allocateDirect(64 * 8).order(ByteOrder.LITTLE_ENDIAN);
        var output = ByteBuffer.allocateDirect(64 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int base = 0; base < 256; base += 64) {
            xz.clear();
            for (int i = base; i < base + 64; i++) xz.putInt(xyz[i * 3]).putInt(xyz[i * 3 + 2]);
            assertEquals(64, RustWorldgenBackend.displayDensityFloors(world, xz, output, 64));
            for (int i = 0; i < 64; i++) result[base + i] = output.getInt(i * 4);
        }
        return result;
    }
}
