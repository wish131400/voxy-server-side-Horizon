package dev.xantha.vss.common.worldgen;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.function.Predicate;
import net.minecraft.core.Holder;

/** Repairs record children whose upstream mapAll implementation omits the visitor callback. */
final class AppliedDensityRecords {
    private final IdentityHashMap<Object, Object> captured = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
    private final UnaryOperator<Object> transform;
    private final Predicate<Object> densityRecord;

    AppliedDensityRecords(UnaryOperator<Object> transform, Predicate<Object> densityRecord) {
        this.transform = transform;
        this.densityRecord = densityRecord;
    }

    Object capture(Object source) {
        if (source == null) return null;
        Object known = captured.get(source);
        if (known != null) return known;
        if (active.put(source, Boolean.TRUE) != null)
            throw new IllegalStateException("Cyclic applied density graph at " + source.getClass().getName());
        Object result = source;
        if (source instanceof Holder<?> holder) {
            Object value = holder.value();
            Object mapped = densityRecord.test(value) ? capture(value) : value;
            if (mapped != value) result = Holder.direct(mapped);
        } else if (source instanceof List<?> values) {
            var mapped = new ArrayList<Object>(values.size());
            boolean changed = false;
            for (Object value : values) {
                Object replacement = capture(value);
                mapped.add(replacement);
                changed |= replacement != value;
            }
            if (changed) result = List.copyOf(mapped);
        } else if (source.getClass().isRecord() && densityRecord.test(source)) {
            var components = source.getClass().getRecordComponents();
            Object[] arguments = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            boolean changed = false;
            try {
                for (int i = 0; i < components.length; i++) {
                    var accessor = components[i].getAccessor();
                    accessor.setAccessible(true);
                    Object value = accessor.invoke(source);
                    arguments[i] = capture(value);
                    types[i] = components[i].getType();
                    changed |= arguments[i] != value;
                }
                if (changed) {
                    var constructor = source.getClass().getDeclaredConstructor(types);
                    constructor.setAccessible(true);
                    result = constructor.newInstance(arguments);
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot capture applied density record " + source.getClass().getName(), failure);
            }
        }
        result = transform.apply(result);
        active.remove(source);
        captured.put(source, result);
        return result;
    }
}
