package dev.xantha.vss.smoke;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xantha.vss.api.VSSApi;
import dev.xantha.vss.client.prediction.ClientPredictionState;
import dev.xantha.vss.compat.StrictLodVisibility;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.compat.VoxyRenderAccess;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Isolated real-world verification; no code or metadata from this directory is published with VSS. */
public final class VSSCommunityWorldSmoke implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("VSS Community World Smoke");
    private final AtomicBoolean finished = new AtomicBoolean();
    private final JsonArray stages = new JsonArray(), sessions = new JsonArray();
    private final long startedNanos = System.nanoTime();
    private final String startedAt = Instant.now().toString();
    private Path reportPath;
    private String address, stage = "title";
    private int ticks, stageTick, sessionNumber;
    private long composedAtConnect, composedAtEnable, composedAtReload;
    private Object rendererBeforeReload;
    private Object sessionLevel, sessionEngine, previousLevel, previousEngine;
    private JsonObject sessionReport;
    private boolean markerNative, markerAuthoritative, markerOutsideVanilla;
    private boolean profileReady, gpuNodesUploaded;
    private long deadlineSeconds;
    private int markerX, markerY, markerZ;

    @Override public void onInitializeClient() {
        String configured = System.getProperty("vss.worldSmoke.report");
        if (configured == null) throw new IllegalStateException("Missing isolated world-smoke report path");
        reportPath = Path.of(configured).toAbsolutePath().normalize();
        address = System.getProperty("vss.worldSmoke.address", "127.0.0.1:25565");
        // Refuse to operate against any external server or the user's current game.
        if (!address.matches("127\\.0\\.0\\.1:[0-9]{1,5}"))
            throw new IllegalStateException("World smoke requires an isolated loopback server");
        markerX = Integer.getInteger("vss.worldSmoke.markerX", 160);
        markerY = Integer.getInteger("vss.worldSmoke.markerY", 200);
        markerZ = Integer.getInteger("vss.worldSmoke.markerZ", 0);
        deadlineSeconds = Math.max(60, Math.min(900, Long.getLong("vss.worldSmoke.timeout", 600L)));
        var config = VSSClientConfig.CONFIG;
        config.receiveServerLods = true;
        config.enableLocalChunkIngestion = false;
        config.enablePrediction = true;
        config.enablePredictionLoading = true;
        config.predictionDistanceBlocks = 1024;
        config.predictionBackgroundWorkers = 2;
        config.enableXaeroMapBridge = false;
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> finish(false, "client_exited_before_world_probe_completed", null, false)));
        var deadline = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "VSS-world-smoke-deadline"); thread.setDaemon(true); return thread;
        });
        deadline.schedule(() -> finish(false, "world_probe_deadline_exceeded", null, true),
                deadlineSeconds, TimeUnit.SECONDS);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOG.info("Isolated world probe armed: {}", address);
    }

    private void tick(Minecraft client) {
        if (finished.get()) return;
        ticks++;
        try {
            client.options.pauseOnLostFocus = false;
            if (ticks % 100 == 0) LOG.info("World probe stage={}, seconds={}, network={}, prediction={}, bridge={}",
                    stage, (System.nanoTime() - startedNanos) / 1_000_000_000L,
                    VSSClientNetworking.diagnostics(), ClientPredictionState.diagnostics(), VoxyPredictionBridge.diagnostics());
            if (stage.equals("title")) {
                if (client.screen instanceof TitleScreen && client.getOverlay() == null && ticks > 40) {
                    client.options.renderDistance().set(2);
                    beginConnect(client);
                }
                return;
            }
            if (stage.equals("disconnected")) {
                if (client.level != null || VSSApi.clientSession().isClientLodSessionActive()) return;
                if (mapSize(ClientPredictionState.class, "MANAGERS") != 0
                        || mapSize(VoxyPredictionBridge.class, "WORLDS") != 0) return;
                if ((boolean) previousEngine.getClass().getMethod("isLive").invoke(previousEngine)) return;
                if (ticks - stageTick < 40) return;
                sessionReport.addProperty("disconnectClosedPredictionManagers", true);
                sessionReport.addProperty("disconnectClosedBridgeContexts", true);
                sessionReport.addProperty("disconnectRetiredNativeEngine", true);
                beginConnect(client);
                return;
            }
            if (client.level == null || client.player == null) return;
            if (!client.level.dimension().equals(Level.OVERWORLD)) throw new IllegalStateException("Unexpected smoke dimension");
            // Rotate the actual camera to drive native Voxy traversal and mesh upload through real frames.
            client.player.setYRot((ticks * 0.4f) % 360);
            client.player.setXRot(15.0f);
            if (stage.equals("connecting")) {
                if (!VSSApi.clientSession().isClientLodSessionActive()) return;
                Object engine = actualEngine(client.level);
                if (engine == null) return;
                if (sessionNumber == 2 && (client.level == previousLevel || engine == previousEngine))
                    throw new IllegalStateException("Reconnect reused a retired level or engine identity");
                sessionLevel = client.level; sessionEngine = engine;
                sessionReport.addProperty("newLevelIdentity", sessionNumber == 1 || sessionLevel != previousLevel);
                sessionReport.addProperty("newEngineIdentity", sessionNumber == 1 || sessionEngine != previousEngine);
                next("warming");
            }
            if (client.level != sessionLevel) return;
            Map<String, Object> bridge = VoxyPredictionBridge.diagnostics();
            if (stage.equals("warming")) {
                if (ticks % 20 == 0) {
                    profileReady = (boolean) staticField(ClientPredictionState.class, "profileReady");
                    gpuNodesUploaded = (boolean) staticField(StrictLodVisibility.class, "uploadedNodes");
                    markerNative = nativeMarker(sessionEngine);
                    markerAuthoritative = confirmed(sessionEngine, markerX >> 4, markerZ >> 4);
                    markerOutsideVanilla = client.level.getChunkSource().getChunk(markerX >> 4, markerZ >> 4,
                            net.minecraft.world.level.chunk.ChunkStatus.FULL, false) == null;
                    if (ticks % 100 == 0) LOG.info("World probe evidence markerNative={}, markerAuthoritative={}, markerOutsideVanilla={}, profileReady={}, gpuNodesUploaded={}",
                            markerNative, markerAuthoritative, markerOutsideVanilla, profileReady, gpuNodesUploaded);
                }
                long delta = number(bridge, "sectionsComposed") - composedAtConnect;
                if (!profileReady || !ClientPredictionState.hasReadyTiles(client.level.dimension())
                        || !VoxyPredictionBridge.active(client.level.dimension()) || delta < 1
                        || !markerNative || !markerAuthoritative || !markerOutsideVanilla || !gpuNodesUploaded
                        || VSSClientNetworking.getColumnsReceived() < 1) return;
                sessionReport.addProperty("worldgenProfileReady", true);
                sessionReport.addProperty("serverColumnPayloadsReceived", VSSClientNetworking.getColumnsReceived());
                sessionReport.addProperty("nativeMarkerBlockMatched", true);
                sessionReport.addProperty("markerColumnSettled", true);
                sessionReport.addProperty("markerOutsideVanillaClientChunks", true);
                sessionReport.addProperty("newPredictionSectionsComposed", delta);
                sessionReport.addProperty("nativeGpuNodesUploaded", true);
                if (sessionNumber == 2) {
                    sessions.add(sessionReport);
                    finish(true, "two_real_sessions_prediction_toggle_and_reconnect_passed", null, true);
                    return;
                }
                VSSClientConfig.CONFIG.enablePrediction = false;
                next("prediction_off");
                return;
            }
            if (stage.equals("prediction_off")) {
                if (ticks - stageTick < 40 || VoxyPredictionBridge.active(client.level.dimension())
                        || number(bridge, "pendingWithdrawals") != 0 || !VSSApi.clientSession().isClientLodSessionActive()) return;
                if (!nativeMarker(sessionEngine)) throw new IllegalStateException("Prediction toggle removed native real data");
                sessionReport.addProperty("predictionDisabledWithdrawalsDrained", true);
                sessionReport.addProperty("serverSessionAndNativeDataSurvivedDisable", true);
                composedAtEnable = number(bridge, "sectionsComposed");
                VSSClientConfig.CONFIG.enablePrediction = true;
                next("prediction_on");
                return;
            }
            if (stage.equals("prediction_on")) {
                if (ticks - stageTick < 40 || !VoxyPredictionBridge.active(client.level.dimension())
                        || number(bridge, "sectionsComposed") <= composedAtEnable) return;
                sessionReport.addProperty("predictionReenabledAndComposedNewSections", true);
                rendererBeforeReload = currentRenderer();
                if (rendererBeforeReload == null) throw new IllegalStateException("No real renderer to reload");
                composedAtReload = number(bridge, "sectionsComposed");
                client.levelRenderer.allChanged();
                next("renderer_reloaded");
                return;
            }
            if (stage.equals("renderer_reloaded")) {
                Object renderer = currentRenderer();
                if (ticks - stageTick < 40 || renderer == null || renderer == rendererBeforeReload
                        || !VoxyPredictionBridge.active(client.level.dimension())
                        || number(bridge, "sectionsComposed") <= composedAtReload) return;
                if (actualEngine(client.level) != sessionEngine || !nativeMarker(sessionEngine)
                        || !confirmed(sessionEngine, markerX >> 4, markerZ >> 4))
                    throw new IllegalStateException("Renderer reload lost the current native world or its authority");
                sessionReport.addProperty("rendererReloadCreatedNewRendererAndComposed", true);
                sessionReport.addProperty("rendererReloadPreservedEngineAndAuthority", true);
                previousLevel = sessionLevel; previousEngine = sessionEngine;
                next("disconnected");
                disconnect(client);
                sessions.add(sessionReport);
            }
        } catch (Throwable failure) {
            finish(false, "world_probe_failed_at_" + stage, failure, true);
        }
    }

    private void beginConnect(Minecraft client) {
        sessionNumber++;
        if (sessionNumber == 2) markerX += 16;
        sessionReport = new JsonObject(); sessionReport.addProperty("session", sessionNumber);
        composedAtConnect = number(VoxyPredictionBridge.diagnostics(), "sectionsComposed");
        markerNative = markerAuthoritative = markerOutsideVanilla = profileReady = gpuNodesUploaded = false;
        sessionLevel = sessionEngine = null;
        next("connecting");
        connect(client, address);
    }

    private void next(String value) {
        stage = value; stageTick = ticks;
        JsonObject event = new JsonObject(); event.addProperty("stage", value);
        event.addProperty("session", sessionNumber);
        event.addProperty("elapsedSeconds", (System.nanoTime() - startedNanos) / 1_000_000_000.0);
        stages.add(event); LOG.info("World probe entered {} for session {}", value, sessionNumber);
        try {
            JsonObject progress = event.deepCopy();
            progress.addProperty("markerX", markerX); progress.addProperty("markerY", markerY);
            progress.addProperty("markerZ", markerZ);
            progress.addProperty("markerBlock", sessionNumber == 2 ? "minecraft:emerald_block" : "minecraft:diamond_block");
            Path path = reportPath.resolveSibling("progress.json");
            Path temporary = path.resolveSibling("progress.json.tmp");
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, new GsonBuilder().create().toJson(progress), StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { throw new IllegalStateException("World-smoke progress channel failed", failure); }
    }

    private boolean nativeMarker(Object engine) throws ReflectiveOperationException {
        engine.getClass().getMethod("acquireRef").invoke(engine);
        Object section = null;
        try {
            section = engine.getClass().getMethod("acquireIfExists", int.class, int.class, int.class, int.class)
                    .invoke(engine, 0, markerX >> 5, markerY >> 5, markerZ >> 5);
            if (section == null) return false;
            long voxel = new VoxyRenderAccess.Sections(section.getClass()).read(section)
                    .get((markerY & 31) << 10 | (markerZ & 31) << 5 | markerX & 31);
            Object mapper = engine.getClass().getMethod("getMapper").invoke(engine);
            BlockState state = (BlockState) mapper.getClass().getMethod("getBlockStateFromBlockId", int.class)
                    .invoke(mapper, (int) ((voxel >>> 27) & 0xfffffL));
            return state != null && state.is(sessionNumber == 2 ? Blocks.EMERALD_BLOCK : Blocks.DIAMOND_BLOCK);
        } finally {
            try { if (section != null) section.getClass().getMethod("release").invoke(section); }
            finally { engine.getClass().getMethod("releaseRef").invoke(engine); }
        }
    }

    private static Object actualEngine(net.minecraft.client.multiplayer.ClientLevel level) throws ReflectiveOperationException {
        return Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier")
                .getMethod("ofEngineNullable", Level.class).invoke(null, level);
    }
    private static Object currentRenderer() throws ReflectiveOperationException {
        Object context = staticField(VoxyPredictionBridge.class, "current");
        if (context == null) return null;
        Field field = context.getClass().getDeclaredField("renderSystem");
        field.setAccessible(true); return field.get(context);
    }
    private static boolean confirmed(Object engine, int x, int z) throws ReflectiveOperationException {
        var method = Class.forName("dev.xantha.vss.compat.VoxyCompat")
                .getDeclaredMethod("confirmedPredictionColumn", Object.class, int.class, int.class);
        method.setAccessible(true); return (boolean) method.invoke(null, engine, x, z);
    }
    private static Object staticField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    private static int mapSize(Class<?> owner, String name) throws ReflectiveOperationException {
        Map<?, ?> map = (Map<?, ?>) staticField(owner, name);
        synchronized (map) { return map.size(); }
    }
    private static long number(Map<String, Object> values, String key) {
        Object value = values.get(key); return value instanceof Number n ? n.longValue() : 0L;
    }

    private void finish(boolean passed, String reason, Throwable failure, boolean stop) {
        if (!finished.compareAndSet(false, true)) return;
        var gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject report = new JsonObject();
        report.addProperty("schema", 1); report.addProperty("status", passed ? "passed" : "failed");
        report.addProperty("validation", "isolated_real_server_client_network_native_ingest_prediction_lifecycle");
        report.addProperty("reason", reason); report.addProperty("lastStage", stage);
        report.addProperty("visualValidated", false); report.addProperty("gameplayValidated", false);
        report.addProperty("performanceValidated", false);
        report.addProperty("startedAt", startedAt); report.addProperty("finishedAt", Instant.now().toString());
        report.addProperty("elapsedSeconds", (System.nanoTime() - startedNanos) / 1_000_000_000.0);
        report.addProperty("deadlineSeconds", deadlineSeconds); report.addProperty("ticks", ticks);
        report.add("stages", stages); report.add("sessions", sessions);
        if (sessionReport != null) report.add("currentSession", sessionReport);
        report.addProperty("markerNative", markerNative); report.addProperty("markerAuthoritative", markerAuthoritative);
        report.addProperty("markerOutsideVanilla", markerOutsideVanilla); report.addProperty("profileReady", profileReady);
        if (failure != null) report.addProperty("error", failure.toString());
        try {
            report.add("bridge", gson.toJsonTree(VoxyPredictionBridge.diagnostics()));
            report.addProperty("prediction", ClientPredictionState.diagnostics());
            report.addProperty("network", VSSClientNetworking.diagnostics());
            report.addProperty("strictLod", StrictLodVisibility.diagnostics());
            report.add("mods", gson.toJsonTree(FabricLoader.getInstance().getAllMods().stream()
                    .map(mod -> mod.getMetadata().getId() + ":" + mod.getMetadata().getVersion().getFriendlyString()).sorted().toList()));
            Files.createDirectories(reportPath.getParent());
            Path temporary = reportPath.resolveSibling(reportPath.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(report) + "\n", StandardCharsets.UTF_8);
            Files.move(temporary, reportPath, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("World probe {}: {}", passed ? "passed" : "failed", reason);
        } catch (Exception reportFailure) { LOG.error("World probe report failed", reportFailure); }
        if (stop) {
            Minecraft client = Minecraft.getInstance();
            if (client.isSameThread()) client.stop(); else client.execute(client::stop);
        }
    }

    private static void connect(Minecraft client, String address) {
        // VSS_WORLD_SMOKE_CONNECT
        throw new UnsupportedOperationException("Compile using community_world_smoke.gradle");
    }
    private static void disconnect(Minecraft client) {
        // VSS_WORLD_SMOKE_DISCONNECT
        throw new UnsupportedOperationException("Compile using community_world_smoke.gradle");
    }
}
