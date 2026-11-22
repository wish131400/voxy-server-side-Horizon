package dev.xantha.vss.testutil;

import dev.xantha.vss.common.worldgen.TectonicConfigNoise;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;

/** JUnit bootstraps vanilla without Fabric's registry lifecycle hooks. Test use only. */
public final class TectonicCodecFixtures {
    private TectonicCodecFixtures() { }

    public static synchronized void register() {
        var registry = BuiltInRegistries.DENSITY_FUNCTION_TYPE;
        try {
            var frozen = MappedRegistry.class.getDeclaredField("frozen");
            frozen.setAccessible(true);
            boolean previous = frozen.getBoolean(registry);
            frozen.setBoolean(registry, false);
            try {
                TectonicConfigNoise.register();
                // Re-freezing a modern registry also attempts to initialize its already-bound tags.
                // Bind only our added codec, leaving every existing holder and registry tag intact.
                var byValue = MappedRegistry.class.getDeclaredField("byValue");
                byValue.setAccessible(true);
                var values = (java.util.Map<?, ?>) byValue.get(registry);
                var entry = values.entrySet().stream().filter(candidate ->
                        candidate.getValue() instanceof Holder.Reference<?> holder
                                && holder.key().identifier().toString().equals("vss:tectonic_config_noise"))
                        .findFirst().orElseThrow(() -> new IllegalStateException("Applied Tectonic codec was not registered"));
                var holder = (Holder.Reference<?>) entry.getValue();
                if (!holder.isBound()) {
                    var bindValue = Holder.Reference.class.getDeclaredMethod("bindValue", Object.class);
                    bindValue.setAccessible(true);
                    bindValue.invoke(holder, entry.getKey());
                }
                if (!holder.isBound()) throw new IllegalStateException("Applied Tectonic codec holder is not bound");
            } finally {
                frozen.setBoolean(registry, previous);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot prepare the offline density codec registry", failure);
        }
    }
}
