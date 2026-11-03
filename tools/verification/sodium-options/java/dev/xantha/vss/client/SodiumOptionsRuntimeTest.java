package dev.xantha.vss.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonParser;
import dev.xantha.vss.config.VSSClientConfig;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes the production page builders with the selected, unmodified Sodium release. */
class SodiumOptionsRuntimeTest {
    @TempDir Path configDirectory;

    @Test void actualReleaseBuildsBothPagesAndAppliesAndSavesTheirBindings() throws Exception {
        String previous = System.getProperty("vss.configDir");
        System.setProperty("vss.configDir", configDirectory.toString());
        var loader = mock(FabricLoader.class);
        when(loader.getEnvironmentType()).thenReturn(EnvType.CLIENT);
        when(loader.getModContainer(anyString())).thenReturn(Optional.empty());
        try (var fabric = mockStatic(FabricLoader.class); var minecraft = mockStatic(Minecraft.class)) {
            fabric.when(FabricLoader::getInstance).thenReturn(loader);
            minecraft.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
            assertTrue(VSSVoxyOptionsIntegration.isSodiumPresent());
            if (VSSVoxyOptionsIntegration.hasLegacyOptions()) verifyLegacy();
            else verifyModern();
        } finally {
            if (previous == null) System.clearProperty("vss.configDir");
            else System.setProperty("vss.configDir", previous);
        }
    }

    private void verifyLegacy() throws Exception {
        List<Object> pages = new ArrayList<>();
        VSSVoxyOptionsIntegration.addPage(pages);
        assertPages(pages, "getName");
        List<Object> first = List.copyOf(pages);
        VSSVoxyOptionsIntegration.addPage(pages);
        assertEquals(first, pages, "Constructor, Options API and Mod Menu must reuse the same pages");
        Object receive = findLegacyOption(pages, "vss.voxy_options.receive_server_lods");
        Object prediction = findLegacyOption(pages, "vss.voxy_options.prediction");
        for (Object option : List.of(receive, prediction)) {
            boolean old = (boolean) call(option, "getValue");
            call(option, "setValue", !old);
            assertTrue((boolean) call(option, "hasChanged"));
            call(option, "applyChanges");
            call(call(option, "getStorage"), "save");
            assertEquals(!old, call(option, "getValue"));
            assertSaved(option == receive ? "receiveServerLods" : "enablePrediction", !old);
            call(option, "setValue", old);
            call(option, "applyChanges");
            call(call(option, "getStorage"), "save");
        }
        Object distance = findLegacyOption(pages, "vss.voxy_options.prediction_distance_blocks");
        assertNotNull(call(distance, "getControl"), "Integer sliders must use this Sodium family's types");
        Object tier = findLegacyOption(pages, "vss.voxy_options.performance_tier");
        assertNotNull(call(tier, "getControl"), "Enum controls must use this Sodium family's types");
    }

    private void verifyModern() throws Exception {
        String root = "net.caffeinemc.mods.sodium.client.config.";
        Object builder = Class.forName(root + "builder.ConfigBuilderImpl")
                .getConstructor(Function.class, String.class).newInstance((Function<String, Object>) id -> null, "vss");
        Method register = VSSVoxyOptionsIntegration.class.getDeclaredMethod("addSodium08Config", Object.class);
        register.setAccessible(true);
        register.invoke(null, builder);
        Collection<?> mods = (Collection<?>) call(builder, "build");
        assertEquals(1, mods.size(), "The real builder must accept the complete VSS option tree");
        Object mod = mods.iterator().next();
        assertEquals("vss", call(mod, "configId"));
        List<?> pages = (List<?>) call(mod, "pages");
        assertPages(pages, "name");
        // Config applies bindings and invokes the same storage callbacks as Sodium's Apply button.
        Object config = Class.forName(root + "structure.Config").getConstructor(List.class).newInstance(List.of(mod));
        call(config, "resetAllOptionsFromBindings");
        List<Object> all = new ArrayList<>();
        for (Object page : pages) for (Object group : (List<?>) call(page, "groups")) {
            all.addAll((List<?>) call(group, "options"));
        }
        assertTrue(all.size() >= 10, "Both pages need their actual controls, not empty placeholders");
        boolean old = VSSClientConfig.CONFIG.enablePrediction;
        Object prediction = all.stream().filter(option -> {
            try { return ((Component) call(option, "getName")).getString()
                    .equals(Component.translatable("vss.voxy_options.prediction").getString()); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).findFirst().orElseThrow();
        call(prediction, "modifyValue", !old);
        call(config, "applyAllOptions");
        assertEquals(!old, VSSClientConfig.CONFIG.enablePrediction);
        assertSaved("enablePrediction", !old);
        call(prediction, "modifyValue", old);
        call(config, "applyAllOptions");
    }

    private void assertSaved(String field, boolean value) throws Exception {
        var json = JsonParser.parseString(Files.readString(configDirectory.resolve("vss-client-config.json"))).getAsJsonObject();
        assertEquals(value, json.get(field).getAsBoolean());
    }

    private static void assertPages(List<?> pages, String nameMethod) throws Exception {
        assertEquals(2, pages.size());
        assertEquals(Component.translatable("vss.voxy_options.sync_title").getString(),
                ((Component) call(pages.get(0), nameMethod)).getString());
        assertEquals(Component.translatable("vss.voxy_options.prediction_title").getString(),
                ((Component) call(pages.get(1), nameMethod)).getString());
    }

    private static Object findLegacyOption(List<?> pages, String key) throws Exception {
        for (Object page : pages) for (Object option : (List<?>) call(page, "getOptions")) {
            if (((Component) call(option, "getName")).getString().equals(Component.translatable(key).getString())) return option;
        }
        throw new AssertionError("Missing option: " + key);
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            method.setAccessible(true);
            return method.invoke(target, args);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
}
