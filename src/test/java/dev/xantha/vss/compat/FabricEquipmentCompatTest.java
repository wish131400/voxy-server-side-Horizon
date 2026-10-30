package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FabricEquipmentCompatTest {
    @BeforeAll
    static void bootstrap() { FabricEquipmentTestBootstrap.initialize(); }

    @Test
    void legacyTrinketsSnapshotIncludesRemovalAndDynamicSlotMetadata() throws Exception {
        LegacyTrinkets source = new LegacyTrinkets();
        source.slot().metadata.putString("Modifier", "extra_slots");
        source.slot().metadata.putInt("Size", 3);
        source.slot().metadata.putBoolean("Expanded", true);
        CompoundTag payload = FabricEquipmentCompat.captureTrinkets(source, null);
        LegacyTrinkets target = new LegacyTrinkets();
        target.slot().setItem(2, new ItemStack(Items.STONE));
        assertFalse(target.slot().getItem(2).isEmpty(), "The fixture must contain a real item before replacement");
        FabricEquipmentCompat.applyTrinkets(target, payload, null);
        assertEquals(source.slot().metadata, target.slot().metadata);
        assertTrue(target.slot().isEmpty());
        assertEquals(List.of("clear", "sync"), target.slot().events);

        source.inventory.clear();
        target.slot().setItem(0, new ItemStack(Items.STONE));
        assertFalse(target.slot().getItem(0).isEmpty(), "The omitted slot must start occupied");
        TrinketInventory previous = target.slot();
        FabricEquipmentCompat.applyTrinkets(target, FabricEquipmentCompat.captureTrinkets(source, null), null);
        assertTrue(previous.isEmpty(), "An omitted slot must clear the previous remote inventory");
        assertTrue(previous.metadata.isEmpty(), "An omitted slot must clear previous modifiers");
        assertEquals(1, previous.getContainerSize());
    }

    @Test
    void registryAwareTrinketsCapturesFullSyncMetadataAndClearsShrinkingSlots() throws Exception {
        Registries server = new Registries();
        RegistryTrinkets source = new RegistryTrinkets();
        source.slot().metadata.putString("Modifier", "full_sync");
        source.slot().metadata.putInt("Size", 1);
        source.slot().effectiveSize = 1;
        CompoundTag payload = FabricEquipmentCompat.captureTrinkets(source, server);
        RegistryTrinkets target = new RegistryTrinkets();
        target.slot().setItem(2, new ItemStack(Items.STONE));
        assertFalse(target.slot().getItem(2).isEmpty(), "The tail must start occupied before shrinking");
        FabricEquipmentCompat.applyTrinkets(target, payload, net.minecraft.core.RegistryAccess.EMPTY);
        assertSame(server, source.lastRegistry);
        assertEquals(source.slot().metadata, target.slot().metadata);
        assertEquals(1, target.slot().getContainerSize());
        assertTrue(target.slot().isEmpty(), "The old tail must be cleared before applying a smaller inventory");
    }

    @Test
    void accessoriesRestoresCosmeticsRenderTogglesAndInitializesAfterReading() throws Exception {
        var access = new FabricEquipmentCompat.AccessoriesNbt(name -> resolve(name, true));
        Registries server = new Registries();
        Registries client = new Registries();
        Holder source = new Holder();
        source.data = equipment("functional", false);
        source.data.putString("Cosmetic", "cape");
        CompoundTag payload = access.capture(source, server);
        assertSame(server, source.registry);
        Holder target = new Holder();
        Capability capability = new Capability();
        access.apply(target, capability, payload, client);
        assertEquals(List.of("read", "init"), target.events);
        assertEquals(source.data, target.data);
        assertSame(client, target.registry);
        assertSame(capability, target.capability);
    }

    @Test
    void legacyAccessoriesUsesEmptySerializationContext() throws Exception {
        var access = new FabricEquipmentCompat.AccessoriesNbt(name -> resolve(name, false));
        Holder source = new Holder();
        source.data = equipment("hat", true);
        CompoundTag payload = access.capture(source, new Registries());
        Holder target = new Holder();
        access.apply(target, new Capability(), payload, new Registries());
        assertNull(source.registry);
        assertNull(target.registry);
        assertEquals(source.data, target.data);
        assertEquals(List.of("read", "init"), target.events);
    }

    @Test
    void absentProvidersAndNullEntityDoNotCreateOrRetryEmptySnapshots() {
        assertNull(CuriosCompat.capture(null));
        assertFalse(CuriosCompat.apply(null, new CompoundTag()));
        assertTrue(FabricEquipmentCompat.apply(null, new CompoundTag()));
    }

    private static CompoundTag equipment(String item, boolean render) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Item", item);
        tag.putBoolean("Render", render);
        tag.putInt("SlotSize", 3);
        return tag;
    }

    private static Class<?> resolve(String name, boolean registryAware) throws ClassNotFoundException {
        return switch (name) {
            case "io.wispforest.accessories.endec.NbtMapCarrier" -> Carrier.class;
            case "io.wispforest.endec.util.MapCarrier" -> MapCarrier.class;
            case "io.wispforest.endec.SerializationContext" -> Context.class;
            case "io.wispforest.accessories.impl.AccessoriesHolderImpl" -> Holder.class;
            case "io.wispforest.accessories.api.AccessoriesCapability" -> Capability.class;
            case "io.wispforest.owo.serialization.RegistriesAttribute" -> {
                if (!registryAware) throw new ClassNotFoundException(name);
                yield RegistryAttribute.class;
            }
            case "io.wispforest.endec.SerializationAttribute$Instance" -> Attribute.class;
            default -> throw new ClassNotFoundException(name);
        };
    }

    public static class LegacyTrinkets {
        final Map<String, Map<String, Object>> inventory = new LinkedHashMap<>();
        LegacyTrinkets() { inventory.put("chest", new LinkedHashMap<>(Map.of("cloak", new TrinketInventory()))); }
        TrinketInventory slot() { return (TrinketInventory) inventory.get("chest").get("cloak"); }
        public Map<String, Map<String, Object>> getInventory() { return inventory; }
        public void writeToNbt(CompoundTag tag) { writeStorage(tag); }
        void writeStorage(CompoundTag tag) {
            inventory.forEach((group, slots) -> {
                CompoundTag groupTag = new CompoundTag();
                slots.forEach((name, value) -> {
                    TrinketInventory slot = (TrinketInventory) value;
                    CompoundTag slotTag = new CompoundTag();
                    ListTag items = new ListTag();
                    for (int i = 0; i < slot.getContainerSize(); i++) items.add(new CompoundTag());
                    slotTag.put("Items", items);
                    CompoundTag storageMetadata = new CompoundTag();
                    storageMetadata.putBoolean("PersistentOnly", true);
                    slotTag.put("Metadata", storageMetadata);
                    groupTag.put(name, slotTag);
                });
                tag.put(group, groupTag);
            });
        }
        public void readFromNbt(CompoundTag tag) { fail("Storage merge must not be used for remote inventory synchronization"); }
    }

    public static class RegistryTrinkets extends LegacyTrinkets {
        Object lastRegistry;
        public void writeToNbt(CompoundTag tag, Registries registries) { lastRegistry = registries; writeStorage(tag); }
    }

    public static class TrinketInventory extends SimpleContainer {
        CompoundTag metadata = new CompoundTag();
        int effectiveSize = 3;
        final List<String> events = new ArrayList<>();
        TrinketInventory() { super(3); }
        public CompoundTag getSyncTag() { return metadata.copy(); }
        public void applySyncTag(CompoundTag tag) {
            events.add("sync");
            metadata = tag.copy();
            effectiveSize = tag.get("Expanded") == null ? 1 : 3;
        }
        @Override public int getContainerSize() { return effectiveSize; }
        @Override public void clearContent() { events.add("clear"); super.clearContent(); }
    }

    public interface MapCarrier { }
    public record Carrier(CompoundTag compoundTag) implements MapCarrier { }
    public interface Attribute { }
    public record RegistryAttribute(Registries registries) implements Attribute {
        public static RegistryAttribute of(String unsupported) { throw new AssertionError("Wrong registry factory overload"); }
        public static RegistryAttribute of(Registries registries) { return new RegistryAttribute(registries); }
    }
    public static final class Registries { }
    public static final class Capability {
        public Holder getHolder() { return new Holder(); }
    }
    public record Context(Registries registries) {
        public static Context empty() { return new Context(null); }
        public static Context attributes(Attribute... attributes) {
            return new Context(((RegistryAttribute) attributes[0]).registries());
        }
    }

    public static final class Holder {
        CompoundTag data = new CompoundTag();
        Object registry;
        Capability capability;
        final List<String> events = new ArrayList<>();
        public void write(MapCarrier carrier, Context context) {
            registry = context.registries();
            ((Carrier) carrier).compoundTag().merge(data);
        }
        public void read(MapCarrier carrier, Context context) {
            events.add("read");
            registry = context.registries();
            data = ((Carrier) carrier).compoundTag().copy();
        }
        public void init(Capability capability) { events.add("init"); this.capability = capability; }
    }
}
