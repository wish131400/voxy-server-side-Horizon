package dev.xantha.vss.smoke;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Development-only mod; its classes and metadata must never enter a production VSS JAR. */
public final class VSSClientSmoke implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VSS Client Smoke");
    private static final int REQUIRED_TITLE_TICKS = 80;
    private static final long REQUIRED_TITLE_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long DEADLINE_SECONDS = 120;

    private final AtomicBoolean finished = new AtomicBoolean();
    private final long startedNanos = System.nanoTime();
    private final String startedAt = Instant.now().toString();
    private final ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "VSS-client-smoke-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private Path reportPath;
    private GuiProbe gui;
    private volatile long ticks;
    private volatile int titleTicks;
    private long titleStartedNanos;
    private volatile String lastScreen = "unobserved";
    private boolean predictionResourceAtlasValidated;

    @Override
    public void onInitializeClient() {
        String configuredReport = System.getProperty("vss.smoke.report");
        if (configuredReport == null || configuredReport.isBlank()) {
            throw new IllegalStateException("Client smoke requires -Dvss.smoke.report=<report path>");
        }
        reportPath = Path.of(configuredReport).toAbsolutePath().normalize();
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> finish(false, "client_exited_before_title_validation", null, false),
                "VSS-client-smoke-shutdown"));
        deadline.schedule(() -> finish(false, "title_deadline_exceeded", null, true),
                DEADLINE_SECONDS, TimeUnit.SECONDS);
        try {
            gui = new GuiProbe();
            for (String id : requiredMods()) {
                if (!FabricLoader.getInstance().isModLoaded(id)) {
                    throw new IllegalStateException("Required smoke dependency is missing: " + id);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            finish(false, "initialization_failed", failure, true);
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOGGER.info("Client initialization smoke armed; waiting for the title screen");
    }

    private static String[] requiredMods() {
        return Arrays.stream(System.getProperty("vss.smoke.requiredMods", "vss,voxy,sodium,fabric-api").split(","))
                .map(String::trim).filter(id -> !id.isEmpty()).toArray(String[]::new);
    }

    private void tick(Minecraft client) {
        if (finished.get()) return;
        ticks++;
        try {
            Object screen = gui.screen(client);
            lastScreen = screen == null ? "none" : screen.getClass().getName();
            if (!(screen instanceof TitleScreen) || gui.overlay(client) != null || client.level != null) {
                titleTicks = 0;
                titleStartedNanos = 0L;
                return;
            }
            if (titleStartedNanos == 0L) titleStartedNanos = System.nanoTime();
            titleTicks++;
            if (titleTicks >= REQUIRED_TITLE_TICKS
                    && System.nanoTime() - titleStartedNanos >= REQUIRED_TITLE_NANOS) {
                // Exercise the same loaded-resource lookup used after a worldgen
                // profile arrives, even while this bounded probe stays at title.
                var probe = dev.xantha.vss.client.prediction.ClientPredictionState.class
                        .getDeclaredMethod("currentResourceAtlasToken", Minecraft.class);
                probe.setAccessible(true);
                if (probe.invoke(null, client) == null)
                    throw new IllegalStateException("Prediction block atlas returned no resource sprite");
                predictionResourceAtlasValidated = true;
                finish(true, "title_screen_initialized", null, true);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            finish(false, "title_probe_failed", failure, true);
        }
    }

    private void finish(boolean passed, String reason, Throwable failure, boolean stopClient) {
        if (!finished.compareAndSet(false, true)) return;
        deadline.shutdown();
        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        report.addProperty("status", passed ? "passed" : "failed");
        report.addProperty("reason", reason);
        report.addProperty("validation", "client_initialization_and_title_screen");
        report.addProperty("gameplayValidated", false);
        report.addProperty("predictionResourceAtlasValidated", predictionResourceAtlasValidated);
        report.addProperty("startedAt", startedAt);
        report.addProperty("finishedAt", Instant.now().toString());
        report.addProperty("elapsedSeconds", (System.nanoTime() - startedNanos) / 1_000_000_000.0);
        report.addProperty("deadlineSeconds", DEADLINE_SECONDS);
        report.addProperty("ticks", ticks);
        report.addProperty("consecutiveTitleTicks", titleTicks);
        report.addProperty("requiredTitleTicks", REQUIRED_TITLE_TICKS);
        report.addProperty("lastScreen", lastScreen);
        report.addProperty("javaVersion", System.getProperty("java.version"));
        if (failure != null) report.addProperty("error", failure.toString());
        JsonArray mods = new JsonArray();
        FabricLoader.getInstance().getAllMods().stream()
                .sorted(java.util.Comparator.comparing(mod -> mod.getMetadata().getId()))
                .forEach(mod -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", mod.getMetadata().getId());
                    entry.addProperty("version", mod.getMetadata().getVersion().getFriendlyString());
                    mods.add(entry);
                });
        report.add("mods", mods);
        try {
            Files.createDirectories(reportPath.getParent());
            Path temporary = reportPath.resolveSibling(reportPath.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(report),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, reportPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, reportPath, StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.info("Client initialization smoke {}: {}", passed ? "passed" : "failed", reason);
        } catch (IOException reportFailure) {
            LOGGER.error("Could not write client initialization smoke report", reportFailure);
        } finally {
            if (stopClient) {
                Minecraft client = Minecraft.getInstance();
                if (client.isSameThread()) client.stop();
                else client.execute(client::stop);
            }
        }
    }

    /** Reflection is confined to this development probe for the 26.2 GUI ownership change. */
    private static final class GuiProbe {
        private final Field guiField;
        private final Field screenField;
        private final Method screenMethod;
        private final Method overlayMethod;

        GuiProbe() throws ReflectiveOperationException {
            Field legacyScreen;
            try {
                legacyScreen = Minecraft.class.getField("screen");
            } catch (NoSuchFieldException absentLegacyScreen) {
                legacyScreen = null;
            }
            screenField = legacyScreen;
            if (legacyScreen != null) {
                guiField = null;
                screenMethod = null;
                overlayMethod = Minecraft.class.getMethod("getOverlay");
            } else {
                guiField = Minecraft.class.getField("gui");
                screenMethod = guiField.getType().getMethod("screen");
                overlayMethod = guiField.getType().getMethod("overlay");
            }
        }

        Object screen(Minecraft client) throws ReflectiveOperationException {
            if (screenField != null) return screenField.get(client);
            Object gui = guiField.get(client);
            return gui == null ? null : screenMethod.invoke(gui);
        }

        Object overlay(Minecraft client) throws ReflectiveOperationException {
            Object target = guiField == null ? client : guiField.get(client);
            return target == null ? null : overlayMethod.invoke(target);
        }
    }
}
