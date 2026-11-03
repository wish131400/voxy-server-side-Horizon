package dev.xantha.vss.smoke;

import com.google.gson.JsonObject;
import dev.xantha.vss.client.VSSVoxyOptionsIntegration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Opt-in real client probe; no classes from this source set enter VSS releases. */
final class SodiumOptionsSmoke {
    static JsonObject verify(Minecraft client, Screen parent) throws ReflectiveOperationException {
        boolean legacy = VSSVoxyOptionsIntegration.hasLegacyOptions();
        String gui = "net.caffeinemc.mods.sodium.client.gui.";
        if (legacy) {
            try { Class.forName(gui + "SodiumOptionsGUI"); }
            catch (ClassNotFoundException oldNamespace) { gui = "me.jellysquid.mods.sodium.client.gui."; }
        }
        Class<?> type = Class.forName(gui + (legacy ? "SodiumOptionsGUI" : "VideoSettingsScreen"));
        Screen ordinary = (Screen) type.getMethod("createScreen", Screen.class).invoke(null, parent);
        List<?> ordinaryPages = pages(ordinary, legacy);
        requireVssPages(ordinaryPages, legacy);
        if (legacy) {
            List<?> original = List.copyOf(ordinaryPages);
            VSSVoxyOptionsIntegration.addPage(ordinary);
            if (!original.equals(pages(ordinary, true))) throw new IllegalStateException("Duplicate VSS page registration");
        }
        Screen direct = VSSVoxyOptionsIntegration.createSodiumConfigScreen(parent);
        if (!type.isInstance(direct)) throw new IllegalStateException("VSS entry did not return the Sodium screen");
        List<?> directPages = pages(direct, legacy);
        requireVssPages(directPages, legacy);
        String predictionName = Component.translatable("vss.voxy_options.prediction_title").getString();
        Object prediction = null;
        for (Object page : directPages) if (predictionName.equals(name(page, legacy))) prediction = page;
        try {
            show(client, direct);
            call(direct, legacy ? "setPage" : "jumpToPage", prediction);
            if (legacy) {
                Field controls = type.getDeclaredField("controls");
                controls.setAccessible(true);
                if (((List<?>) controls.get(direct)).isEmpty()) throw new IllegalStateException("No prediction controls initialized");
            }
        } finally { show(client, parent); }
        JsonObject result = new JsonObject();
        result.addProperty("status", "passed");
        result.addProperty("screenClass", type.getName());
        result.addProperty("ordinaryVideoEntry", true);
        result.addProperty("directVssEntry", true);
        result.addProperty("predictionPageInitialized", true);
        result.addProperty("vssPages", 2);
        return result;
    }

    private static List<?> pages(Object screen, boolean legacy) throws ReflectiveOperationException {
        if (legacy) {
            Field field = screen.getClass().getDeclaredField("pages");
            field.setAccessible(true);
            return (List<?>) field.get(screen);
        }
        Object config = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager").getField("CONFIG").get(null);
        for (Object mod : (List<?>) call(config, "getModOptions")) {
            if ("vss".equals(call(mod, "configId"))) return (List<?>) call(mod, "pages");
        }
        return new ArrayList<>();
    }

    private static String name(Object page, boolean legacy) throws ReflectiveOperationException {
        return ((Component) call(page, legacy ? "getName" : "name")).getString();
    }

    private static void requireVssPages(List<?> pages, boolean legacy) throws ReflectiveOperationException {
        for (String key : new String[]{"vss.voxy_options.sync_title", "vss.voxy_options.prediction_title"}) {
            int found = 0;
            for (Object page : pages) if (Component.translatable(key).getString().equals(name(page, legacy))) found++;
            if (found != 1) throw new IllegalStateException("Expected exactly one page: " + key + ", found " + found);
        }
    }

    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) return method.invoke(target, args);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static void show(Minecraft client, Screen screen) throws ReflectiveOperationException {
        // 26.2 moved screen ownership into client.gui; older clients own it directly.
        Object owner = client;
        try { Minecraft.class.getMethod("setScreen", Screen.class); }
        catch (NoSuchMethodException moved) { owner = Minecraft.class.getField("gui").get(client); }
        owner.getClass().getMethod("setScreen", Screen.class).invoke(owner, screen);
    }
}
